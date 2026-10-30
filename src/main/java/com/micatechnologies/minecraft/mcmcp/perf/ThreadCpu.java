package com.micatechnologies.minecraft.mcmcp.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Which threads used the process's CPU over a window (#156).
 *
 * <p>{@code game_health} gave one process-wide figure and the game thread's share, and
 * {@code game_cpu_sample} needs a thread name already known. Finding that a server idling at 7% was
 * spending it in {@code spark-cpu-monitor} and {@code flare-monitoring-thread-1} took PowerShell
 * thread snapshots and a jstack from a separately installed JDK. Background mod threads are what a
 * tick profiler cannot see, so this is the first question in any idle-CPU investigation.
 *
 * <p>Imports no Minecraft class: fed CPU times by thread id, it ranks them.
 */
public final class ThreadCpu {

    /** One thread's use of the window. */
    public static final class Row {
        public final long id;
        public final String name;
        /** Percent of one core over the window. */
        public final double percent;

        Row(long id, String name, double percent) {
            this.id = id;
            this.name = name;
            this.percent = percent;
        }
    }

    private ThreadCpu() {
    }

    /**
     * Every thread alive at both ends, by CPU used, most first. A thread that started inside the
     * window counts from zero; one that ended is gone and is not listed.
     *
     * @param before   CPU nanoseconds by thread id at the start; a negative time is not known
     * @param after    the same at the end
     * @param names    thread names by id
     * @param wallNanos the window's length
     */
    public static List<Row> rank(Map<Long, Long> before, Map<Long, Long> after, Map<Long, String> names,
                                 long wallNanos) {
        if (wallNanos <= 0L) {
            return Collections.emptyList();
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Long, Long> end : after.entrySet()) {
            long used = end.getValue();
            if (used < 0L) {
                continue;
            }
            Long start = before.get(end.getKey());
            if (start != null) {
                if (start < 0L) {
                    continue;
                }
                used -= start;
            }
            String name = names.get(end.getKey());
            rows.add(new Row(end.getKey(), name == null ? "#" + end.getKey() : name,
                Math.max(0L, used) * 100.0D / wallNanos));
        }
        rows.sort((a, b) -> Double.compare(b.percent, a.percent));
        return rows;
    }

    /**
     * Where a thread is, as the first frame that is not the JDK's own: what owns it, which a
     * {@code Thread.sleep} or a socket read at the top does not say.
     */
    @Nullable
    public static String ownFrame(@Nullable StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return null;
        }
        for (StackTraceElement frame : stack) {
            String type = frame.getClassName();
            if (!type.startsWith("java.") && !type.startsWith("javax.") && !type.startsWith("sun.")
                && !type.startsWith("jdk.") && !type.startsWith("com.sun.")) {
                return type + "." + frame.getMethodName();
            }
        }
        return stack[0].getClassName() + "." + stack[0].getMethodName();
    }
}
