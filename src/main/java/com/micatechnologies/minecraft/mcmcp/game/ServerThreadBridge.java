package com.micatechnologies.minecraft.mcmcp.game;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
 *
 * <p>Every call goes through a {@link BridgeTask}: a task whose caller timed out or was cancelled
 * before the server thread reached it is skipped when it is reached, instead of running for nobody
 * on a server that was already too far behind to run it in time (#132). Tasks go through
 * {@link ServerTaskPump}, not {@code addScheduledTask}: offered without a lock, run at the end of each
 * tick within a budget, callers taking turns (#133).
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
        return callOnGameThread(task, timeoutMillis, null, null, null);
    }

    @Override
    public <T> T callOnGameThread(Callable<T> task, long timeoutMillis, @Nullable Consumer<? super T> ifOrphaned,
                                  @Nullable BooleanSupplier cancelled, @Nullable TaskCaller caller) throws Exception {
        // Already on the server thread — run inline. Scheduling here and then blocking on the
        // result would deadlock: the task cannot run until this thread returns to the tick loop.
        if (isGameThread()) {
            return task.call();
        }

        MinecraftServer server = server();
        if (server == null || !server.isServerRunning()) {
            throw new IllegalStateException("No Minecraft server is running");
        }

        // BridgeTask completes exceptionally rather than rethrowing, because an exception escaping
        // onto the server thread propagates into the tick loop and crashes the server. A misbehaving
        // MCP tool must not be able to do that.
        BridgeTask<T> bridged = new BridgeTask<>(task, ifOrphaned);
        ServerTaskPump.offer(caller == null ? TaskCaller.MCMCP : caller, bridged);
        return bridged.await(timeoutMillis, cancelled);
    }

    @Override
    public void runOnGameThread(final Runnable task) {
        MinecraftServer server = server();
        if (server == null || !server.isServerRunning()) {
            Mcmcp.LOGGER.warn("Dropped a scheduled MCMCP task: no server is running");
            return;
        }
        ServerTaskPump.runLater(TaskCaller.MCMCP, task);
    }
}
