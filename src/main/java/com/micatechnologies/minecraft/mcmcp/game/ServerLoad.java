package com.micatechnologies.minecraft.mcmcp.game;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

/**
 * How loaded this process's server is, as MCMCP sees it: what the dispatcher, the bridge and jobs
 * read to decide whether to add work.
 *
 * <p>Two sources feed it. The chunk-load governor measures tick time and heap every second on the
 * server thread and reports {@link Level#OK}, {@link Level#THROTTLED} or {@link Level#PAUSED}. The
 * server watchdog, on a thread of its own, reports {@link Level#STALLED} when the server thread has
 * finished no tick for a while — the one condition the governor cannot see, because it measures in
 * ticks and a stalled server has none. A stall outranks any measurement.
 *
 * <p>The real TPS is ticks finished a second by the wall clock over the last five seconds
 * ({@link com.micatechnologies.minecraft.mcmcp.perf.TickClock#ticksPerSecond}). The mean tick time
 * leaves out the time between ticks, so a server thread stopped for 2.5 s in every 3 read 0.2 ms and
 * 20 TPS by it. An integrated server's is not used, since it stops ticking whenever its player pauses.
 *
 * <p>The levels are measured against the {@code health} thresholds, not chunk loading's own, which
 * are tighter: chunk loads slow at 40 ms and stop at 50, but a server at 45 ms is still at 20 TPS and
 * its agents' writes are not what to refuse.
 *
 * <ul>
 *   <li>{@link Level#THROTTLED}: mean tick over {@code health.holdJobsMspt} (60), or on a dedicated
 *       server a real TPS below {@code health.holdJobsBelowTps} (15). Jobs wait.</li>
 *   <li>{@link Level#PAUSED}: mean tick over {@code health.refuseWritesMspt} (100), real TPS below
 *       {@code health.refuseWritesBelowTps} (8), or heap over {@code chunks.pauseHeapPercent}. Calls
 *       that change the world are refused, and jobs wait.</li>
 *   <li>{@link Level#STALLED}: no tick for {@code health.stallDumpSeconds}. As paused.</li>
 * </ul>
 *
 * <p>No Minecraft class: state and listeners only, so it can be read from {@code protocol/} and
 * {@code mcp/} and tested as plain Java.
 */
public final class ServerLoad {

    public enum Level {
        OK, THROTTLED, PAUSED, STALLED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Told when the level changes, on whichever thread changed it. Must not block. */
    public interface Listener {
        void changed(Level now, Level before);
    }

    private static volatile Level measured = Level.OK;
    private static volatile double meanMspt;
    /** Ticks finished a second by the wall clock, or negative when not known. */
    private static volatile double realTps = -1.0D;
    private static volatile double heapPercent;
    private static volatile long recoveryMillis;
    private static volatile boolean stalled;
    private static volatile long stalledForMillis;
    @Nullable
    private static volatile String lastStallDump;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ServerLoad() {
    }

