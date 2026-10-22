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

    /** How many recent ticks {@link #durationOf} can still answer for: about thirteen seconds at 20 TPS. */
    static final int RECENT = 256;

    private volatile long lastNanos;
    private volatile long count;
    private volatile long throttledAtNanos = Long.MIN_VALUE;
    /** Each tick's duration by its index modulo {@link #RECENT}, written before the count publishes it. */
    private final long[] recent = new long[RECENT];
    /** When each tick finished, by {@link System#nanoTime()}, kept beside its duration. */
    private final long[] finishedAt = new long[RECENT];

    /**
     * Called by the owning game thread whenever it finds itself throttled: a client in the background
     * held to a few frames a second. A profile taken then measures the throttle, not the game (#102).
     */
    public void throttled(long nowNanos) {
        throttledAtNanos = nowNanos;
    }

    /** Whether the thread was seen throttled at or after {@code sinceNanos}. */
    public boolean throttledSince(long sinceNanos) {
        return throttledAtNanos != Long.MIN_VALUE && throttledAtNanos - sinceNanos >= 0L;
    }

    /** Called by the owning game thread only. */
    public void completed(long tookNanos) {
        completed(tookNanos, System.nanoTime());
    }

    /** Called by the owning game thread only, with when the tick finished. */
    public void completed(long tookNanos, long finishedAtNanos) {
        lastNanos = tookNanos;
        int slot = (int) (count % RECENT);
        recent[slot] = tookNanos;
        finishedAt[slot] = finishedAtNanos;
        count++;
    }

    /**
     * Ticks finished a second, by the wall clock, from {@code sinceNanos} to {@code nowNanos}: the
     * real TPS. Unlike a mean of tick durations, it sees the time <em>between</em> ticks, so a server
     * thread that stops for seconds at a time between quick ticks reads as slow. Frozen 2.5 s in every
     * 3 by a debugger, a server's mean tick read 0.2 ms, 20 TPS, while it finished a few ticks a second.
     *
     * <p>When more than {@link #RECENT} ticks finished in the span, the rate is taken over the ones
     * kept. Read it on the owning thread, as the slots are written there.
     *
     * @return the rate, or -1 when the span is not positive
     */
    public double ticksPerSecond(long nowNanos, long sinceNanos) {
        long span = nowNanos - sinceNanos;
        if (span <= 0L) {
            return -1.0D;
        }
        long completed = count;
        int n = 0;
        long oldest = nowNanos;
        for (long index = completed - 1L; index >= 0L && completed - index <= RECENT; index--) {
            long at = finishedAt[(int) (index % RECENT)];
            if (at - sinceNanos <= 0L) {
                return n * 1.0e9D / span;
            }
            if (at - nowNanos > 0L) {
                // Finished after the span asked about.
                continue;
            }
            n++;
            oldest = at;
        }
        if (completed <= RECENT) {
            // Every tick there has been finished in the span.
            return n * 1.0e9D / span;
        }
        // The ring ran out inside the span: the rate over the ticks it still holds.
        return nowNanos - oldest <= 0L ? -1.0D : (n - 1) * 1.0e9D / (nowNanos - oldest);
    }

    /**
     * How long tick {@code index} took, counting from zero, so the tick running now is number
     * {@link #count()}: a caller that read the count during a tick asks for that number once the
     * count has moved past it. -1 when that tick has not finished yet, or is too old to be kept.
     */
    public long durationOf(long index) {
        long completed = count;
        if (index < 0L || index >= completed || completed - index > RECENT) {
            return -1L;
        }
        long took = recent[(int) (index % RECENT)];
        // The writer may have lapped the slot while it was being read.
        return count - index > RECENT ? -1L : took;
    }

    public long count() {
        return count;
    }

    public long lastNanos() {
        return lastNanos;
    }
}
