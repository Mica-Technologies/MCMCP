package com.micatechnologies.minecraft.mcmcp.chunkload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The chunk-load governor's arithmetic: which chunks, how many loads, when to stop, what to give back. */
class ChunkLoadTest {

    // ------------------------------------------------------------------
    // Chunk sets
    // ------------------------------------------------------------------

    @Test
    void negativeCoordinatesLandInTheirOwnChunkNotChunkZero() {
        assertEquals(ChunkKeys.of(-1, -1), ChunkKeys.ofBlock(-1, -16));
        assertEquals(ChunkKeys.of(0, 0), ChunkKeys.ofBlock(0, 15));
        long key = ChunkKeys.of(-30000, 12345);
        assertEquals(-30000, ChunkKeys.x(key));
        assertEquals(12345, ChunkKeys.z(key));
    }

    @Test
    void aBoxTouchesExactlyTheChunksItOverlapsWhicheverWayRoundItIsGiven() {
        Set<Long> box = ChunkKeys.box(15, 15, 16, 16);
        assertEquals(4, box.size(), "two blocks either side of a chunk corner touch four chunks");
        assertEquals(box, ChunkKeys.box(16, 16, 15, 15));
        assertEquals(1, ChunkKeys.box(0, 0, 15, 15).size());
        assertEquals(400, ChunkKeys.boxCount(0, 0, 319, 319));
    }

    // ------------------------------------------------------------------
    // Budgets
    // ------------------------------------------------------------------

    @Test
    void aTickAllowsAtMostItsShareAndTheMinuteBudgetsCarryAcrossTicks() {
        LoadBudget budget = new LoadBudget();
        long now = 1_000_000L;
        budget.newTick();
        assertEquals(8, budget.allowance("a", 100, now, 8, 600, 400));
        budget.record("a", 8, now);
        assertEquals(0, budget.allowance("a", 100, now, 8, 600, 400), "the tick's share is spent");
        budget.newTick();
        assertEquals(8, budget.allowance("a", 100, now, 8, 600, 400));
        assertEquals(8, budget.lastMinute(now));
    }

    @Test
    void oneCallerCannotSpendTheWholeServersMinute() {
        LoadBudget budget = new LoadBudget();
        long now = 0L;
        for (int tick = 0; tick < 100; tick++) {
            budget.newTick();
            int allowed = budget.allowance("greedy", 1000, now, 8, 600, 40);
            budget.record("greedy", allowed, now);
        }
        assertEquals(40, budget.lastMinute("greedy", now));
        budget.newTick();
        assertEquals(8, budget.allowance("polite", 1000, now, 8, 600, 40), "another caller still has room");
        assertTrue(budget.millisUntilRoom("greedy", now, 600, 40) > 0L);
    }

    @Test
    void loadsOlderThanAMinuteNoLongerCount() {
        LoadBudget budget = new LoadBudget();
        budget.record("a", 40, 0L);
        budget.newTick();
        assertEquals(0, budget.allowance("a", 10, 30_000L, 8, 600, 40));
        assertEquals(8, budget.allowance("a", 10, 60_001L, 8, 600, 40));
    }

    // ------------------------------------------------------------------
    // Server health
    // ------------------------------------------------------------------

    @Test
    void worseningIsImmediateAndRecoveryWaitsOutTheWindow() {
        ServerHealth health = new ServerHealth();
        assertEquals(ServerHealth.State.OK, health.update(20, 50, 0L, 40, 50, 85));
        assertEquals(ServerHealth.State.PAUSED, health.update(70, 50, 1_000L, 40, 50, 85));
        assertEquals(ServerHealth.State.PAUSED, health.update(20, 50, 2_000L, 40, 50, 85), "not yet recovered");
        assertEquals(ServerHealth.State.PAUSED, health.update(20, 50, 11_000L, 40, 50, 85));
        assertEquals(ServerHealth.State.OK, health.update(20, 50, 12_001L, 40, 50, 85));
    }

