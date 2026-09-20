package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ring that tick and frame statistics are computed from. */
class DurationWindowTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MILLI = 1_000_000L;

    @Test
    void anEmptyWindowSummarisesToZeroRatherThanFailing() {
        DurationWindow.Summary summary = new DurationWindow(8).summarise(10 * SECOND, SECOND);

        assertEquals(0, summary.count());
        assertEquals(0.0D, summary.meanNanos());
        assertEquals(0L, summary.percentileNanos(95.0D));
        assertEquals(0.0D, summary.ratePerSecond());
        assertEquals(0.0D, summary.slowestMeanNanos(1.0D));
    }

    @Test
    void onlySamplesInsideTheWindowAreCounted() {
        DurationWindow window = new DurationWindow(16);
        window.record(1 * SECOND, 5 * MILLI);
        window.record(8 * SECOND, 10 * MILLI);
        window.record(9 * SECOND, 20 * MILLI);

        DurationWindow.Summary summary = window.summarise(10 * SECOND, 3 * SECOND);

        assertEquals(2, summary.count());
        assertEquals(15.0D * MILLI, summary.meanNanos());
    }

    @Test
    void aFullRingForgetsItsOldestSamples() {
        DurationWindow window = new DurationWindow(3);
        for (int i = 1; i <= 5; i++) {
            window.record(i * SECOND, i * MILLI);
        }

        DurationWindow.Summary summary = window.summarise(6 * SECOND, 100 * SECOND);

        assertEquals(3, summary.count());
        assertEquals(3 * MILLI, summary.minNanos());
        assertEquals(5 * MILLI, summary.maxNanos());
    }

    @Test
    void aPercentileIsAlwaysADurationThatActuallyHappened() {
        DurationWindow window = new DurationWindow(128);
        for (int i = 1; i <= 100; i++) {
            window.record(i * MILLI, i * MILLI);
        }

        DurationWindow.Summary summary = window.summarise(SECOND, SECOND);

        assertEquals(50 * MILLI, summary.percentileNanos(50.0D));
        assertEquals(95 * MILLI, summary.percentileNanos(95.0D));
        assertEquals(100 * MILLI, summary.percentileNanos(100.0D));
    }

    @Test
    void oneHitchInAHundredFramesSetsTheOnePercentLow() {
        DurationWindow window = new DurationWindow(128);
        for (int i = 1; i <= 99; i++) {
            window.record(i * MILLI, 5 * MILLI);
        }
        window.record(100 * MILLI, 80 * MILLI);

        DurationWindow.Summary summary = window.summarise(SECOND, SECOND);

        assertEquals(80.0D * MILLI, summary.slowestMeanNanos(1.0D));
        assertEquals(1, summary.countOver(50 * MILLI));
    }

    @Test
    void rateComesFromTheSpacingOfSamplesNotTheLengthOfTheWindowAsked() {
        // Twenty ticks a second for two seconds, read through a five-minute window: a server that
        // has only just started is still running at 20 TPS.
        DurationWindow window = new DurationWindow(128);
        for (int i = 0; i <= 40; i++) {
            window.record(i * 50 * MILLI, 10 * MILLI);
        }

        DurationWindow.Summary summary = window.summarise(2 * SECOND, 300 * SECOND);

        assertEquals(20.0D, summary.ratePerSecond(), 1.0e-9D);
    }

    @Test
    void onlySlowEventsThatCoincideWithAnIntervalAreCountedAsOverlapping() {
        DurationWindow window = new DurationWindow(16);
        window.record(1 * SECOND, 80 * MILLI);          // slow, during the collection below
        window.record(2 * SECOND, 80 * MILLI);          // slow, nowhere near one
        window.record(3 * SECOND, 10 * MILLI);          // during one, but not slow
        List<long[]> collections = Arrays.asList(
            new long[] {SECOND - 60 * MILLI, SECOND - 20 * MILLI},
            new long[] {3 * SECOND - 5 * MILLI, 3 * SECOND});

        assertEquals(1, window.countOverlapping(0L, 50 * MILLI, collections, 0L));
    }

    @Test
    void slackCatchesAnIntervalThatEndedJustBeforeTheEventBegan() {
        // The GC log is in whole milliseconds; a collection reported as ending a millisecond before
        // the tick it stalled began is the same event seen through a coarser clock.
        DurationWindow window = new DurationWindow(16);
        window.record(SECOND, 80 * MILLI);
        List<long[]> collections = Arrays.asList(
            new long[] {SECOND - 200 * MILLI, SECOND - 81 * MILLI});

        assertEquals(0, window.countOverlapping(0L, 50 * MILLI, collections, 0L));
        assertEquals(1, window.countOverlapping(0L, 50 * MILLI, collections, 3 * MILLI));
    }

    @Test
    void roundingKeepsWhatIsMeaningfulAndNoMore() {
        assertEquals(12.35D, DurationWindow.millis(12_345_678.0D));
        assertEquals(12.3D, DurationWindow.micros(12_345.0D));
    }
}
