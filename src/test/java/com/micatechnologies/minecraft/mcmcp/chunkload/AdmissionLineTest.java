package com.micatechnologies.minecraft.mcmcp.chunkload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The order calls waiting for room under a hold cap get it in (#214): room that comes free is the
 * oldest waiter's it fits, nobody is stalled behind a waiter it does not fit yet, and nobody waits on
 * what will not end soon.
 */
class AdmissionLineTest {

    private static final HoldLedger.Caps CAPS = new HoldLedger.Caps(100, 0, 0);

    private static Set<Long> range(long from, long count) {
        Set<Long> set = new HashSet<>();
        for (long i = 0; i < count; i++) {
            set.add(from + i);
        }
        return set;
    }

    /** Records what a walk did, and takes each admitted waiter's hold as the governor would. */
    private static final class Recorder implements AdmissionLine.Outcomes<String> {

        final HoldLedger ledger;
        final List<String> admitted = new ArrayList<>();
        final List<String> turnedAway = new ArrayList<>();
        final List<Boolean> timedOut = new ArrayList<>();
        final List<HoldLedger.CapRefusal> refusals = new ArrayList<>();

        Recorder(HoldLedger ledger) {
            this.ledger = ledger;
        }

        @Override
        public void admit(AdmissionLine.Entry<String> entry) {
            ledger.add(ledger.create(entry.caller, entry.dimension, entry.chunks, true, 0L, 1L, 3_600_000L));
            admitted.add(entry.payload);
        }

        @Override
        public void turnAway(AdmissionLine.Entry<String> entry, @Nullable HoldLedger.CapRefusal refusal,
                             boolean timedOut) {
            turnedAway.add(entry.payload);
            this.timedOut.add(timedOut);
            refusals.add(refusal);
        }
    }

    private static AdmissionLine.Entry<String> waitFor(AdmissionLine<String> line, HoldLedger ledger, String caller,
                                                       Set<Long> chunks, long now) {
        HoldLedger.CapRefusal refusal = ledger.checkCaps(caller, 0, chunks, CAPS);
        assertTrue(refusal != null && refusal.mayClear, caller + " must be waiting on calls in progress");
        return line.join(caller, 0, chunks, now, 30_000L, caller, refusal);
    }

    @Test
    void aTiledReadGivingAPieceBackCannotTakeItStraightBackFromSomeoneWaiting() {
        // Agent a's read holds 90 of 100 in a running call; agent b needs 40 and waits in line.
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold piece = ledger.create("a", 0, range(0, 90), true, 0L, 1L, 3_600_000L);
        ledger.add(piece);
        AdmissionLine<String> line = new AdmissionLine<>();
        waitFor(line, ledger, "b", range(1000, 40), 0L);

        // a's piece is read and given back; a asks for its next piece of 90 at once.
        ledger.remove(piece.id);
        assertNull(ledger.checkCaps("a", 0, range(100, 90), CAPS), "by the caps alone a would fit");
        assertTrue(line.mustYield(ledger, CAPS, "a", 0, range(100, 90)), "but the room came free for b first");
        assertFalse(line.mustYield(ledger, CAPS, "a", 0, range(100, 60)), "what b does not need is a's to take");

        Recorder outcomes = new Recorder(ledger);
        line.walk(ledger, CAPS, 1_000L, outcomes);
        assertEquals(Collections.singletonList("b"), outcomes.admitted, "b gets in on the next walk");
        assertEquals(0, line.size());
    }

    @Test
    void aWaiterThatDoesNotFitYetNeverStallsASmallerCallThatDoes() {
        // A long write holds 80; a big read waiting for 50 cannot get in until the write ends. Small
        // loads that fit beside the write must not wait behind it.
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold write = ledger.create("writer", 0, range(0, 80), true, 0L, 1L, 3_600_000L);
        ledger.add(write);
        AdmissionLine<String> line = new AdmissionLine<>();
        waitFor(line, ledger, "big", range(1000, 50), 0L);

        assertFalse(line.mustYield(ledger, CAPS, "small", 0, range(2000, 10)));
        assertTrue(line.reservations(ledger, CAPS).isEmpty(), "big reserves nothing it cannot use yet");

        // Waiters that fit are admitted whatever their place: one behind big gets in first.
        line.join("small2", 0, range(3000, 10), 10L, 30_000L, "small2", null);
        Recorder outcomes = new Recorder(ledger);
        line.walk(ledger, CAPS, 100L, outcomes);
        assertEquals(Collections.singletonList("small2"), outcomes.admitted);

        ledger.remove(write.id);
        line.walk(ledger, CAPS, 200L, outcomes);
        assertEquals(Arrays.asList("small2", "big"), outcomes.admitted, "big gets in once the write ends");
    }

    @Test
    void freedRoomGoesToTheOldestWaitersItFitsInOrder() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold running = ledger.create("x", 0, range(0, 90), true, 0L, 1L, 3_600_000L);
        ledger.add(running);
        AdmissionLine<String> line = new AdmissionLine<>();
        waitFor(line, ledger, "first", range(1000, 60), 0L);
        waitFor(line, ledger, "second", range(2000, 30), 10L);
        waitFor(line, ledger, "third", range(3000, 30), 20L);

        ledger.remove(running.id);
        List<HoldLedger.Pending> reserved = line.reservations(ledger, CAPS);
        assertEquals(2, reserved.size(), "60 + 30 fit in 100; the third's 30 does not fit beside them");
        assertTrue(line.mustYield(ledger, CAPS, "newcomer", 0, range(4000, 20)));
        assertFalse(line.mustYield(ledger, CAPS, "newcomer", 0, range(4000, 10)), "the last 10 are free");

        Recorder outcomes = new Recorder(ledger);
        line.walk(ledger, CAPS, 100L, outcomes);
        assertEquals(Arrays.asList("first", "second"), outcomes.admitted);
        assertEquals(1, line.size(), "the third waits on, the others' calls being in progress");
    }

    @Test
    void aWaiterUnderOnePlayersCapReservesNothingFromAnotherPlayerOrTheEndpoint() {
        HoldLedger.Caps caps = new HoldLedger.Caps(1000, 100, 0);
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold running = ledger.create("player:u1~a", 0, range(0, 90), true, 0L, 1L, 3_600_000L);
        ledger.add(running);
        AdmissionLine<String> line = new AdmissionLine<>();
        HoldLedger.CapRefusal waiting = ledger.checkCaps("player:u1~b", 0, range(1000, 40), caps);
        assertTrue(waiting.perPlayer() && waiting.mayClear);
        line.join("player:u1~b", 0, range(1000, 40), 0L, 30_000L, "u1b", waiting);
        ledger.remove(running.id);

        assertTrue(line.mustYield(ledger, caps, "player:u1~c", 0, range(2000, 70)), "u1's agents share that room");
        assertFalse(line.mustYield(ledger, caps, "player:u2~a", 0, range(2000, 70)), "u2's agents do not");
        assertFalse(line.mustYield(ledger, caps, "endpoint/x", 0, range(2000, 70)), "nor does the endpoint");
    }

    @Test
    void aWaiterIsTurnedAwayOnceOnlyLastingHoldsStandInItsWay() {
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold running = ledger.create("x", 0, range(0, 50), true, 0L, 1L, 3_600_000L);
        ledger.add(running);
        AdmissionLine<String> line = new AdmissionLine<>();
        waitFor(line, ledger, "w", range(1000, 60), 0L);

        // The running call ends, but a lasting hold takes its place: nothing will end soon now.
        ledger.remove(running.id);
        ledger.add(ledger.create("y", 0, range(200, 50), false, 0L, 120_000L, 900_000L));
        Recorder outcomes = new Recorder(ledger);
        line.walk(ledger, CAPS, 100L, outcomes);
        assertEquals(Collections.singletonList("w"), outcomes.turnedAway);
        assertFalse(outcomes.timedOut.get(0));
        assertEquals(0, line.size());
    }

    @Test
    void aWaiterTurnedAwayAtItsDeadlineIsToldItsOwnCountsNeverAnotherCallers() {
        HoldLedger ledger = new HoldLedger();
        ledger.add(ledger.create("x", 0, range(0, 90), true, 0L, 1L, 3_600_000L));
        ledger.add(ledger.create("w", 0, range(500, 3), false, 0L, 120_000L, 900_000L));
        AdmissionLine<String> line = new AdmissionLine<>();
        waitFor(line, ledger, "w", range(1000, 20), 0L);
        Recorder outcomes = new Recorder(ledger);
        line.walk(ledger, CAPS, 29_999L, outcomes);
        assertTrue(outcomes.turnedAway.isEmpty());
        line.walk(ledger, CAPS, 30_000L, outcomes);
        assertEquals(Collections.singletonList("w"), outcomes.turnedAway);
        assertTrue(outcomes.timedOut.get(0));
        HoldLedger.CapRefusal told = outcomes.refusals.get(0);
        assertEquals(3, told.callerHeldChunks, "w's own 3, not x's 90");
        assertEquals(20, told.adding);
    }

    @Test
    void aCallerWithACallInProgressNeverYieldsToAReservation() {
        // A server_run_test step runs inside its test's call; the room reserved may be that call's own,
        // which cannot end until the step does (#132).
        HoldLedger ledger = new HoldLedger();
        HoldLedger.Hold other = ledger.create("x", 0, range(0, 60), true, 0L, 1L, 3_600_000L);
        ledger.add(other);
        ledger.add(ledger.create("test", 0, range(100, 10), true, 0L, 1L, 3_600_000L));
        AdmissionLine<String> line = new AdmissionLine<>();
        waitFor(line, ledger, "w", range(1000, 60), 0L);
        ledger.remove(other.id);
        assertFalse(line.mustYield(ledger, CAPS, "test", 0, range(200, 10)));
        assertTrue(line.mustYield(ledger, CAPS, "someone", 0, range(200, 40)));
    }

    @Test
    void anAbandonedWaiterLeavesTheLineWithoutBeingAdmittedOrToldAnything() {
        HoldLedger ledger = new HoldLedger();
        AdmissionLine<String> line = new AdmissionLine<>();
        AdmissionLine.Entry<String> entry = line.join("w", 0, range(1000, 20), 0L, 30_000L, "w", null);
        entry.abandon();
        assertTrue(line.reservations(ledger, CAPS).isEmpty());
        Recorder outcomes = new Recorder(ledger);
        line.walk(ledger, CAPS, 100L, outcomes);
        assertTrue(outcomes.admitted.isEmpty());
        assertTrue(outcomes.turnedAway.isEmpty());
        assertEquals(0, line.size());
        assertEquals(0, ledger.heldChunks());
    }

    @Test
    void aLogoutTakesThatPlayersWaitersOutOfTheLineAndNoOneElses() {
        AdmissionLine<String> line = new AdmissionLine<>();
        AdmissionLine.Entry<String> mine = line.join("player:u1~a", 0, range(0, 1), 0L, 30_000L, "a", null);
        line.join("player:u2~a", 0, range(0, 1), 0L, 30_000L, "b", null);
        line.join("job:3", 0, range(0, 1), 0L, 30_000L, "c", null);
        List<AdmissionLine.Entry<String>> removed = line.removeCovered("player:u1");
        assertEquals(1, removed.size());
        assertSame(mine, removed.get(0));
        assertEquals(2, line.size());
    }
}
