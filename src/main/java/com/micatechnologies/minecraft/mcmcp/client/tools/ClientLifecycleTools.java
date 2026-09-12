package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.client.ClientDeferredTasks;
import com.micatechnologies.minecraft.mcmcp.game.McmcpProcess;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Ending the game process.
 *
 * <h2>Why a tool for this at all</h2>
 *
 * {@code client_world_leave} reaches the main menu; the JVM keeps running, and keeps MCMCP's ports.
 * Without a way to end the process, anything that wants to restart a client has to go outside MCMCP
 * and kill it by hand — and the way that goes wrong is silent.
 *
 * <p>A Gradle {@code runClient} forks its own JVM. Stopping the Gradle task leaves the <em>game</em>
 * running and still bound to {@code clientPort}. The next {@code runClient} produces a second game
 * that cannot bind, so every MCP call keeps reaching the first one. Nothing errors: the endpoint
 * answers normally, from the previous build. A verification pass over a freshly built jar then
 * reports that correct edits changed nothing, and the evidence says the edits are wrong.
 *
 * <p>That is what this tool is for: "stop cleanly, relaunch, re-measure" as an ordinary step in a
 * harness rather than a process hunt on the side. When a client has stopped responding and cannot be
 * asked to quit, the fallback is the pid, which {@code client_runtime_info} and
 * {@code mcmcp_endpoint_info} now report.
 *
 * @author Mica Technologies
 */
@SideOnly(Side.CLIENT)
public final class ClientLifecycleTools {

    /**
     * How long to wait after answering before ending the JVM.
     *
     * <p>The one thing this tool must not do is die before its own reply is on the wire. A caller
     * that gets a dropped connection cannot tell "quit worked" from "quit crashed", and that is
     * precisely the case where a clear answer is worth most. The handler returns, the transport
     * writes and flushes, and only then does the shutdown fire — this delay is the gap that makes
     * the ordering certain rather than likely.
     *
     * <p>Half a second is several orders of magnitude more than a loopback write of a few hundred
     * bytes needs, and it is time the caller spends waiting for a process to exit anyway. Do not
     * shorten it to tighten a test.
     */
    private static final long SHUTDOWN_DELAY_MILLIS = 500L;

    private ClientLifecycleTools() {
    }

    public static void register() {
        // The shutdown runs from the client tick, not the scheduled-task drain. Registering here
        // rather than relying on some other tool having done it keeps that true in isolation.
        ClientDeferredTasks.register();
        registerQuit();
    }

    private static void registerQuit() {
        McpRegistry.registerTool(McpTool.named("client_quit")
            .title("Quit the game")
            .description("End this Minecraft client's process, exactly as the Quit Game button does.\n\n"
                + "Use this to restart a client rather than killing it from outside. That matters "
                + "more than it sounds: MCMCP's ports are fixed, the process that bound them keeps "
                + "them, and a game left running by a stopped launcher task goes on answering every "
                + "call from a build you are no longer working on, with no error to say so.\n\n"
                + "By default the current world is left first, so a singleplayer save is flushed the "
                + "same way client_world_leave flushes it. The reply is sent before the process goes; "
                + "after it, this endpoint stops answering and the port is released within a second "
                + "or two.")
            .schema(JsonSchema.object()
                .bool("leaveWorld", "Leave the current world before quitting, so a singleplayer save "
                    + "is written. Defaults to true. Set false only to quit from wherever the client "
                    + "is without waiting for a save.")
                .build())
            .clientOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowProcessControl()) {
                    return ToolResult.error("Quitting is disabled by "
                        + "permissions.allowProcessControl in the MCMCP config.");
                }

                final boolean leaveWorld = context.getBoolean("leaveWorld", true);

                JsonObject result = new JsonObject();
                result.addProperty("quitting", true);
                result.addProperty("pid", McmcpProcess.pid());

                if (leaveWorld) {
                    // Synchronous on purpose, and before the reply: the leave saves the world, and a
                    // reply claiming leftWorld before the save completed would be a lie about the
                    // one thing this argument exists to guarantee. It also keeps the leave and the
                    // shutdown in different ticks, which is what the integrated server's shutdown
                    // race needs -- see ClientWorldTools.leaveLoadedWorld.
                    JsonObject left = ClientWorldTools.leaveLoadedWorld(context);
                    result.addProperty("leftWorld", left.get("left").getAsBoolean());
                    if (left.has("wasSingleplayer")) {
                        result.addProperty("wasSingleplayer", left.get("wasSingleplayer").getAsBoolean());
                    }
                }
                else {
                    result.addProperty("leftWorld", false);
                }

                scheduleShutdown();

                return ToolResult.text("Quitting. The reply is on its way out ahead of the shutdown; "
                    + "this endpoint stops answering within a second or two.")
                    .withStructured(result);
            })
            .build());
    }

    /**
     * Ends the game a moment from now, off the calling thread.
     *
     * <p>Two deferrals, for two different reasons, and neither replaces the other.
     *
     * <p>The first is the timer: it lets the tool result be written and flushed before the JVM goes.
     * It runs on a thread of its own because the handler thread must return for that write to
     * happen at all.
     *
     * <p>The second is {@link ClientDeferredTasks}: {@code Minecraft.shutdown} sets {@code running}
     * false, and the game loop then tears down the integrated server and the network channel on its
     * way out. That is the same context that deadlocked a world leave driven from the scheduled-task
     * drain — Minecraft holds the task queue's lock while draining it, and a mod handling
     * {@code ClientDisconnectionFromServerEvent} commonly wants that lock from a Netty thread.
     * Running from the client tick puts this where the vanilla Quit Game button runs, outside the
     * drain.
     */
    private static void scheduleShutdown() {
        Thread timer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(SHUTDOWN_DELAY_MILLIS);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    // Quitting is still what was asked for; a lost sleep is not a reason to stay up.
                }
                Mcmcp.LOGGER.info("MCMCP is ending the game at the request of client_quit.");
                ClientDeferredTasks.runNextTick(new Runnable() {
                    @Override
                    public void run() {
                        Minecraft.getMinecraft().shutdown();
                    }
                });
            }
        }, "MCMCP-quit");
        // Daemon so that if something else ends the game first, this thread is not what keeps the
        // JVM alive waiting to end a game that is already gone.
        timer.setDaemon(true);
        timer.start();
    }
}
