package com.micatechnologies.minecraft.mcmcp.mcp;

import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * What tools are doing on each side: when one was last called, and which are running now.
 *
 * <p>Something in client code needs to know an agent is driving — the client holds its frame rate
 * up while one is (see {@code ClientKeepAwake}), and shows what it is doing on screen (see
 * {@code ClientActivityBanner}) — and common code must not name a client type. So the dispatcher
 * records the facts here and the client reads them, rather than the dispatcher calling into the
 * client.
 */
public final class ToolActivity {

    /** A call in progress, and the last progress it reported. Fields are read across threads. */
    public static final class RunningCall {

        private final long id;
        private final McmcpSide side;
        private final String tool;
        private final long startedMillis;
        private volatile double progress = -1;
        private volatile double total = -1;
        @Nullable
        private volatile String message;

        RunningCall(long id, McmcpSide side, String tool, long startedMillis) {
            this.id = id;
            this.side = side;
            this.tool = tool;
            this.startedMillis = startedMillis;
        }

        public String getTool() {
            return tool;
        }

        public long getStartedMillis() {
            return startedMillis;
        }

        /** The last progress reported, or a negative number if none yet. */
        public double getProgress() {
            return progress;
        }

        /** The total that progress counts towards, or a negative number if unknown. */
        public double getTotal() {
            return total;
        }

        @Nullable
        public String getMessage() {
            return message;
        }
    }

    private static volatile long lastClientCallMillis;
    private static volatile long lastServerCallMillis;
    private static volatile long lastEndedMillis;

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<Long, RunningCall> RUNNING = new ConcurrentHashMap<>();

    private ToolActivity() {
    }

    public static void noteCall(McmcpSide side) {
        long now = System.currentTimeMillis();
        if (side.isClient()) {
            lastClientCallMillis = now;
        }
        else {
            lastServerCallMillis = now;
        }
    }

    /** {@link System#currentTimeMillis()} of the last tool call on {@code side}, or 0 if none yet. */
    public static long lastCallMillis(McmcpSide side) {
        return side.isClient() ? lastClientCallMillis : lastServerCallMillis;
    }

    /** Records a call starting. Pass the id to {@link #progress} and {@link #callEnded}. */
    public static long callStarted(McmcpSide side, String tool) {
        long id = NEXT_ID.incrementAndGet();
        RUNNING.put(id, new RunningCall(id, side, tool, System.currentTimeMillis()));
        return id;
    }

    /** Records progress on a running call. A call that has already ended is ignored. */
    public static void progress(long id, double progress, double total, @Nullable String message) {
        RunningCall call = RUNNING.get(id);
        if (call == null) {
            return;
        }
        call.progress = progress;
        call.total = total;
        call.message = message;
    }

    public static void callEnded(long id) {
        if (RUNNING.remove(id) != null) {
            lastEndedMillis = System.currentTimeMillis();
        }
    }

    /**
     * The most recently started call still running, on any side, or null. Any side because a
     * singleplayer world runs both in one process, and a person watching it does not care which half
     * a {@code server_set_blocks} came in on.
     */
    @Nullable
    public static RunningCall newestRunning() {
        RunningCall newest = null;
        for (RunningCall call : RUNNING.values()) {
            if (newest == null || call.id > newest.id) {
                newest = call;
            }
        }
        return newest;
    }

    /** How many calls are running, on every side. */
    public static int runningCount() {
        return RUNNING.size();
    }

    /** {@link System#currentTimeMillis()} when a call last ended, or 0 if none has. */
    public static long lastEndedMillis() {
        return lastEndedMillis;
    }
}
