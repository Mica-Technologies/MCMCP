package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.micatechnologies.minecraft.mcmcp.perf.StallDetector.Transition;
import org.junit.jupiter.api.Test;

/** The watchdog's decision about whether the client thread has stopped. */
class StallDetectorTest {

    private static final long SECOND = 1_000_000_000L;

    private static StallDetector detector() {
        StallDetector detector = new StallDetector("Client thread");
        detector.setThresholdMillis(10_000L);
        return detector;
    }

    @Test
    void aGameThatHasNeverFinishedAFrameIsNotStalledHowEverLongItTakes() {
        StallDetector detector = detector();
        for (long t = 1; t < 300; t++) {
            assertEquals(Transition.NONE, detector.observe(0L, t * SECOND));
        }
        assertFalse(detector.isArmed());
        assertFalse(detector.isStalled());
        assertEquals(0L, detector.silentMillis(300 * SECOND));
    }

    @Test
    void aCounterThatStopsForTheThresholdIsReportedStalledExactlyOnce() {
        StallDetector detector = detector();
        detector.observe(5L, SECOND);
        assertEquals(Transition.NONE, detector.observe(5L, 10 * SECOND));
        assertEquals(Transition.STALLED, detector.observe(5L, 11 * SECOND));
        assertEquals(Transition.NONE, detector.observe(5L, 30 * SECOND));
        assertTrue(detector.isStalled());
        assertEquals(29_000L, detector.silentMillis(30 * SECOND));
    }

    @Test
    void aCounterThatKeepsMovingIsNeverStalledHoweverSlowly() {
        StallDetector detector = detector();
        for (long t = 1; t < 100; t++) {
            // One frame every nine seconds is a dreadful client, not a hung one.
            assertEquals(Transition.NONE, detector.observe(t, t * 9 * SECOND));
        }
        assertFalse(detector.isStalled());
    }

    @Test
    void progressAfterAStallIsReportedAsRecovery() {
        StallDetector detector = detector();
        detector.observe(1L, 0L + SECOND);
        detector.observe(1L, 20 * SECOND);
        assertEquals(Transition.RECOVERED, detector.observe(2L, 21 * SECOND));
        assertFalse(detector.isStalled());
        assertEquals(0L, detector.silentMillis(21 * SECOND));
    }

    @Test
    void everyTransitionMovesTheVersionSoAStallAndRecoveryBetweenReadsIsNotMissed() {
        StallDetector detector = detector();
        long before = detector.version();
        detector.observe(1L, SECOND);
        detector.observe(1L, 20 * SECOND);
        detector.observe(2L, 21 * SECOND);
        assertEquals(before + 2, detector.version());
    }

    @Test
    void aTimeoutOnARunningThreadGetsNoExplanationAndOneOnAStalledThreadSaysSo() {
        StallDetector detector = detector();
        detector.observe(1L, SECOND);
        assertEquals("", detector.describeIfStalled(5 * SECOND));
        detector.observe(1L, 40 * SECOND);
        String said = detector.describeIfStalled(40 * SECOND);
        assertTrue(said.contains("39 s"), said);
        assertTrue(said.contains("game_health"), said);
    }
}
