package com.micatechnologies.minecraft.mcmcp.game;

import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/**
 * Marshals work from an MCP worker thread onto the game thread, and back.
 *
 * <p>This is the single most important boundary in the mod. Minecraft's world state — entities,
 * chunks, inventories, the player, the GUI — is owned by one thread per side and is guarded by
 * nothing at all. Touching it from an HTTP handler thread does not throw; it corrupts. The failure
 * mode is a {@code ConcurrentModificationException} minutes later in unrelated code, a chunk that
 * saves half-written, or a silently desynced client. So <em>every</em> tool that reads or writes
 * game state runs through here, and the MCP layer never holds a reference to a Minecraft object
 * across the boundary — only to the immutable snapshot a game-thread call returned.
 *
 * <p>Implementations are deliberately side-specific and client implementations must never be loaded
 * on a dedicated server: {@code ClientThreadBridge} references {@code Minecraft}, and merely
 * class-loading it server-side trips Forge's {@code SideTransformer} with "Attempted to load class
 * ... for invalid side SERVER". They are reached only through the proxy.
 */
public interface GameThreadBridge {

    /**
     * Whether the game is far enough along to accept work.
     *
     * <p>False during startup, while a client sits at the main menu with no world loaded, and after
     * a server has begun shutting down. Tools check this and return a tool-level error rather than
     * queueing work that will never run — a scheduled task on a stopped server never executes and
     * never completes its future, so the caller would block until its timeout for no reason.
     */
    boolean isAvailable();

    /** True when the calling thread is already the game thread, making a hand-off unnecessary. */
    boolean isGameThread();

    /**
     * Runs {@code task} on the game thread and waits for its value.
     *
     * <p>Blocks the calling thread — an MCP worker — for at most {@code timeoutMillis}. It must
     * never be called <em>from</em> the game thread with a hand-off pending, which is why
     * implementations short-circuit via {@link #isGameThread}: scheduling onto the thread you are
     * already on and then waiting for it is an instant deadlock.
     *
     * @throws java.util.concurrent.TimeoutException if the game thread did not run the task in time
     *                                               — usually a stalled tick loop or a shutdown that
     *                                               began between the availability check and here
     */
    <T> T callOnGameThread(Callable<T> task, long timeoutMillis) throws Exception;

    /**
     * As {@link #callOnGameThread(Callable, long)}, telling apart a task that never started from one
     * that did, where the bridge can (see {@link BridgeTask}).
     *
     * <p>The default is the plain call: a bridge that cannot tell the two apart never throws
     * {@link BridgeTask.NotStarted}, never polls {@code cancelled}, and never runs {@code ifOrphaned}.
     *
     * @param ifOrphaned run on the game thread with the value of a task that finished after its caller
     *                   stopped waiting, to give back what it took; null for nothing
     * @param cancelled  polled while waiting; a task not yet started when it turns true is dropped
     * @param caller     who the task runs for, so a bridge with a queue of its own can take turns
     *                   between callers; null for MCMCP's own
     * @throws BridgeTask.NotStarted if the time ran out before the task started; it never will
     */
    default <T> T callOnGameThread(Callable<T> task, long timeoutMillis,
                                   @Nullable java.util.function.Consumer<? super T> ifOrphaned,
                                   @Nullable java.util.function.BooleanSupplier cancelled,
                                   @Nullable TaskCaller caller) throws Exception {
        return callOnGameThread(task, timeoutMillis);
    }

    /**
     * Schedules {@code task} on the game thread without waiting.
     *
     * <p>For fire-and-forget side effects where the caller has nothing to report back. Exceptions
     * thrown by the task are logged, not propagated — there is nobody left to propagate them to,
     * and an escaping exception on the game thread crashes the game.
     */
    void runOnGameThread(Runnable task);

    /** Which endpoint this bridge belongs to; used for tool-availability filtering and log context. */
    McmcpSide getSide();
}
