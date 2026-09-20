package com.micatechnologies.minecraft.mcmcp.perf;

import com.google.gson.JsonObject;
import java.util.Arrays;

/**
 * A fixed-size ring of timed events — server ticks, rendered frames — that can be summarised over
 * any trailing window.
 *
 * <p>Imports no Minecraft class, for the reason {@code protocol/} does not: the arithmetic that turns
 * samples into a percentile is exactly the part that can be wrong without anything failing, and it is
 * only unit-testable if it can be constructed without a game.
 *
 * <p>Every method takes the clock as an argument rather than reading {@link System#nanoTime}, so a
 * test can place samples at exact times.
 *
 * <p>Written by one game thread and read by HTTP workers, so everything is synchronised. The critical
 * sections are a couple of array writes on one side and a copy on the other; the sort happens outside
 * the lock.
 */
public final class DurationWindow {

    private final long[] endNanos;
    private final long[] durationNanos;
    private int next;
    private int size;

    public DurationWindow(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.endNanos = new long[capacity];
        this.durationNanos = new long[capacity];
    }

    /** Records one event that finished at {@code endedAtNanos} and took {@code tookNanos}. */
    public synchronized void record(long endedAtNanos, long tookNanos) {
        endNanos[next] = endedAtNanos;
        durationNanos[next] = tookNanos;
        next = (next + 1) % endNanos.length;
        if (size < endNanos.length) {
            size++;
        }
    }

    /** Summarises the events that finished within {@code windowNanos} before {@code nowNanos}. */
    public Summary summarise(long nowNanos, long windowNanos) {
        return summariseSince(nowNanos - windowNanos);
    }

    /** Summarises the events that finished at or after {@code sinceNanos}. */
    public Summary summariseSince(long sinceNanos) {
        long[] durations;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        int count = 0;
        synchronized (this) {
            durations = new long[size];
            // Newest first, so the walk can stop at the first sample that is too old: the ring is
            // written in time order, and everything behind that sample is older still.
            for (int i = 1; i <= size; i++) {
                int index = (next - i + endNanos.length) % endNanos.length;
                if (endNanos[index] - sinceNanos < 0) {
                    break;
                }
                durations[count++] = durationNanos[index];
                first = Math.min(first, endNanos[index]);
                last = Math.max(last, endNanos[index]);
            }
        }
        durations = Arrays.copyOf(durations, count);
        Arrays.sort(durations);
        return new Summary(durations, count == 0 ? 0L : last - first);
    }

    /** The distribution of one window's samples. Immutable. */
    public static final class Summary {

        private final long[] sorted;
        private final long spanNanos;

        Summary(long[] sorted, long spanNanos) {
            this.sorted = sorted;
            this.spanNanos = spanNanos;
        }

        public int count() {
            return sorted.length;
        }

        public double meanNanos() {
            if (sorted.length == 0) {
                return 0.0D;
            }
            double total = 0.0D;
            for (long sample : sorted) {
                total += sample;
            }
            return total / sorted.length;
        }

        /**
         * Nearest-rank percentile: the smallest sample with at least {@code percent} of the window at
         * or below it. Nearest-rank rather than interpolated because it always returns a duration
         * that actually happened, which is what someone chasing a hitch wants to see.
         */
        public long percentileNanos(double percent) {
            if (sorted.length == 0) {
                return 0L;
            }
            int rank = (int) Math.ceil(percent / 100.0D * sorted.length);
            return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
        }

        public long minNanos() {
            return sorted.length == 0 ? 0L : sorted[0];
        }

        public long maxNanos() {
            return sorted.length == 0 ? 0L : sorted[sorted.length - 1];
        }

        /** Mean duration of the slowest {@code percent} of samples — at least one sample. */
        public double slowestMeanNanos(double percent) {
            if (sorted.length == 0) {
                return 0.0D;
            }
            int take = Math.max(1, (int) Math.floor(percent / 100.0D * sorted.length));
            double total = 0.0D;
            for (int i = sorted.length - take; i < sorted.length; i++) {
                total += sorted[i];
            }
            return total / take;
        }

        public int countOver(long thresholdNanos) {
            int index = sorted.length;
            while (index > 0 && sorted[index - 1] > thresholdNanos) {
                index--;
            }
            return sorted.length - index;
        }

        /**
         * Events per second, from the spacing of the samples rather than from the window's nominal
         * length. A window that asks for five minutes on a server that has been up for ten seconds
         * would otherwise report a fraction of the true rate.
         */
        public double ratePerSecond() {
            if (sorted.length < 2 || spanNanos <= 0L) {
                return 0.0D;
            }
            return (sorted.length - 1) * 1.0e9D / spanNanos;
        }

        /** {@code samples}, {@code mean}, {@code min}, {@code median}, {@code p95}, {@code p99}, {@code max} — times in ms. */
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("samples", sorted.length);
            json.addProperty("meanMs", millis(meanNanos()));
            json.addProperty("minMs", millis(minNanos()));
            json.addProperty("medianMs", millis(percentileNanos(50.0D)));
            json.addProperty("p95Ms", millis(percentileNanos(95.0D)));
            json.addProperty("p99Ms", millis(percentileNanos(99.0D)));
            json.addProperty("maxMs", millis(maxNanos()));
            return json;
        }
    }

    /** Nanoseconds to milliseconds, rounded to two places: past that it is noise, and it is billed. */
    public static double millis(double nanos) {
        return Math.round(nanos / 10_000.0D) / 100.0D;
    }

    /** Nanoseconds to microseconds, rounded to one place. */
    public static double micros(double nanos) {
        return Math.round(nanos / 100.0D) / 10.0D;
    }
}
