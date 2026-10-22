package com.micatechnologies.minecraft.mcmcp.perf;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.function.LongSupplier;

/**
 * Samples one thread's stack only while a window the thread itself opens is open: the stack sample
 * behind {@code server_run_command}'s {@code profile}.
 *
 * <p>{@code game_cpu_sample} cannot profile a command, because it blocks for its whole window and one
 * agent's calls run one at a time, so the sample has finished before the command starts. This runs on
 * its own thread for the length of the call instead, and the game thread brackets the commands with
 * {@link #begin} and {@link #end}. Everything outside those brackets (scheduling, chunk loads done
 * before the commands, the rest of the tick) is never sampled, so the tree is the commands' and
 * nothing else's.
 *
 * <p>A sample is kept only if the same window was open both before and after the stack was taken.
 * {@link ThreadMXBean#getThreadInfo} stops the thread at a safepoint somewhere between those two
 * reads, so a sample straddling an {@link #end} is dropped rather than credited to the command.
 *
 * <p>With {@code wholeTick}, a window stays open after {@link #end} until the tick it began in
 * finishes, as read from {@code completedTicks}: the work a command causes later in the same tick
 * (a mod's tick handler, a tile entity reacting to a block it placed) is in the tree too.
 *
 * <p>Imports no Minecraft class. {@link #begin} and {@link #end} belong to the sampled thread; the
 * tree belongs to the sampler until {@link #stop} has joined it.
 */
public final class CommandSampler {

    /** A window: which tick it began in. Compared by identity. */
    private static final class Window {
        final long tick;

        Window(long tick) {
            this.tick = tick;
        }
    }

    private final long threadId;
    private final int intervalMillis;
    private final boolean wholeTick;
    private final LongSupplier completedTicks;
    private final CallTree tree = new CallTree();

    private volatile Window window;
    private volatile boolean running;
    private Thread sampler;

    /**
     * @param threadId       the thread to sample, as {@link GameThreads#find} returns it
     * @param completedTicks how many ticks that thread has finished; only read with {@code wholeTick}
     */
    public CommandSampler(long threadId, int intervalMillis, boolean wholeTick, LongSupplier completedTicks) {
        this.threadId = threadId;
        this.intervalMillis = Math.max(1, intervalMillis);
        this.wholeTick = wholeTick;
        this.completedTicks = completedTicks;
    }

    public void start() {
        running = true;
        sampler = new Thread(this::loop, "MCMCP command sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    /** Opens a window. Called by the sampled thread, before the commands. */
    public void begin() {
        window = new Window(completedTicks.getAsLong());
    }

    /** Closes the window, or with {@code wholeTick} leaves it for the tick's end to close. */
    public void end() {
        if (!wholeTick) {
            window = null;
        }
    }

    /** Stops the sampler and waits for it, after which {@link #tree} is safe to read. */
    public CallTree stop() throws InterruptedException {
        running = false;
        if (sampler != null) {
            sampler.join(5_000L);
        }
        return tree;
    }

    public CallTree tree() {
        return tree;
    }

    private boolean open(Window w) {
        return w != null && (!wholeTick || completedTicks.getAsLong() == w.tick);
    }

    private void loop() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        try {
            while (running) {
                Window before = window;
                if (open(before)) {
                    ThreadInfo info = bean.getThreadInfo(threadId, Integer.MAX_VALUE);
                    if (info == null) {
                        return;
                    }
                    if (window == before && open(before)) {
                        // Idle frames are kept: a command parked on a lock or on disk is time that
                        // command took, which is the question being asked.
                        tree.add(info.getStackTrace(), false);
                    }
                    Thread.sleep(intervalMillis);
                } else {
                    // Closed: poll quickly, so a short command's first sample is not an interval late.
                    Thread.sleep(1L);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // A sampler that fails leaves an emptier tree, never a failed command.
        }
    }
}
