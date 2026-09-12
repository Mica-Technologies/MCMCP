package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.McmcpProcess;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.concurrent.Callable;
import net.minecraft.server.MinecraftServer;

/**
 * Stopping the server.
 *
 * <h2>Why this is a tool and not just {@code /stop}</h2>
 *
 * {@code stop} is in {@code permissions.blockedCommands} by default, and should stay there: the
 * command tools hand out a whole command set at once, and a blocklist is how the obviously
 * irreversible members of it are kept out. A tool is the opposite shape — one action, one switch, an
 * honest destructive annotation, and a reply that says what was actually stopped.
 *
 * <p>The counterpart to {@code client_quit}, and it exists for the same reason: a game left running
 * keeps MCMCP's ports, so a harness that cannot stop a server cleanly ends up killing processes from
 * outside and hoping it killed the right one.
 *
 * <h2>The integrated-server case</h2>
 *
 * A singleplayer client with the server endpoint enabled runs an <em>integrated</em> server, and this
 * tool works there too. It is not a smaller action for being integrated — it ends the world the
 * player is standing in — but it does not end the client process, which stays at the main menu. The
 * reply says which kind of server it stopped so a caller is never guessing. {@code client_quit} is
 * what ends a client.
 *
 * @author Mica Technologies
 */
public final class ServerLifecycleTools {

    /**
     * How long to wait after answering before stopping the server.
     *
     * <p>Same reasoning as {@code client_quit}: a caller that gets a dropped connection instead of a
     * reply cannot tell a clean stop from a crash. {@code initiateShutdown} only sets a flag, but
     * what follows it — saving every world, then the JVM exiting — is not something to race an
     * unflushed socket against.
     */
    private static final long SHUTDOWN_DELAY_MILLIS = 500L;

    private ServerLifecycleTools() {
    }

    public static void register() {
        registerStop();
    }

    private static void registerStop() {
        McpRegistry.registerTool(McpTool.named("server_stop")
            .title("Stop the server")
            .description("Stop this Minecraft server, exactly as the /stop command does: players are "
                + "disconnected, every world is saved, and the process exits.\n\n"
                + "This is not reversible from here — nothing in MCMCP can start a server back up, "
                + "because after this there is no endpoint left to ask. On a shared server, tell "
                + "people first with server_broadcast.\n\n"
                + "On a singleplayer client running an integrated server this stops the world rather "
                + "than the game; the client returns to the main menu and keeps running. Use "
                + "client_quit to end a client process. The reply says which kind of server this was.")
            .schema(JsonSchema.noArguments())
            .serverOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowProcessControl()) {
                    return ToolResult.error("Stopping the server is disabled by "
                        + "permissions.allowProcessControl in the MCMCP config.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        JsonObject json = new JsonObject();
                        if (server == null || !server.isServerRunning()) {
                            json.addProperty("stopping", false);
                            json.addProperty("note", "No server is running.");
                            return json;
                        }
                        json.addProperty("stopping", true);
                        json.addProperty("dedicated", server.isDedicatedServer());
                        json.addProperty("onlinePlayers", server.getCurrentPlayerCount());
                        json.addProperty("pid", McmcpProcess.pid());
                        return json;
                    }
                });

                if (!result.get("stopping").getAsBoolean()) {
                    return ToolResult.text("No server is running.").withStructured(result);
                }

                scheduleStop(context);

                return ToolResult.text("Stopping the server. The reply is on its way out ahead of "
                    + "the shutdown; worlds are saved as it goes, which on a large world takes a few "
                    + "seconds.")
                    .withStructured(result);
            })
            .build());
    }

    /**
     * Stops the server a moment from now, off the calling thread.
     *
     * <p>The delay lets the tool result reach the caller first; the thread exists because the
     * handler has to return for that write to happen at all.
     *
     * <p>The stop itself goes back onto the server thread. That is not optional for an integrated
     * server: {@code IntegratedServer.initiateShutdown} blocks on {@code addScheduledTask} to log
     * other players out, and {@code addScheduledTask} runs inline when it is already on the server
     * thread but queues — and then waits — when it is not. Called from this timer thread it would
     * park until the server got round to the task, on a server that is about to stop taking tasks.
     */
    private static void scheduleStop(final ToolContext context) {
        Thread timer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(SHUTDOWN_DELAY_MILLIS);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    // Stopping is still what was asked for; a lost sleep is not a reason to stay up.
                }
                Mcmcp.LOGGER.info("MCMCP is stopping the server at the request of server_stop.");
                try {
                    context.getGameThread().runOnGameThread(new Runnable() {
                        @Override
                        public void run() {
                            MinecraftServer server = ServerThreadBridge.server();
                            if (server != null && server.isServerRunning()) {
                                server.initiateShutdown();
                            }
                        }
                    });
                }
                catch (RuntimeException failed) {
                    // Nothing is left to report this to — the tool result went out half a second
                    // ago — so the log is the only record that a stop was asked for and refused.
                    Mcmcp.LOGGER.error("MCMCP could not stop the server", failed);
                }
            }
        }, "MCMCP-stop");
        // Daemon so this thread is never what holds a JVM open that is trying to exit.
        timer.setDaemon(true);
        timer.start();
    }
}
