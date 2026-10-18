package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TickClockTest {

    @Test
    void aClockNeverThrottledReportsNoThrottleInAnyWindow() {
        assertFalse(new TickClock().throttledSince(Long.MIN_VALUE + 1));
    }

    @Test
    void aThrottleInsideTheWindowIsReportedAndOneBeforeItIsNot() {
        TickClock clock = new TickClock();
        clock.throttled(1_000L);
        assertTrue(clock.throttledSince(1_000L), "a throttle at the window's first instant counts");
        assertTrue(clock.throttledSince(500L));
        assertFalse(clock.throttledSince(1_001L), "a throttle that ended before the window does not");
    }

    @Test
    void theComparisonSurvivesTheNanoClockWrapping() {
        TickClock clock = new TickClock();
        clock.throttled(Long.MIN_VALUE + 10L);
        assertTrue(clock.throttledSince(Long.MAX_VALUE - 10L), "nanoTime values are compared by difference");
    }
}
