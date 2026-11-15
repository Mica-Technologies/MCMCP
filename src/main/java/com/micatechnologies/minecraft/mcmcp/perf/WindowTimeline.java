package com.micatechnologies.minecraft.mcmcp.perf;

/**
 * When the client window was unfocused or throttled, as a timeline of changes, so a measurement
 * can say what share of its own window each held (#198).
 *
 * <p>"Throttled at some point" was not enough. A ten-second sample that lost focus for one second
 * and one that was in the background throughout both carried the same warning, and the agent had to
 * re-read the window between samples to know which to throw away. Fed every tick and frame by the
 * client, but it stores only changes, in a small ring: an hour of a window that never changes state
 * is one entry.
 *
 * <p>One writer (the client thread), any number of readers; reads are synchronized and cheap.
 */
public final class WindowTimeline {

    public static final WindowTimeline CLIENT = new WindowTimeline(512);

    private final long[] at;
    private final boolean[] unfocused;
    private final boolean[] throttled;
    private int count;
    private int head;

    public WindowTimeline(int capacity) {
        this.at = new long[capacity];
        this.unfocused = new boolean[capacity];
        this.throttled = new boolean[capacity];
    }

    /** Records the state now; nothing is stored unless it differs from the last one. */
    public synchronized void note(long nowNanos, boolean isUnfocused, boolean isThrottled) {
        if (count > 0) {
            int last = (head - 1 + at.length) % at.length;
            if (unfocused[last] == isUnfocused && throttled[last] == isThrottled) {
                return;
            }
        }
        at[head] = nowNanos;
        unfocused[head] = isUnfocused;
        throttled[head] = isThrottled;
        head = (head + 1) % at.length;
        count = Math.min(count + 1, at.length);
    }

    /** The share of {@code [sinceNanos, nowNanos]} the window was unfocused ({@code [0]}) and throttled ({@code [1]}). */
    public synchronized double[] fractions(long sinceNanos, long nowNanos) {
        double[] out = new double[2];
        long span = nowNanos - sinceNanos;
        if (span <= 0L || count == 0) {
            return out;
        }
        long unfocusedNanos = 0L;
        long throttledNanos = 0L;
        int oldest = (head - count + at.length) % at.length;
        for (int i = 0; i < count; i++) {
            int slot = (oldest + i) % at.length;
            long start = at[slot];
            long end = i + 1 < count ? at[(slot + 1) % at.length] : nowNanos;
            // Before the oldest entry kept, the state is unknown and counted as neither.
            long from = Math.max(start, sinceNanos);
            long to = Math.min(end, nowNanos);
            if (to <= from) {
                continue;
            }
            if (unfocused[slot]) {
                unfocusedNanos += to - from;
            }
            if (throttled[slot]) {
                throttledNanos += to - from;
            }
        }
        out[0] = round2((double) unfocusedNanos / span);
        out[1] = round2((double) throttledNanos / span);
        return out;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
