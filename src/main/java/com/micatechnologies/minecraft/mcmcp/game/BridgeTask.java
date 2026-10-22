package com.micatechnologies.minecraft.mcmcp.game;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * One piece of work handed to a game thread, and the agreement between that thread and the caller
 * waiting for it about which of them gave up first.
 *
 * <h2>Why</h2>
 *
 * A task queued on the game thread stays queued after its caller times out, and it used to run
 * anyway, minutes later, for nobody (#132). The caller's agent was told "timed out" and sent the
 * same call again, so a server behind on its queue was handed the same work twice, then four times:
 * back-to-back multi-second ticks that never quite reached vanilla's watchdog, with every player
 * timing out. Worse, a task that took something — a chunk hold — took it for nobody, and nobody gave
 * it back.
 *
 * <h2>The states</h2>
 *
 * <pre>
 *   PENDING --run--> RUNNING --finished--> DONE
 *      |                 |
 *   caller gave up    caller gave up
 *      v                 v
 *   ABANDONED         ORPHANED --finished--> (cleanup)
 * </pre>
 *
 * Every move is a compare-and-set, so exactly one of the two threads wins each race:
 *
 * <ul>
 *   <li>A task the caller abandoned before it started does nothing when the game thread reaches it,
 *       and the caller knows for certain that nothing ran: {@link NotStarted}, which is safe to retry.</li>
 *   <li>A task already running when the caller gave up runs to the end, since nothing can stop it
 *       halfway; its caller is told it started and that what it did is not known
 *       ({@link StartedNotFinished}). Its result goes to the cleanup the caller left, which can give
 *       back what the task took.</li>
 * </ul>
 *
 * <p>Pure: no Minecraft class, so the state machine is unit-tested as plain Java. Whatever drains the
 * game thread's queue — vanilla's scheduled tasks today, MCMCP's own budgeted pump later — only has to
 * call {@link #run()}; an abandoned task costs it nothing.
 */
public final class BridgeTask<T> implements Runnable {

    public enum State {
        PENDING, RUNNING, DONE, ABANDONED, ORPHANED
    }

    /** The caller stopped waiting before the game thread started the task, so it never ran. */
    public static final class NotStarted extends Exception {

        private static final long serialVersionUID = 1L;

        private final long waitedMillis;

        NotStarted(long waitedMillis) {
            super("The game thread did not start this call's work within " + waitedMillis + " ms, so it was "
                + "dropped before it ran: nothing changed.");
            this.waitedMillis = waitedMillis;
        }

        public long waitedMillis() {
            return waitedMillis;
        }
    }

    /** The caller stopped waiting while the task was running: what it did is not known. */
    public static final class StartedNotFinished extends TimeoutException {

        private static final long serialVersionUID = 1L;

        StartedNotFinished(long timeoutMillis, long ranForMillis) {
            super("The game thread started this call's work but had not finished it after " + timeoutMillis
                + " ms (it had been running for " + ranForMillis + " ms). It may still finish, so what it "
                + "changed is not known: look before you retry.");
        }
    }

    /** How often a caller that can be cancelled looks at its cancellation while it waits. */
    private static final long CANCEL_POLL_NANOS = 100_000_000L;

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final Callable<T> task;
    @Nullable
    private final Consumer<? super T> ifOrphaned;
    private final CompletableFuture<T> result = new CompletableFuture<>();
    private volatile long startedNanos;

    /**
     * @param ifOrphaned run on the game thread with the task's value when the task finished after its
     *                   caller stopped waiting: where to give back what nobody is going to use. Null for
     *                   nothing. Never run for a task that threw, or one that never started.
     */
    public BridgeTask(Callable<T> task, @Nullable Consumer<? super T> ifOrphaned) {
        this.task = task;
        this.ifOrphaned = ifOrphaned;
    }

    public State state() {
        return state.get();
    }

    /**
     * Game thread: runs the task unless its caller has already given up on it. Never throws, because an
     * exception escaping a scheduled task reaches the tick loop and crashes the game.
     */
    @Override
    public void run() {
        if (!state.compareAndSet(State.PENDING, State.RUNNING)) {
            return;
        }
        startedNanos = System.nanoTime();
        T value;
        try {
            value = task.call();
        }
        catch (Throwable t) {
            state.compareAndSet(State.RUNNING, State.DONE);
            result.completeExceptionally(t);
            return;
        }
        if (state.compareAndSet(State.RUNNING, State.DONE)) {
            result.complete(value);
            return;
        }
        // Orphaned: the caller left while this ran. Nobody will read the value; the cleanup may need it.
        result.complete(value);
        if (ifOrphaned != null) {
            try {
                ifOrphaned.accept(value);
            }
            catch (Throwable ignored) {
                // The cleanup's own business; it must not take the game thread down with it.
            }
        }
    }

    /**
     * Caller's thread: waits up to {@code timeoutMillis} for the value.
     *
     * @param cancelled polled while waiting; when it turns true before the task started, the task is
     *                  abandoned and this throws {@link CancellationException}. Once the task has
     *                  started a cancellation waits out the timeout as before, since the task cannot be
     *                  stopped halfway. Null when the wait cannot be cancelled.
     * @throws NotStarted                if the time ran out before the task started; it never will
     * @throws StartedNotFinished        if the time ran out while it was running
     * @throws CancellationException     if cancelled before it started
     * @throws java.util.concurrent.ExecutionException if the task threw
     */
    public T await(long timeoutMillis, @Nullable BooleanSupplier cancelled) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        while (true) {
            long left = deadline - System.nanoTime();
            long wait = cancelled == null ? left : Math.min(left, CANCEL_POLL_NANOS);
            try {
                return result.get(Math.max(0L, wait), TimeUnit.NANOSECONDS);
            }
            catch (TimeoutException waiting) {
                boolean cancel = cancelled != null && cancelled.getAsBoolean();
                boolean expired = deadline - System.nanoTime() <= 0L;
                if (!cancel && !expired) {
                    continue;
                }
                if (state.compareAndSet(State.PENDING, State.ABANDONED)) {
                    if (cancel && !expired) {
                        throw new CancellationException("The call was cancelled before the game thread started "
                            + "its work: nothing ran.");
                    }
                    throw new NotStarted(timeoutMillis);
                }
                if (!expired) {
                    continue;
                }
                if (state.compareAndSet(State.RUNNING, State.ORPHANED)) {
                    throw new StartedNotFinished(timeoutMillis,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
                }
                // It finished between the timeout and here: the value is there.
                return result.get();
            }
        }
    }
}
