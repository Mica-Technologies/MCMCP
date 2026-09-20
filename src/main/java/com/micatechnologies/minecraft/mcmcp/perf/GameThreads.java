package com.micatechnologies.minecraft.mcmcp.perf;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds JVM threads by name, without naming a Minecraft class.
 *
 * <p>1.12.2 names its two game threads {@code Client thread} and {@code Server thread}, and those
 * names are the only handle on them that works from common code on both sides — and the only one a
 * profiler needs, since {@link ThreadMXBean} works in thread ids.
 */
public final class GameThreads {

    public static final String CLIENT = "Client thread";
    public static final String SERVER = "Server thread";

    private GameThreads() {
    }

    /**
     * The id of the thread called {@code name}, preferring an exact match over a case-insensitive
     * substring, or -1 if no live thread matches.
     */
    public static long find(String name) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        String needle = name.toLowerCase(Locale.ROOT);
        long partial = -1L;
        // Depth 0: names only. Asking for stacks here would stop every thread in the JVM to answer
        // a question about one of them.
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (info == null) {
                continue;
            }
            if (info.getThreadName().equals(name)) {
                return info.getThreadId();
            }
            if (partial < 0 && info.getThreadName().toLowerCase(Locale.ROOT).contains(needle)) {
                partial = info.getThreadId();
            }
        }
        return partial;
    }

    /** Names of every live thread, for the error that says which names would have worked. */
    public static List<String> names() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        List<String> names = new ArrayList<String>();
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (info != null) {
                names.add(info.getThreadName());
            }
        }
        return names;
    }
}
