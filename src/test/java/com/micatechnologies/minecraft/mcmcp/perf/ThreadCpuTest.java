package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ranking threads by the CPU they used over a window (#156). */
class ThreadCpuTest {

    @Test
    void threadsAreRankedByCpuUsedInTheWindowNotSinceTheyStarted() {
        Map<Long, Long> before = new HashMap<>();
        Map<Long, Long> after = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        // The server thread has used far more in total, but not in this one-second window.
        before.put(1L, 900_000_000_000L);
        after.put(1L, 900_050_000_000L);
        names.put(1L, "Server thread");
        before.put(2L, 1_000_000_000L);
        after.put(2L, 1_070_000_000L);
        names.put(2L, "spark-cpu-monitor");
        // Started inside the window: counted from zero.
        after.put(3L, 10_000_000L);
        names.put(3L, "flare-monitoring-thread-1");

        List<ThreadCpu.Row> rows = ThreadCpu.rank(before, after, names, 1_000_000_000L);

        assertEquals("spark-cpu-monitor", rows.get(0).name);
        assertEquals(7.0D, rows.get(0).percent, 1e-9);
        assertEquals("Server thread", rows.get(1).name);
        assertEquals(1.0D, rows.get(2).percent, 1e-9);
    }

    @Test
    void aThreadWhoseTimeIsNotKnownIsLeftOut() {
        Map<Long, Long> before = new HashMap<>();
        Map<Long, Long> after = new HashMap<>();
        before.put(1L, -1L);
        after.put(1L, 5L);
        after.put(2L, -1L);

        assertEquals(0, ThreadCpu.rank(before, after, new HashMap<>(), 1_000L).size());
    }

    @Test
    void theOwnFrameSkipsTheJdksFrames() {
        StackTraceElement[] stack = {
            new StackTraceElement("sun.management.OperatingSystemImpl", "getProcessCpuLoad0", null, -1),
            new StackTraceElement("java.lang.Thread", "sleep", null, -1),
            new StackTraceElement("me.lucko.spark.monitor.CpuMonitor", "run", null, -1)};

        assertEquals("me.lucko.spark.monitor.CpuMonitor.run", ThreadCpu.ownFrame(stack));
    }
}
