package com.micatechnologies.minecraft.mcmcp.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

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

    /**
     * The thread's state, whole stack and lock details, or null if no live thread has that id.
     *
     * <p>Asks for one thread only. Lock owners are included because the question a stuck thread
     * raises is almost always "waiting on whom".
     */
    @Nullable
    public static ThreadInfo snapshot(long threadId) {
        if (threadId < 0L) {
            return null;
        }
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = threads.getThreadInfo(new long[] { threadId }, threads.isObjectMonitorUsageSupported(),
            threads.isSynchronizerUsageSupported());
        return infos.length == 0 ? null : infos[0];
    }

    /**
     * A thread's state, what it is waiting on, and its top frames, for a tool result.
     *
     * <p>Frames are capped because this lands in a model's context: the top dozen say where a thread
     * is stuck, and the fifty below them are the same game loop every time.
     */
    public static JsonObject toJson(ThreadInfo info, int maxFrames) {
        JsonObject json = new JsonObject();
        json.addProperty("thread", info.getThreadName());
        json.addProperty("state", info.getThreadState().name());
        LockInfo lock = info.getLockInfo();
        if (lock != null) {
            json.addProperty("waitingOn", lock.toString());
            if (info.getLockOwnerName() != null) {
                json.addProperty("heldBy", info.getLockOwnerName());
            }
        }
        JsonArray stack = new JsonArray();
        StackTraceElement[] frames = info.getStackTrace();
        for (int i = 0; i < frames.length && i < maxFrames; i++) {
            stack.add(frames[i].toString());
        }
        json.add("stack", stack);
        return json;
    }

    /**
     * A {@code jstack}-style dump of one thread, whole, for the log.
     *
     * <p>Not {@link ThreadInfo#toString()}, which silently stops at eight frames — above anything
     * that would say why.
     */
    public static String dump(ThreadInfo info) {
        StringBuilder out = new StringBuilder();
        out.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
        if (info.getLockName() != null) {
            out.append(" on ").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            out.append(" owned by \"").append(info.getLockOwnerName()).append('"');
        }
        out.append('\n');
        for (StackTraceElement frame : info.getStackTrace()) {
            out.append("\tat ").append(frame).append('\n');
        }
        return out.toString();
    }

    /** The names of any threads deadlocked on monitors or synchronizers, empty when there are none. */
    public static List<String> deadlocked() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        List<String> names = new ArrayList<String>();
        long[] ids = threads.isSynchronizerUsageSupported()
            ? threads.findDeadlockedThreads()
            : threads.findMonitorDeadlockedThreads();
        if (ids == null) {
            return names;
        }
        for (ThreadInfo info : threads.getThreadInfo(ids, 0)) {
            if (info != null) {
                names.add(info.getThreadName());
            }
        }
        return names;
    }
}
