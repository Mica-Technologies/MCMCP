package com.micatechnologies.minecraft.mcmcp.perf;

/**
 * Decides whether a game thread has stopped making progress, from a counter it advances.
 *
 * <h2>Why a counter and not a timestamp</h2>
 *
 * The thing being watched is the thread that would have to write a timestamp, and a hung thread
 * writes nothing. A counter it advances once a frame is read from outside by a watchdog that
 * remembers when it last saw the number move — so the only clock that matters belongs to a thread
 * that is still running.
 *
 * <h2>What "stalled" can and cannot mean</h2>
 *
 * Silence past the threshold, nothing more. A deadlock and a sixty-second world load look identical
 * from here: both stop the counter. That is why nothing in this class is called "hung", and why the
 * places that report it also report the thread's stack, which is what tells the two apart.
 *
 * <p>It is not armed until the counter first moves, because a game still loading its mods has never
 * finished a frame and is not stalled — it has not started.
 *
 * <p>One writer, the watchdog calling {@link #observe}; any number of readers. The fields readers
 * see are volatile and each one is complete on its own, so a reader never needs a consistent
 * snapshot of more than one.
 */
public final class StallDetector {

    /** The client thread, advanced by frames and client ticks. Idle on a dedicated server. */
    public static final StallDetector CLIENT_THREAD = new StallDetector(GameThreads.CLIENT);

    /** What an observation changed. */
    public enum Transition {
        NONE,
        STALLED,
        RECOVERED
    }

    private final String threadName;

    private volatile long thresholdNanos = 15_000_000_000L;

    /** Watchdog only. */
    private long lastCount;

    private volatile boolean armed;

    private volatile long lastProgressNanos;
    private volatile boolean stalled;

    /**
     * Bumped on every transition, so a reader can tell "the state changed and changed back" from
     * "nothing happened" without being told about each change as it occurs.
     */
    private volatile long version;

    public StallDetector(String threadName) {
        this.threadName = threadName;
    }

    public String threadName() {
        return threadName;
    }

    public void setThresholdMillis(long millis) {
        thresholdNanos = Math.max(1L, millis) * 1_000_000L;
    }

    public long thresholdMillis() {
        return thresholdNanos / 1_000_000L;
    }

    /**
     * Records the thread's progress counter as of {@code nowNanos}. Watchdog thread only.
     *
     * @return the transition this observation caused, if any
     */
    public Transition observe(long count, long nowNanos) {
        if (!armed) {
            if (count == 0L) {
                return Transition.NONE;
            }
            lastCount = count;
            lastProgressNanos = nowNanos;
            armed = true;
            return Transition.NONE;
        }

        if (count != lastCount) {
            lastCount = count;
            lastProgressNanos = nowNanos;
            if (stalled) {
                stalled = false;
                version++;
                return Transition.RECOVERED;
            }
            return Transition.NONE;
        }

        if (!stalled && nowNanos - lastProgressNanos >= thresholdNanos) {
            stalled = true;
            version++;
            return Transition.STALLED;
        }
        return Transition.NONE;
    }

    /** Whether the counter has ever moved. Before it has, nothing here means anything. */
    public boolean isArmed() {
        return armed;
    }

    public boolean isStalled() {
        return stalled;
    }

    /** Milliseconds since the counter last moved, as of {@code nowNanos}; 0 before it is armed. */
    public long silentMillis(long nowNanos) {
        return armed ? Math.max(0L, (nowNanos - lastProgressNanos) / 1_000_000L) : 0L;
    }

    public long version() {
        return version;
    }

    /**
     * A sentence to append to an error from a tool that waited on this thread and gave up, or an
     * empty string when the thread is running normally and the wait was just a slow one.
     */
    public String describeIfStalled(long nowNanos) {
        if (!stalled) {
            return "";
        }
        return " The " + threadName.toLowerCase(java.util.Locale.ROOT) + " has not finished a frame in "
            + (silentMillis(nowNanos) / 1000L) + " s, so it is loading, blocked or hung rather than busy. "
            + "game_health shows its stack, which says which.";
    }
}
