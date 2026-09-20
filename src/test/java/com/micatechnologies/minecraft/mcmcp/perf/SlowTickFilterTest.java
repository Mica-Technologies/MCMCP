package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The filter that lets {@code game_cpu_sample} profile only the ticks that hitched. */
class SlowTickFilterTest {

    private static final long MILLI = 1_000_000L;
    private static final long THRESHOLD = 50 * MILLI;

    private static StackTraceElement[] stack(String method) {
        return new StackTraceElement[] {new StackTraceElement("a.b.C", method, null, 1)};
    }

    @Test
    void samplesFromAFastTickNeverReachTheTree() {
        CallTree tree = new CallTree();
        SlowTickFilter filter = new SlowTickFilter(tree, THRESHOLD, true);

        filter.sample(stack("fast"), 10, 5 * MILLI);
        filter.sample(stack("fast"), 10, 5 * MILLI);
        // Tick 11 has now finished, and took 8 ms.
        filter.sample(stack("next"), 11, 8 * MILLI);

        assertEquals(0L, tree.samples());
        assertEquals(1L, filter.ticksSeen());
        assertEquals(0L, filter.ticksKept());
    }

    @Test
    void samplesFromASlowTickAreKeptOnceItsDurationIsKnown() {
        CallTree tree = new CallTree();
        SlowTickFilter filter = new SlowTickFilter(tree, THRESHOLD, true);

        filter.sample(stack("slow"), 10, 5 * MILLI);
        filter.sample(stack("slow"), 10, 5 * MILLI);
        assertEquals(0L, tree.samples(), "nothing is kept while the tick is still running");

        filter.sample(stack("next"), 11, 120 * MILLI);

        assertEquals(2L, tree.samples());
        assertEquals(1L, filter.ticksKept());
        assertEquals("a.b.C.slow", tree.hottestFrames(1).get(0).getAsJsonObject().get("frame").getAsString());
    }

    @Test
    void aTickThatEndsExactlyOnTheThresholdCounts() {
        CallTree tree = new CallTree();
        SlowTickFilter filter = new SlowTickFilter(tree, THRESHOLD, true);

        filter.sample(stack("edge"), 0, 0);
        filter.sample(stack("next"), 1, THRESHOLD);

        assertEquals(1L, tree.samples());
    }

    @Test
    void samplesThatCannotBeMatchedToADurationAreDroppedNotGuessedAt() {
        // Two ticks went by between samples. The duration on offer is the second one's; the held
        // samples are the first one's. Keeping them would file a fast tick under slow.
        CallTree tree = new CallTree();
        SlowTickFilter filter = new SlowTickFilter(tree, THRESHOLD, true);

        filter.sample(stack("unknown"), 10, 5 * MILLI);
        filter.sample(stack("later"), 12, 300 * MILLI);

        assertEquals(0L, tree.samples());
        assertEquals(0L, filter.ticksSeen());
    }

    @Test
    void theTickStillRunningWhenSamplingStopsIsNotCounted() {
        CallTree tree = new CallTree();
        SlowTickFilter filter = new SlowTickFilter(tree, THRESHOLD, true);

        filter.sample(stack("a"), 10, 5 * MILLI);
        filter.sample(stack("b"), 11, 200 * MILLI);
        filter.sample(stack("b"), 11, 200 * MILLI);

        assertEquals(1L, tree.samples());
        assertEquals(1L, filter.ticksSeen());
    }
}
