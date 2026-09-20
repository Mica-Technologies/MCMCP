package com.micatechnologies.minecraft.mcmcp.perf;

/**
 * How many ticks (or frames) a game thread has finished, and how long the last one took.
 *
 * <p>This is the seam that lets the sampling profiler in common code ask "did a tick just end, and
 * was it a slow one" of either side without naming a client class: the server's tick recorder feeds
 * {@link #SERVER}, the client's frame recorder feeds {@link #CLIENT_FRAMES}, and the sampler reads
 * whichever matches its endpoint.
 *
 * <p>One writer — the game thread that owns the clock — and any number of readers. The duration is
 * written before the count, so a reader that sees the count move and then reads the duration gets
 * the duration of a tick at least as new as the one it noticed.
 */
public final class TickClock {

    public static final TickClock SERVER = new TickClock();

    /** The client's unit is the frame: that is what hitches, and what the client thread's time is spent on. */
    public static final TickClock CLIENT_FRAMES = new TickClock();

    private volatile long lastNanos;
    private volatile long count;

    /** Called by the owning game thread only. */
    public void completed(long tookNanos) {
        lastNanos = tookNanos;
        count++;
    }

    public long count() {
        return count;
    }

    public long lastNanos() {
        return lastNanos;
    }
}