    /** The level a tick-time state names: the same three, read against the health thresholds. */
    public static Level fromHealth(com.micatechnologies.minecraft.mcmcp.chunkload.ServerHealth.State state) {
        switch (state) {
            case PAUSED:
                return Level.PAUSED;
            case THROTTLED:
                return Level.THROTTLED;
            default:
                return Level.OK;
        }
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** The level now: a stall if there is one, else the last measurement. */
    public static Level level() {
        return stalled ? Level.STALLED : measured;
    }

    /** Whether calls that change the world are refused, and jobs wait: paused or stalled. */
    public static boolean refusesWrites() {
        Level level = level();
        return level == Level.PAUSED || level == Level.STALLED;
    }

    /** Whether background jobs hold back: anything but OK. */
    public static boolean holdsJobs() {
        return level() != Level.OK;
    }

    /**
     * A measurement from the server thread.
     *
     * @param untilRecoveryMillis the earliest the measured level could next improve
     */
    public static void measured(Level level, double mspt, double heap, long untilRecoveryMillis) {
        measured(level, mspt, heap, -1.0D, untilRecoveryMillis);
    }

    /**
     * A measurement from the server thread, with the real TPS.
     *
     * @param tps                 ticks finished a second by the wall clock, or negative when not known
     * @param untilRecoveryMillis the earliest the measured level could next improve
     */
    public static synchronized void measured(Level level, double mspt, double heap, double tps,
                                             long untilRecoveryMillis) {
        Level before = level();
        measured = level == Level.STALLED ? Level.PAUSED : level;
        meanMspt = mspt;
        realTps = tps;
        heapPercent = heap;
        recoveryMillis = untilRecoveryMillis;
        fire(before);
    }

    /** From the watchdog: whether the server thread is stalled, and for how long. */
    public static synchronized void stalled(boolean value, long forMillis) {
        Level before = level();
        stalled = value;
        stalledForMillis = value ? forMillis : 0L;
        fire(before);
    }

    /** From the watchdog: where the current stall's stacks were written. */
    public static void stallDumped(@Nullable String path) {
        lastStallDump = path;
    }

    /** Forgets everything: the server stopped, and the next one starts at OK. Tells nobody. */
    public static synchronized void reset() {
        measured = Level.OK;
        stalled = false;
        stalledForMillis = 0L;
        meanMspt = 0.0D;
        realTps = -1.0D;
        heapPercent = 0.0D;
        recoveryMillis = 0L;
    }

    public static double meanMspt() {
        return meanMspt;
    }

    /** Ticks finished a second over the last few seconds, by the wall clock, or negative when not known. */
    public static double realTps() {
        return realTps;
    }

    /** The TPS to tell people: the real one when it is known, else what the mean tick time allows. */
    public static double reportedTps() {
        double real = realTps;
        return real >= 0.0D ? Math.min(20.0D, real) : tps(meanMspt);
    }

    public static double heapPercent() {
        return heapPercent;
    }

    public static long stalledForMillis() {
        return stalledForMillis;
    }

    @Nullable
    public static String lastStallDump() {
        return lastStallDump;
    }

    /** How far back the real TPS looks. */
    public static final long REAL_TPS_WINDOW_NANOS = 5_000_000_000L;

    /** The shortest span the real TPS is taken over: less, just after a server starts, is not known yet. */
    static final long REAL_TPS_MIN_SPAN_NANOS = 1_000_000_000L;

    /**
     * The real TPS: ticks {@code clock} finished a second over the last {@link #REAL_TPS_WINDOW_NANOS},
     * or since {@code startedNanos} when the server started more recently than that.
     *
     * @return the rate, or -1 when the server has not been running for {@link #REAL_TPS_MIN_SPAN_NANOS}
     */
    public static double realTps(com.micatechnologies.minecraft.mcmcp.perf.TickClock clock, long nowNanos,
                                 long startedNanos) {
        long since = nowNanos - REAL_TPS_WINDOW_NANOS;
        if (since - startedNanos < 0L) {
            since = startedNanos;
        }
        if (nowNanos - since < REAL_TPS_MIN_SPAN_NANOS) {
            return -1.0D;
        }
        return clock.ticksPerSecond(nowNanos, since);
    }

    /** Ticks a second the mean tick time allows, at most 20. */
    public static double tps(double mspt) {
        return mspt <= 50.0D ? 20.0D : 1000.0D / mspt;
    }

    /** How long a refused caller should wait before trying again: measured where it can be. */
    public static long retryAfterMillis() {
        switch (level()) {
            case STALLED:
                return 15_000L;
            case PAUSED:
                return Math.max(5_000L, Math.min(30_000L, recoveryMillis));
            default:
                return 2_000L;
        }
    }

    /** The level and its numbers in a sentence: "paused (mean tick 87.0 ms, 11.5 TPS, heap 72%)". */
    public static String describe() {
        Level level = level();
        if (level == Level.STALLED) {
            return "stalled: the server thread has not finished a tick in " + stalledForMillis / 1000L + " s";
        }
        return level.id() + " (mean tick " + round1(meanMspt) + " ms, " + round1(reportedTps()) + " TPS, heap "
            + Math.round(heapPercent) + "%)";
    }

    /** The structured {@code busy} block a refused call carries. */
    public static JsonObject busy(String reason) {
        JsonObject json = new JsonObject();
        json.addProperty("reason", reason);
        json.addProperty("health", level().id());
        json.addProperty("mspt", round1(meanMspt));
        if (realTps >= 0.0D) {
            json.addProperty("realTps", round1(realTps));
        }
        json.addProperty("heapPercent", Math.round(heapPercent));
        if (stalled) {
            json.addProperty("stalledSeconds", stalledForMillis / 1000L);
        }
        json.addProperty("retryAfterMs", retryAfterMillis());
        return json;
    }

    /** For {@code game_health} and {@code /mcmcp chunks}. */
    public static JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("level", level().id());
        json.addProperty("meanTickMs", round1(meanMspt));
        json.addProperty("tps", round1(tps(meanMspt)));
        if (realTps >= 0.0D) {
            json.addProperty("realTps", round1(realTps));
        }
        json.addProperty("heapPercentAfterGc", Math.round(heapPercent));
        json.addProperty("refusesWrites", refusesWrites());
        if (stalled) {
            json.addProperty("stalledSeconds", stalledForMillis / 1000L);
        }
        String dump = lastStallDump;
        if (dump != null) {
            json.addProperty("lastStallDump", dump);
        }
        return json;
    }

    static double round1(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    private static void fire(Level before) {
        Level now = level();
        if (now == before) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.changed(now, before);
            }
            catch (RuntimeException e) {
                // A listener must never stop the state changing.
            }
        }
    }
}
