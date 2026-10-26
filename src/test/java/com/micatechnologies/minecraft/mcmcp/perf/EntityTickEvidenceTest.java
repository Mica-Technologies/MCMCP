package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.micatechnologies.minecraft.mcmcp.perf.EntityTickEvidence.Tick;
import com.micatechnologies.minecraft.mcmcp.perf.EntityTickEvidence.Verdict;
import org.junit.jupiter.api.Test;

class EntityTickEvidenceTest {

    private static final int CUTOFF = 300;

    @Test
    void anEntityThatTickedMeansTheUpdateRan() {
        assertEquals(Tick.RAN, EntityTickEvidence.classify(3, true, 0, 0, false, CUTOFF));
    }

    @Test
    void anEmptyWorldBelowTheCutoffCountsUpAndSoRan() {
        assertEquals(Tick.RAN, EntityTickEvidence.classify(0, false, 12, 13, true, CUTOFF));
    }

    @Test
    void anEmptyWorldCountingUpPastTheCutoffIsVanillasEarlyReturn() {
        assertEquals(Tick.IDLE, EntityTickEvidence.classify(0, false, 300, 301, true, CUTOFF));
        assertEquals(Tick.IDLE, EntityTickEvidence.classify(2, false, 4000, 4001, true, CUTOFF));
    }

    @Test
    void anEmptyWorldWhoseIdleCountDidNotMoveWasCancelledAtTheHead() {
        // The #140 case: a pauser cancels updateEntities, so even the idle count stands still and the
        // rule alone read "ticking" for the Nether, with no chunk loaded at all.
        assertEquals(Tick.SKIPPED, EntityTickEvidence.classify(0, false, 0, 0, true, CUTOFF));
    }

    @Test
    void watchedEntitiesThatDidNotTickInAHeldWorldMeanTheUpdateDidNotRun() {
        // A forced chunk keeps the idle count at zero either way; only the entities can tell.
        assertEquals(Tick.SKIPPED, EntityTickEvidence.classify(4, false, 0, 0, false, CUTOFF));
    }

    @Test
    void aHeldWorldWithNothingToWatchSaysNothing() {
        assertEquals(Tick.UNKNOWN, EntityTickEvidence.classify(0, false, 0, 0, false, CUTOFF));
    }

    @Test
    void anUnreadableIdleCountFallsBackOnTheEntities() {
        assertEquals(Tick.UNKNOWN, EntityTickEvidence.classify(0, false, -1, -1, true, CUTOFF));
        assertEquals(Tick.SKIPPED, EntityTickEvidence.classify(1, false, -1, -1, true, CUTOFF));
    }

    @Test
    void aWorldWithNoObservationsIsUnobserved() {
        assertEquals(Verdict.UNOBSERVED, new EntityTickEvidence().verdict());
        EntityTickEvidence evidence = new EntityTickEvidence();
        evidence.record(Tick.UNKNOWN);
        assertEquals(Verdict.UNOBSERVED, evidence.verdict());
    }

    @Test
    void oneRunInTheWindowIsEnoughToCountAsTicking() {
        // A lag limiter that skips some ticks still ticks its entities.
        EntityTickEvidence evidence = new EntityTickEvidence();
        evidence.record(Tick.RAN);
        for (int i = 0; i < EntityTickEvidence.WINDOW - 1; i++) {
            evidence.record(Tick.SKIPPED);
        }
        assertEquals(Verdict.TICKING, evidence.verdict());
    }

    @Test
    void aRunThatHasLeftTheWindowNoLongerCounts() {
        EntityTickEvidence evidence = new EntityTickEvidence();
        evidence.record(Tick.RAN);
        for (int i = 0; i < EntityTickEvidence.WINDOW; i++) {
            evidence.record(Tick.SKIPPED);
        }
        assertEquals(Verdict.SUPPRESSED, evidence.verdict());
    }

    @Test
    void theNewestKnownTickDecidesBetweenIdleAndSuppressed() {
        EntityTickEvidence evidence = new EntityTickEvidence();
        evidence.record(Tick.SKIPPED);
        evidence.record(Tick.IDLE);
        evidence.record(Tick.UNKNOWN);
        assertEquals(Verdict.IDLE_CUTOFF, evidence.verdict());
        evidence.record(Tick.SKIPPED);
        assertEquals(Verdict.SUPPRESSED, evidence.verdict());
    }

    @Test
    void theReasonsAreTheNamesTheToolsReport() {
        assertEquals("idleCutoff", Verdict.IDLE_CUTOFF.reason);
        assertEquals("suppressed", Verdict.SUPPRESSED.reason);
    }
}
