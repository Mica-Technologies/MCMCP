package com.micatechnologies.minecraft.mcmcp.perf;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Remembers when recent garbage collections happened, so that a slow tick or frame can be checked
 * against them.
 *
 * <p>The collector totals in {@code game_health} say how much time GC has taken; they cannot say
 * <em>when</em>, and when is the whole question. "Seven ticks over 50 ms in the last minute" reads as
 * a block to go hunting for; "seven ticks over 50 ms, six of them during a collection" reads as a heap
 * setting. This is what turns the first sentence into the second.
 *
 * <p>The JVM announces each collection through a JMX notification carrying its start and end as
 * milliseconds since JVM start. Those are converted onto the {@link System#nanoTime} axis once, at
 * install, because that is the axis every tick and frame sample is on.
 *
 * <p>The notification type is a HotSpot extension. Where it is missing, {@link #install} does
 * nothing, the log stays empty, and the tools that read it report no overlap rather than failing.
 *
 * <p>Imports no Minecraft class.
 */
public final class GcPauseLog {

    private static final int CAPACITY = 256;

    /** One collection. Times are on the {@link System#nanoTime} axis. */
    public static final class Pause {
        public final long startNanos;
        public final long endNanos;
        public final String collector;
        public final String action;

        Pause(long startNanos, long endNanos, String collector, String action) {
            this.startNanos = startNanos;
            this.endNanos = endNanos;
            this.collector = collector;
            this.action = action;
        }
    }

    private static final Pause[] RING = new Pause[CAPACITY];
    private static int next;
    private static boolean installed;

    private GcPauseLog() {
    }

    /** Starts listening. Safe to call from both sides' registration; only the first call acts. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            // nanoTime at the moment the JVM's own clock read zero.
            long nowNanos = System.nanoTime();
            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            final long jvmStartNanos = nowNanos - uptimeMillis * 1_000_000L;

            NotificationListener listener = new NotificationListener() {
                @Override
                public void handleNotification(Notification notification, Object handback) {
                    try {
                        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                            .equals(notification.getType())) {
                            return;
                        }
                        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo
                            .from((CompositeData) notification.getUserData());
                        record(new Pause(
                            jvmStartNanos + info.getGcInfo().getStartTime() * 1_000_000L,
                            jvmStartNanos + info.getGcInfo().getEndTime() * 1_000_000L,
                            info.getGcName(), info.getGcAction()));
                    } catch (RuntimeException | LinkageError e) {
                        // A listener that throws is removed by some JMX implementations and logged
                        // by others. Neither helps; one missed collection is the better outcome.
                    }
                }
            };
            for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (collector instanceof NotificationEmitter) {
                    ((NotificationEmitter) collector).addNotificationListener(listener, null, null);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // Not HotSpot. The log stays empty.
        }
    }

    static synchronized void record(Pause pause) {
        RING[next] = pause;
        next = (next + 1) % CAPACITY;
    }

    /** Collections that ended at or after {@code sinceNanos}, oldest first. */
    public static synchronized List<Pause> since(long sinceNanos) {
        List<Pause> pauses = new ArrayList<Pause>();
        for (int i = 0; i < CAPACITY; i++) {
            Pause pause = RING[(next + i) % CAPACITY];
            if (pause != null && pause.endNanos - sinceNanos >= 0L) {
                pauses.add(pause);
            }
        }
        return pauses;
    }

    /** The same collections as {@code [start, end]} pairs, for {@link DurationWindow#countOverlapping}. */
    public static List<long[]> intervalsSince(long sinceNanos) {
        List<long[]> intervals = new ArrayList<long[]>();
        for (Pause pause : since(sinceNanos)) {
            intervals.add(new long[] {pause.startNanos, pause.endNanos});
        }
        return intervals;
    }
}
