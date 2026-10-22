package com.micatechnologies.minecraft.mcmcp.chunkload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void aReadKnowsWhenTheSoonestLeaseOverItsChunksEnds() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("p", 0, chunks(1, 2), false, 0L, 120_000L, 900_000L));
        ledger.add(ledger.create("q", 0, chunks(2, 3), false, 100_000L, 120_000L, 14_400_000L));
        ledger.add(ledger.create("p", 0, chunks(4), true, 0L, 1L, 1L));
        assertEquals(900_000L, ledger.leaseEndsAtMillis(0, chunks(2)), "the sooner of two overlapping holds");
        assertEquals(14_500_000L, ledger.leaseEndsAtMillis(0, chunks(3)));
        assertEquals(Long.MAX_VALUE, ledger.leaseEndsAtMillis(0, chunks(4)), "a call's own hold has no lease");
        assertEquals(Long.MAX_VALUE, ledger.leaseEndsAtMillis(1, chunks(1)), "another dimension");
    }

    @Test
    void aRunningCallsHoldOutlivesItsIdleTimerAndIsShedLast() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold call = ledger.create("p", 0, chunks(1), true, 0L, 1L, 3_600_000L);
        call.callEndedAt = () -> 0L;
        HoldLedger.Hold idle = ledger.create("p", 0, chunks(2), false, 0L, 120_000L, 900_000L);
        HoldLedger.Hold busy = ledger.create("p", 0, chunks(3), false, 0L, 120_000L, 900_000L);
        ledger.add(call);
        ledger.add(idle);
        ledger.add(busy);
        ledger.touch(0, chunks(3), 50_000L);
        assertFalse(ledger.expired(1_000_000L).contains(call.id), "a call may go minutes without reading");
        assertEquals(Arrays.asList(idle.id, busy.id, call.id), ledger.shedOrder(1_000_000L));
    }

    @Test
    void aCallsHoldThatWasNeverGivenBackEndsAtItsLease() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold call = ledger.create("p", 0, chunks(1), true, 0L, 1L, 3_600_000L);
        ledger.add(call);
        assertFalse(ledger.expired(3_599_999L).contains(call.id));
        assertEquals(Arrays.asList(call.id), ledger.expired(3_600_000L), "a leaked hold does not live for ever");
    }

    @Test
    void aCallsHoldIsALeakOnlyOnceItsCallHasBeenOverLongerThanItsReleaseTakes() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold call = ledger.create("p", 0, chunks(1), true, 0L, 1L, 3_600_000L);
        long[] ended = {0L};
        call.callEndedAt = () -> ended[0];
        HoldLedger.Hold region = ledger.create("p", 0, chunks(2), false, 0L, 120_000L, 900_000L);
        ledger.add(call);
        ledger.add(region);
        assertFalse(ledger.expired(60_000L).contains(call.id), "its call is still running");
        ended[0] = 60_000L;
        assertFalse(ledger.expired(61_000L).contains(call.id), "its release is on its way to the server thread");
        assertEquals(Arrays.asList(call.id), ledger.expired(60_000L + HoldLedger.CALL_GONE_GRACE_MILLIS));
        assertEquals(Arrays.asList(call.id, region.id), ledger.shedOrder(70_000L),
            "nothing uses it, so it goes before any hold someone asked for");
    }

    @Test
    void aWatchsPinnedHoldIsShedAfterEveryOtherLastingHoldEvenWhenIdlest() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold watch = ledger.create("p", 0, chunks(1), false, 0L, 300_000L, 300_000L);
        watch.pinned = true;
        HoldLedger.Hold region = ledger.create("p", 0, chunks(2), false, 10_000L, 120_000L, 900_000L);
        HoldLedger.Hold call = ledger.create("p", 0, chunks(3), true, 0L, 1L, 1L);
        ledger.add(watch);
        ledger.add(region);
        ledger.add(call);
        assertEquals(Arrays.asList(region.id, watch.id, call.id), ledger.shedOrder(20_000L));
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

    @Test
    void releasingAllOfACallersHoldsSparesTheCallsTheyStillHaveRunning() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold call = ledger.create("p", 0, chunks(1), true, 0L, 1L, 1L);
        HoldLedger.Hold kept = ledger.create("p", 0, chunks(2), false, 0L, 1L, 1L);
        ledger.add(call);
        ledger.add(kept);
        ledger.add(ledger.create("q", 0, chunks(3), false, 0L, 1L, 1L));
        assertEquals(Arrays.asList(kept.id), ledger.ownedBy("p", false),
            "another script on the same account cleaning up must not fail this one's read");
        assertEquals(Arrays.asList(call.id, kept.id), ledger.ownedBy("p", true), "logout ends everything");
    }

    // ------------------------------------------------------------------
    // Hold caps (#121)
    // ------------------------------------------------------------------

    private static Set<Long> range(long from, long count) {
        Set<Long> set = new HashSet<>();
        for (long i = 0; i < count; i++) {
            set.add(from + i);
        }
        return set;
    }

    @Test
    void oneCallerAtItsCapDoesNotStopAnotherCallerTakingAHold() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("agent-a", 0, range(0, 512), false, 0L, 1L, 1L));
        HoldLedger.CapRefusal more = ledger.checkCaps("agent-a", 0, range(512, 1), 1024, 512);
        assertEquals(HoldLedger.LIMIT_PER_CALLER, more.limit, "agent a has used its own share");
        assertNull(ledger.checkCaps("agent-b", 0, range(512, 400), 1024, 512),
            "agent b still has its whole share");
    }

    @Test
    void aBackgroundJobIsACallerOfItsOwnAgainstThePerCallerCap() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("endpoint/orchestrator:1", 0, range(0, 500), false, 0L, 1L, 1L));
        assertNull(ledger.checkCaps("job:7", 0, range(500, 500), 2048, 512),
            "a job the agent submitted does not draw on the agent's share");
    }

    @Test
    void theServerCapIsReportedWithTheCountHeldTheCallersAndTheAskersShare() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("a", 0, range(0, 400), false, 0L, 1L, 1L));
        ledger.add(ledger.create("b", 0, range(400, 400), false, 0L, 1L, 1L));
        ledger.add(ledger.create("c", 0, range(800, 100), false, 0L, 1L, 1L));
        HoldLedger.CapRefusal refusal = ledger.checkCaps("c", 0, range(900, 200), 1024, 512);
        assertEquals(HoldLedger.LIMIT_TOTAL, refusal.limit);
        assertEquals(900, refusal.heldChunks);
        assertEquals(3, refusal.callers);
        assertEquals(100, refusal.callerHeldChunks);
        assertEquals(200, refusal.adding);
        assertFalse(refusal.callsInProgress, "only lasting holds: no point waiting");
        String message = refusal.message();
        assertTrue(message.contains("chunks.maxHeldChunks = 1024"), message);
        assertTrue(message.contains("900 chunk(s) held by 3 caller(s)"), message);
        assertTrue(message.contains("100 of them yours"), message);
    }

    @Test
    void thePerCallerCapCountsChunksTheCallerDoesNotHoldEvenIfAnotherCallerDoes() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("a", 0, range(0, 10), false, 0L, 1L, 1L));
        ledger.add(ledger.create("b", 0, range(100, 8), false, 0L, 1L, 1L));
        HoldLedger.CapRefusal refusal = ledger.checkCaps("b", 0, range(0, 5), 1024, 10);
        assertEquals(HoldLedger.LIMIT_PER_CALLER, refusal.limit,
            "a's chunks cost the server nothing more, but b would hold 13");
        assertEquals(5, refusal.adding);
        assertEquals(8, refusal.callerHeldChunks);
        assertTrue(refusal.message().contains("you hold 8 chunk(s)"), refusal.message());
        assertNull(ledger.checkCaps("b", 0, range(100, 8), 1024, 10), "re-holding its own chunks adds nothing");
    }

    @Test
    void aPerCallerCapOfZeroLeavesOnlyTheServerCap() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("a", 0, range(0, 900), false, 0L, 1L, 1L));
        assertNull(ledger.checkCaps("a", 0, range(900, 100), 1024, 0));
        assertEquals(HoldLedger.LIMIT_TOTAL, ledger.checkCaps("a", 0, range(900, 200), 1024, 0).limit);
    }

    @Test
    void aCapThatCallsInProgressCountAgainstSaysItMayClearSoon() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("a", 0, range(0, 10), true, 0L, 1L, 1L));
        ledger.add(ledger.create("b", 0, range(10, 10), false, 0L, 1L, 1L));
        assertTrue(ledger.checkCaps("b", 0, range(20, 5), 20, 0).callsInProgress, "a's call ends soon");
        assertFalse(ledger.checkCaps("b", 0, range(20, 5), 1024, 10).callsInProgress,
            "b's own share is all lasting holds");
    }

    @Test
    void aCapThatOnlyTheAskersOwnRunningCallsCountAgainstIsNotWorthWaitingFor() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("a", 0, range(0, 10), true, 0L, 1L, 3_600_000L));
        HoldLedger.CapRefusal own = ledger.checkCaps("a", 0, range(10, 5), 1024, 12);
        assertEquals(HoldLedger.LIMIT_PER_CALLER, own.limit);
        assertFalse(own.callsInProgress, "a server_run_test step cannot outwait the test it runs in");
        assertTrue(own.ownCallsInProgress);
        assertTrue(own.message().contains("cannot wait"), own.message());
        HoldLedger.CapRefusal total = ledger.checkCaps("a", 0, range(10, 15), 20, 0);
        assertEquals(HoldLedger.LIMIT_TOTAL, total.limit);
        assertFalse(total.callsInProgress, "the only running call is the asker's own");
        assertTrue(ledger.checkCaps("b", 0, range(10, 15), 20, 0).callsInProgress, "for b, a's call ends soon");
    }

    @Test
    void sharesListWhoHoldsWhatTheBiggestFirstWithTheSoonestExpiry() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("small", 0, range(0, 2), false, 0L, 60_000L, 900_000L));
        ledger.add(ledger.create("big", 0, range(10, 5), false, 0L, 120_000L, 900_000L));
        ledger.add(ledger.create("big", 0, range(12, 5), false, 0L, 30_000L, 900_000L));
        ledger.add(ledger.create("big", 0, range(40, 1), true, 0L, 1L, 1L));
        List<HoldLedger.Share> shares = ledger.shares();
        assertEquals(2, shares.size());
        assertEquals("big", shares.get(0).caller);
        assertEquals(8, shares.get(0).chunks, "overlapping holds count each chunk once");
        assertEquals(2, shares.get(0).holds);
        assertEquals(1, shares.get(0).calls);
        assertEquals(30_000L, shares.get(0).nextExpiryMillis);
        assertEquals(60_000L, shares.get(1).nextExpiryMillis);
        assertEquals(2, ledger.callers());
    }
}
