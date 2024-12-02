package me.uniodex.velocityrcon.server;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import me.uniodex.velocityrcon.VelocityRcon;
import me.uniodex.velocityrcon.commandsource.IRconCommandSource;
import net.kyori.adventure.text.format.NamedTextColor;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class RconHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final byte FAILURE = -1;
    private static final byte TYPE_RESPONSE = 0;
    private static final byte TYPE_COMMAND = 2;
    private static final byte TYPE_LOGIN = 3;

    private final String password;
    private boolean loggedIn = false;
    private final RconServer rconServer;
    private final IRconCommandSource commandSender;

    public RconHandler(RconServer rconServer, String password) {
        this.rconServer = rconServer;
        this.password = password;
        this.commandSender = new IRconCommandSource(rconServer.getServer());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
        buf = buf.order(ByteOrder.LITTLE_ENDIAN);
        if (buf.readableBytes() < 8) {
            return;
        }

        int requestId = buf.readInt();
        int type = buf.readInt();

        byte[] payloadData = new byte[buf.readableBytes() - 2];
        buf.readBytes(payloadData);
        String payload = new String(payloadData, StandardCharsets.UTF_8);

        buf.readBytes(2); // two byte padding

        if (type == TYPE_LOGIN) {
            handleLogin(ctx, payload, requestId);
        } else if (type == TYPE_COMMAND) {
            handleCommand(ctx, payload, requestId);
        } else {
            sendLargeResponse(ctx, requestId, "Unknown request " + Integer.toHexString(type));
        }
    }

    private void handleLogin(ChannelHandlerContext ctx, String payload, int requestId) {
        if (password.equals(payload)) {
            loggedIn = true;
            sendResponse(ctx, requestId, TYPE_COMMAND, "");
            VelocityRcon.getInstance().getLogger().info("Rcon connection from [{}]", ctx.channel().remoteAddress());
        } else {
            loggedIn = false;
            sendResponse(ctx, FAILURE, TYPE_COMMAND, "");
        }
    }

    @SuppressWarnings("LoggingSimilarMessage")
    private void handleCommand(ChannelHandlerContext ctx, String payload, int requestId) {
        if (!loggedIn) {
            sendResponse(ctx, FAILURE, TYPE_COMMAND, "");
            return;
        }

        try {
            VelocityRcon.getInstance().getLogger().info("Executing RCON command: {}", payload);

            if (payload.equalsIgnoreCase("end") || payload.equalsIgnoreCase("stop")) {
                String shutdownMessage = "Shutting down the proxy...";
                sendLargeResponse(ctx, requestId, shutdownMessage);
                rconServer.getServer().shutdown();
                return;
            }

            if (payload.startsWith("lpv")) {
                LuckPerms luckPerms = LuckPermsProvider.get();
                String command = payload.substring(4).trim();

                if (command.equalsIgnoreCase("help")) {
                    String helpMessage = "Available commands: lpv help, lpv user [uuid] parent add [group], lpv user [uuid] info";
                    sendLargeResponse(ctx, requestId, helpMessage);
                    return;
                }

                if (command.startsWith("user")) {
                    String[] parts = command.split(" ");
                    if (parts.length >= 3) {
                        String uuidString = parts[1];
                        UUID uuid = UUID.fromString(uuidString);
                        String action = parts[2];

                        if (action.equalsIgnoreCase("parent") && parts.length == 5 && parts[3].equalsIgnoreCase("add")) {
                            String group = parts[4];
                            var userManager = luckPerms.getUserManager();
                            var user = userManager.loadUser(uuid).join();

                            try {
                                // Remove existing group nodes
                                user.data().clear(node -> node.getKey().startsWith("group."));

                                // Add new group node
                                user.data().add(Node.builder("group." + group)
                                        .value(true)
                                        .build());

                                // Set as primary group
                                user.setPrimaryGroup(group);

                                // Save changes
                                userManager.saveUser(user).join();

                                String responseMessage = "Added user " + uuidString + " to group " + group;
                                sendLargeResponse(ctx, requestId, responseMessage);
                            } catch (Exception e) {
                                String errorMessage = "Failed to add group: " + e.getMessage();
                                VelocityRcon.getInstance().getLogger().error(errorMessage, e);
                                sendLargeResponse(ctx, requestId, errorMessage);
                            }
                            return;
                        }

                        if (action.equalsIgnoreCase("info")) {
                            var user = luckPerms.getUserManager().loadUser(uuid).join();

                            StringBuilder userInfo = new StringBuilder("User " + uuidString + " info:\n");
                            userInfo.append("Primary Group: ").append(user.getPrimaryGroup() != null ? user.getPrimaryGroup() : "N/A").append("\n");

                            var permissionData = user.getCachedData().getPermissionData().getPermissionMap();
                            userInfo.append("Permissions: ").append(permissionData != null ? permissionData.toString() : "None");

                            sendLargeResponse(ctx, requestId, userInfo.toString());
                            return;
                        }
                    }
                }
            }

            // Execute other commands
            boolean success = rconServer.getServer().getCommandManager().executeAsync(commandSender, payload).join();
            String responseMessage = commandSender.flush();

            if (!success) {
                responseMessage = "Error executing: " + payload + " (" + responseMessage + ")";
            }

            sendLargeResponse(ctx, requestId, responseMessage);

        } catch (Exception e) {
            VelocityRcon.getInstance().getLogger().error("Error processing RCON command", e);
            String errorMessage = "Error: " + e.getMessage();
            sendLargeResponse(ctx, requestId, errorMessage);
        }
    }

    private void sendResponse(ChannelHandlerContext ctx, int requestId, int type, String payload) {
        ByteBuf buf = ctx.alloc().buffer().order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt(requestId);
        buf.writeInt(type);
        buf.writeBytes(payload.getBytes(StandardCharsets.UTF_8));
        buf.writeByte(0);
        buf.writeByte(0);
        ctx.write(buf);
        ctx.flush(); // Explicitly flush the buffer
    }

    private void sendLargeResponse(ChannelHandlerContext ctx, int requestId, String payload) {
        if (payload == null || payload.isEmpty()) {
            sendResponse(ctx, requestId, TYPE_RESPONSE, "");
            return;
        }

        int start = 0;
        while (start < payload.length()) {
            int length = Math.min(payload.length() - start, 2048);
            sendResponse(ctx, requestId, TYPE_RESPONSE, payload.substring(start, start + length));
            start += length;
        }
        ctx.flush(); // Ensure final flush after all chunks
    }
}