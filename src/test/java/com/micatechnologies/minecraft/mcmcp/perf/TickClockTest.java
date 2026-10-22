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

    private static final long MS = 1_000_000L;

    @Test
    void aSteadyTwentyTicksASecondReadsTwentyRealTps() {
        TickClock clock = new TickClock();
        for (int i = 1; i <= 200; i++) {
            clock.completed(MS, i * 50L * MS);
        }
        assertEquals(20.0D, clock.ticksPerSecond(10_000L * MS, 5_000L * MS), 0.001D);
    }

    @Test
    void quickTicksWithLongStopsBetweenThemReadAsFewTicksASecond() {
        // A debugger freezing the server thread 2.5 s in every 3: ten 0.2 ms ticks, then nothing.
        TickClock clock = new TickClock();
        long at = 0L;
        long lastTick = 0L;
        for (int cycle = 0; cycle < 4; cycle++) {
            for (int i = 0; i < 10; i++) {
                at += 50L * MS;
                clock.completed(MS / 5L, at);
                lastTick = at;
            }
            at += 2_500L * MS;
        }
        // Measured where it is, on the server thread: just after a tick.
        double tps = clock.ticksPerSecond(lastTick, lastTick - 5_000L * MS);
        assertTrue(tps > 3.0D && tps < 4.5D, "about a sixth of 20: " + tps);
        assertEquals(0.0D, clock.ticksPerSecond(at, at - 2_000L * MS), 0.001D, "nothing finished in the stop");
    }

    @Test
    void ticksFinishedBeforeTheSpanDoNotCount() {
        TickClock clock = new TickClock();
        clock.completed(MS, 100L * MS);
        clock.completed(MS, 1_500L * MS);
        clock.completed(MS, 1_900L * MS);
        assertEquals(2.0D, clock.ticksPerSecond(2_000L * MS, 1_000L * MS), 0.001D);
        assertEquals(1.5D, clock.ticksPerSecond(2_000L * MS, 0L), 0.001D, "every tick there has been");
        assertEquals(-1.0D, clock.ticksPerSecond(2_000L * MS, 2_000L * MS), "no span, no rate");
    }

    @Test
    void moreTicksThanTheRingHoldsInTheSpanAreRatedOverTheOnesItHolds() {
        TickClock clock = new TickClock();
        for (int i = 1; i <= TickClock.RECENT * 2; i++) {
            clock.completed(MS, i * 10L * MS);
        }
        long now = TickClock.RECENT * 2 * 10L * MS;
        assertEquals(100.0D, clock.ticksPerSecond(now, now - 60_000L * MS), 0.5D, "a tick every 10 ms");
    }
}
