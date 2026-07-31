package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.concurrent.Callable;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

/**
 * Command execution and chat on the server endpoint.
 *
 * <p>Commands are the widest single capability MCMCP exposes, because "run a command" covers
 * everything the game's own command set can do. Three things keep that bounded:
 *
 * <ul>
 *   <li>Execution goes through {@link CapturingCommandSender}, which delegates
 *       {@code canUseCommand} to the real sender. Running as a player means running with exactly
 *       that player's permission level.</li>
 *   <li>{@code permissions.blockedCommands} refuses a configurable set outright. It is a backstop,
 *       not a boundary — command names can be spelled around — but it catches the obvious mistakes
 *       like {@code /stop}.</li>
 *   <li>Output is captured and returned, so a model can tell why a command failed instead of
 *       inferring it from a bare exit code.</li>
 * </ul>
 */
public final class ServerCommandTools {

    private ServerCommandTools() {
    }

    public static void register() {
        registerRunCommand();
        registerBroadcast();
        registerTellPlayer();
    }

    private static void registerRunCommand() {
        McpRegistry.registerTool(McpTool.named("server_run_command")
            .title("Run command")
            .description("Run a server command and return everything it printed. Give a command with "
                + "or without the leading slash.\n\n"
                + "By default the command runs with full server authority (console level). Pass "
                + "'asPlayer' to run it as that player instead, with their permission level and their "
                + "position as the command's origin — which is what you want for anything using "
                + "relative coordinates or @s.")
            .schema(JsonSchema.object()
                .string("command", "The command to run, e.g. 'time set day' or '/give Steve stone 64'.")
                .string("asPlayer", "Username to run the command as. Omit to run with console "
                    + "authority.")
                .required("command")
                .build())
            .serverOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowCommands()) {
                    return ToolResult.error("Command execution is disabled by "
                        + "permissions.allowCommands in the MCMCP config.");
                }

                final String rawCommand = context.requireString("command");
                final String command = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
                if (command.trim().isEmpty()) {
                    return ToolResult.error("The command is empty.");
                }
                if (McmcpConfig.isCommandBlocked(command)) {
                    return ToolResult.error("The command '" + command.split(" ")[0] + "' is on the "
                        + "blocked list in permissions.blockedCommands.");
                }

                final String asPlayer = context.getString("asPlayer", null);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        if (server == null) {
                            throw new IllegalStateException("No Minecraft server is running");
                        }

                        ICommandSender origin = server;
                        if (asPlayer != null && !asPlayer.isEmpty()) {
                            EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(asPlayer);
                            if (player == null) {
                                throw new IllegalStateException("No player named '" + asPlayer
                                    + "' is connected.");
                            }
                            origin = player;
                        }

                        CapturingCommandSender sender = new CapturingCommandSender(origin);
                        int returnValue = server.getCommandManager().executeCommand(sender, command);

                        JsonObject json = new JsonObject();
                        json.addProperty("command", command);
                        json.addProperty("ranAs", asPlayer == null ? "server console" : asPlayer);
                        // Minecraft's convention: 0 means the command did not succeed. It is not an
                        // error code — the reason, when there is one, is in the captured output.
                        json.addProperty("result", returnValue);
                        json.addProperty("succeeded", returnValue != 0);

                        JsonArray lines = new JsonArray();
                        for (String line : sender.getOutput()) {
                            lines.add(line);
                        }
                        json.add("output", lines);
                        return json;
                    }
                });

                String output = result.get("output").getAsJsonArray().size() == 0
                    ? "(the command produced no output)"
                    : String.join("\n", com.micatechnologies.minecraft.mcmcp.json.Json
                        .getStringList(result, "output"));
                return ToolResult.text(output).withStructured(result);
            })
            .build());
    }

    private static void registerBroadcast() {
        McpRegistry.registerTool(McpTool.named("server_broadcast")
            .title("Broadcast message")
            .description("Send a chat message to every connected player. Use this to tell players what "
                + "is about to happen before acting on their world.")
            .schema(JsonSchema.object()
                .string("message", "The message text. Plain text; no formatting codes.")
                .required("message")
                .build())
            .serverOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowChat()) {
                    return ToolResult.error("Chat is disabled by permissions.allowChat in the MCMCP "
                        + "config.");
                }
                final String message = context.requireString("message");

                Integer recipients = context.onGameThread(new Callable<Integer>() {
                    @Override
                    public Integer call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        if (server == null) {
                            throw new IllegalStateException("No Minecraft server is running");
                        }
                        server.getPlayerList().sendMessage(new TextComponentString(message));
                        return server.getCurrentPlayerCount();
                    }
                });
                return ToolResult.text("Broadcast to " + recipients + " player(s).");
            })
            .build());
    }

    private static void registerTellPlayer() {
        McpRegistry.registerTool(McpTool.named("server_tell_player")
            .title("Message a player")
            .description("Send a chat message to one connected player.")
            .schema(JsonSchema.object()
                .string("player", "Username of a connected player.")
                .string("message", "The message text.")
                .required("player", "message")
                .build())
            .serverOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowChat()) {
                    return ToolResult.error("Chat is disabled by permissions.allowChat in the MCMCP "
                        + "config.");
                }
                final String username = context.requireString("player");
                final String message = context.requireString("message");

                context.onGameThread(new Callable<Void>() {
                    @Override
                    public Void call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        if (server == null) {
                            throw new IllegalStateException("No Minecraft server is running");
                        }
                        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(username);
                        if (player == null) {
                            throw new IllegalStateException("No player named '" + username
                                + "' is connected.");
                        }
                        player.sendMessage(new TextComponentString(message));
                        return null;
                    }
                });
                return ToolResult.text("Message sent to " + username + ".");
            })
            .build());
    }
}