    @Test
    void aFullHeapPausesLoadingEvenWithFastTicks() {
        ServerHealth health = new ServerHealth();
        assertEquals(ServerHealth.State.PAUSED, health.update(5, 92, 0L, 40, 50, 85));
        assertEquals(0, ServerHealth.perTick(ServerHealth.State.PAUSED, 8));
        assertEquals(2, ServerHealth.perTick(ServerHealth.State.THROTTLED, 8));
        assertEquals(8, ServerHealth.perTick(ServerHealth.State.OK, 8));
    }

    @Test
    void aBriefDipDoesNotResetHowLongTheServerHasBeenPaused() {
        ServerHealth health = new ServerHealth();
        health.update(70, 0, 0L, 40, 50, 85);
        health.update(20, 0, 5_000L, 40, 50, 85);
        health.update(70, 0, 6_000L, 40, 50, 85);
        assertEquals(31_000L, health.pausedForMillis(31_000L));
    }

    // ------------------------------------------------------------------
    // Holds
    // ------------------------------------------------------------------

    private static Set<Long> chunks(long... keys) {
        Set<Long> set = new HashSet<>();
        for (long key : keys) {
            set.add(key);
        }
        return set;
    }

    @Test
    void anOverlappingHoldKeepsWhatTheOtherReleases() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold a = ledger.create("p1", 0, chunks(1, 2, 3), false, 0L, 120_000L, 900_000L);
        HoldLedger.Hold b = ledger.create("p2", 0, chunks(3, 4), false, 0L, 120_000L, 900_000L);
        assertEquals(3, ledger.add(a).size());
        assertEquals(1, ledger.add(b).size(), "only chunk 4 is new");
        assertEquals(4, ledger.heldChunks());

        List<HoldLedger.Released> released = ledger.remove(a.id);
        assertEquals(2, released.size(), "chunk 3 is still b's");
        assertTrue(ledger.isHeld(0, 3L));
    }

    @Test
    void onlyChunksMcmcpLoadedAreItsToUnload() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold hold = ledger.create("p", 0, chunks(1, 2), true, 0L, 1L, 1L);
        ledger.add(hold);
        ledger.markLoadedByUs(0, 2L);
        List<HoldLedger.Released> released = ledger.remove(hold.id);
        for (HoldLedger.Released chunk : released) {
            assertEquals(chunk.place.chunk == 2L, chunk.loadedByUs);
        }
    }

    @Test
    void aHoldEndsWhenIdleOrAtItsLeaseAndUsingItKeepsIt() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold hold = ledger.create("p", 0, chunks(7), false, 0L, 120_000L, 900_000L);
        ledger.add(hold);
        assertTrue(ledger.expired(119_000L).isEmpty());
        ledger.touch(0, chunks(7), 100_000L);
        assertTrue(ledger.expired(200_000L).isEmpty(), "use renewed the idle timer");
        assertEquals(Arrays.asList(hold.id), ledger.expired(220_001L));
        ledger.touch(0, chunks(7), 890_000L);
        assertEquals(Arrays.asList(hold.id), ledger.expired(900_000L), "the lease is absolute");
    }

    @Test
    void aCallsTemporaryHoldNeverExpiresByTimeAndIsShedLast() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold call = ledger.create("p", 0, chunks(1), true, 0L, 1L, 1L);
        HoldLedger.Hold idle = ledger.create("p", 0, chunks(2), false, 0L, 120_000L, 900_000L);
        HoldLedger.Hold busy = ledger.create("p", 0, chunks(3), false, 0L, 120_000L, 900_000L);
        ledger.add(call);
        ledger.add(idle);
        ledger.add(busy);
        ledger.touch(0, chunks(3), 50_000L);
        assertFalse(ledger.expired(10_000_000L).contains(call.id));
        assertEquals(Arrays.asList(idle.id, busy.id, call.id), ledger.shedOrder());
    }

    @Test
    void perCallerCountsAreSeparateAndAddedCountsOnlyNewChunks() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("p1", 0, chunks(1, 2), false, 0L, 1L, 1L));
        ledger.add(ledger.create("p2", 0, chunks(2, 3), false, 0L, 1L, 1L));
        assertEquals(2, ledger.heldChunks("p1"));
        assertEquals(2, ledger.heldChunks("p2"));
        assertEquals(1, ledger.added(0, chunks(3, 4)));
        assertEquals(2, ledger.added(1, chunks(3, 4)), "another dimension is another chunk");
    }
}
