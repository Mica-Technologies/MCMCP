package com.micatechnologies.minecraft.mcmcp.game;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * {@link GameThreadBridge} for the server thread.
 *
 * <p>Serves both the dedicated server and the integrated server behind a singleplayer world, which
 * is why it resolves the {@link MinecraftServer} through {@link FMLCommonHandler} on every call
 * rather than caching one: the integrated server is a fresh instance for every world the player
 * opens, and a cached reference would schedule work onto a dead server after the first world exit.
 */
public class ServerThreadBridge implements GameThreadBridge {

    @Override
    public McmcpSide getSide() {
        return McmcpSide.SERVER;
    }

    /**
     * The currently running server, or null.
     *
     * <p>Null is normal, not exceptional: on a client this is null at the main menu and between
     * worlds, and on a dedicated server it is null before startup finishes and after shutdown
     * begins.
     */
    @Nullable
    public static MinecraftServer server() {
        return FMLCommonHandler.instance().getMinecraftServerInstance();
    }

    @Override
    public boolean isAvailable() {
        MinecraftServer server = server();
        return server != null && server.isServerRunning();
    }

    @Override
    public boolean isGameThread() {
        MinecraftServer server = server();
        return server != null && server.isCallingFromMinecraftThread();
    }

    @Override
    public <T> T callOnGameThread(Callable<T> task, long timeoutMillis) throws Exception {
        // Already on the server thread — run inline. Scheduling here and then blocking on the
        // result would deadlock: the task cannot run until this thread returns to the tick loop.
        if (isGameThread()) {
            return task.call();
        }

        MinecraftServer server = server();
        if (server == null || !server.isServerRunning()) {
            throw new IllegalStateException("No Minecraft server is running");
        }

        // Our own future rather than the ListenableFuture addScheduledTask returns: that one
        // completes with the Runnable's (absent) value, and its exception plumbing differs between
        // 1.12.2 and the lwjgl3ify launches. This keeps the contract in one place.
        final CompletableFuture<T> future = new CompletableFuture<>();
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    future.complete(task.call());
                }
                catch (Throwable t) {
                    // Completing exceptionally rather than rethrowing is deliberate: an exception
                    // escaping a scheduled task propagates into the server tick loop and crashes
                    // the server. A misbehaving MCP tool must not be able to do that.
                    future.completeExceptionally(t);
                }
            }
        });

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            throw new TimeoutException("Server thread did not run the task within " + timeoutMillis + " ms");
        }
    }

    @Override
    public void runOnGameThread(final Runnable task) {
        MinecraftServer server = server();
        if (server == null || !server.isServerRunning()) {
            Mcmcp.LOGGER.warn("Dropped a scheduled MCMCP task: no server is running");
            return;
        }
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                }
                catch (Throwable t) {
                    Mcmcp.LOGGER.error("MCMCP task failed on the server thread", t);
                }
            }
        });
    }
}
