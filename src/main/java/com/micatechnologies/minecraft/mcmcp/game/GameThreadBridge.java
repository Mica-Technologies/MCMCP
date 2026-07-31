package com.micatechnologies.minecraft.mcmcp.game;

import java.util.concurrent.Callable;

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
