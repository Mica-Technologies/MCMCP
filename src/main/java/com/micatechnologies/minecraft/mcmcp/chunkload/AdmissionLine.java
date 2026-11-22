package com.micatechnologies.minecraft.mcmcp.chunkload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The calls waiting for room under a hold cap, in the order they asked (#214).
 *
 * <p>A call refused by a cap that other callers' calls in progress fill waits here for them to give
 * their chunks back, instead of failing. Before this, each waiter polled on its own every 250 ms, and a
 * tiled read that gave a piece back took the next straight away: back-to-back reads by one agent kept
 * a player's whole cap for forty minutes while a second agent's every call was refused.
 *
 * <p>The rule is that room which has come free is reserved for the waiters it now fits, oldest first.
 * A new request may take only what is left after those reservations; if it would push out a waiter
 * the room has just come free for, it waits its turn instead. A waiter that does not fit yet reserves
 * nothing, so it never holds up a smaller call that fits: a load waiting on a long write must not stall
 * every other agent behind it. Each walk admits, oldest first, every waiter that now fits, and turns
 * away one that no longer may clear (only lasting holds stand in its way now) or whose time is up.
 *
 * <p>One exception keeps a call from waiting on itself: a caller with a call in progress (a
 * {@code server_run_test} step runs inside its test's call) never yields to a reservation, since the
 * room may be that very call's (#132).
 *
 * <p>Pure, and used from the server thread only.
 */
public final class AdmissionLine<T> {

    /** One call waiting for room. */
    public static final class Entry<T> {

        public final String caller;
        public final int dimension;
        public final Set<Long> chunks;
        public final long joinedMillis;
        public final long deadlineMillis;
        public final T payload;
        /**
         * Why it waits, as of the last walk: the cap it does not fit, counted for this entry's own
         * caller. Null while it fits but waits behind a reservation; never another caller's numbers.
         */
        @Nullable
        volatile HoldLedger.CapRefusal refusal;
        /** Set from any thread when the call stops waiting; the next walk drops it. */
        volatile boolean abandoned;

        Entry(String caller, int dimension, Set<Long> chunks, long joinedMillis, long deadlineMillis, T payload) {
            this.caller = caller;
            this.dimension = dimension;
            this.chunks = chunks;
            this.joinedMillis = joinedMillis;
            this.deadlineMillis = deadlineMillis;
            this.payload = payload;
        }

        public void abandon() {
            abandoned = true;
        }

        @Nullable
        public HoldLedger.CapRefusal refusal() {
            return refusal;
        }
    }

    /** What a walk does with each waiter it ends. */
    public interface Outcomes<T> {

        /** It fits: take its hold now, before the walk moves on. */
        void admit(Entry<T> entry);

        /**
         * It waits no longer: its time ran out, or only lasting holds stand in its way.
         *
         * @param refusal the cap it does not fit, counted for its own caller; null if it timed out
         *                fitting but behind others the room was reserved for
         */
        void turnAway(Entry<T> entry, @Nullable HoldLedger.CapRefusal refusal, boolean timedOut);
    }

    private final List<Entry<T>> entries = new ArrayList<>();

    public Entry<T> join(String caller, int dimension, Set<Long> chunks, long nowMillis, long waitMillis,
                         T payload, @Nullable HoldLedger.CapRefusal refusal) {
        Entry<T> entry = new Entry<>(caller, dimension, chunks, nowMillis, nowMillis + waitMillis, payload);
        entry.refusal = refusal;
        entries.add(entry);
        return entry;
    }

    public void remove(Entry<T> entry) {
        entries.remove(entry);
    }

    public int size() {
        return entries.size();
    }

    public List<Entry<T>> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * The room reserved now: the waiters that the next walk admits, oldest first, each as the hold it
     * will take.
     */
    public List<HoldLedger.Pending> reservations(HoldLedger ledger, HoldLedger.Caps caps) {
        List<HoldLedger.Pending> reserved = new ArrayList<>();
        for (Entry<T> entry : entries) {
            if (!entry.abandoned
                && ledger.checkCaps(entry.caller, entry.dimension, entry.chunks, caps, reserved) == null) {
                reserved.add(new HoldLedger.Pending(entry.caller, entry.dimension, entry.chunks));
            }
        }
        return reserved;
    }

    /**
     * Whether a new request that fits the caps must still wait: it would take room reserved for a waiter
     * the room has come free for.
     */
    public boolean mustYield(HoldLedger ledger, HoldLedger.Caps caps, String caller, int dimension, Set<Long> chunks) {
        if (entries.isEmpty() || ledger.hasCallInProgress(caller)) {
            return false;
        }
        List<HoldLedger.Pending> reserved = reservations(ledger, caps);
        return !reserved.isEmpty() && ledger.checkCaps(caller, dimension, chunks, caps, reserved) != null;
    }

    /** Admits, oldest first, every waiter that fits now; turns away those that will not get in. */
    public void walk(HoldLedger ledger, HoldLedger.Caps caps, long nowMillis, Outcomes<T> outcomes) {
        for (Iterator<Entry<T>> it = entries.iterator(); it.hasNext(); ) {
            Entry<T> entry = it.next();
            if (entry.abandoned) {
                it.remove();
                continue;
            }
            // Counted against what is really held, the waiters admitted earlier in this walk included.
            HoldLedger.CapRefusal refusal = ledger.checkCaps(entry.caller, entry.dimension, entry.chunks, caps);
            if (refusal == null) {
                it.remove();
                outcomes.admit(entry);
                continue;
            }
            entry.refusal = refusal;
            if (!refusal.mayClear) {
                it.remove();
                outcomes.turnAway(entry, refusal, false);
            }
            else if (nowMillis >= entry.deadlineMillis) {
                it.remove();
                outcomes.turnAway(entry, refusal, true);
            }
        }
    }

    /** Removes the waiters {@code key} covers (a logout, lost access), and returns them. */
    public List<Entry<T>> removeCovered(String key) {
        List<Entry<T>> removed = new ArrayList<>();
        for (Iterator<Entry<T>> it = entries.iterator(); it.hasNext(); ) {
            Entry<T> entry = it.next();
            if (HoldLedger.covers(key, entry.caller)) {
                it.remove();
                removed.add(entry);
            }
        }
        return removed;
    }
}
