package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aFinishedTickIsLookedUpByItsIndexAndOneStillRunningIsUnknown() {
        TickClock clock = new TickClock();
        clock.completed(10L);
        clock.completed(20L);
        assertEquals(10L, clock.durationOf(0L));
        assertEquals(20L, clock.durationOf(1L));
        assertEquals(-1L, clock.durationOf(2L), "tick 2 is the one running now");
        assertEquals(-1L, clock.durationOf(-1L));
    }

    @Test
    void aTickOlderThanTheRingIsUnknownRatherThanAnotherTicksDuration() {
        TickClock clock = new TickClock();
        for (int i = 0; i < TickClock.RECENT + 5; i++) {
            clock.completed(i);
        }
        assertEquals(-1L, clock.durationOf(4L), "its slot now holds tick " + (TickClock.RECENT + 4));
        assertEquals(5L, clock.durationOf(5L));
        assertEquals(TickClock.RECENT + 4L, clock.durationOf(TickClock.RECENT + 4L));
    }

    @Test
    void theComparisonSurvivesTheNanoClockWrapping() {
        TickClock clock = new TickClock();
        clock.throttled(Long.MIN_VALUE + 10L);
        assertTrue(clock.throttledSince(Long.MAX_VALUE - 10L), "nanoTime values are compared by difference");
    }
}
