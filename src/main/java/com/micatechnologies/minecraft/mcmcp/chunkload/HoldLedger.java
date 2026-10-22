package com.micatechnologies.minecraft.mcmcp.chunkload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/**
 * Every chunk MCMCP is holding, why, and for whom.
 *
 * <p>A hold is a set of chunks in one dimension kept loaded for one caller: a region an agent asked
 * to keep loaded, or the chunks one call needs while it runs. Chunks are reference-counted across
 * holds, because two holds can overlap and releasing one must not unload what the other still uses.
 * The ledger also remembers which chunks MCMCP itself loaded: only those are MCMCP's to unload, and
 * a chunk that was already in memory — near a player, held by some other mod — is left as it was.
 *
 * <p>Pure, and used from the server thread only.
 */
public final class HoldLedger {

    /** One hold. */
    public static final class Hold {

        public final String id;
        public final String caller;
        public final int dimension;
        public final Set<Long> chunks;
        public final boolean temporary;
        public final long createdMillis;
        public long lastUsedMillis;
        public long idleMillis;
        public long maxMillis;
        /** Shed after every other lasting hold: small and bounded in time, like a watch's (#98). */
        public boolean pinned;
        /** Who the caller is, for people to read (the agent's name); null when only the id is known. */
        @Nullable
        public String label;
        /**
         * For a temporary hold: when the call it belongs to ended or stopped waiting, or 0 while it has
         * not. Its release is normally already on its way then; a hold still here {@link #CALL_GONE_GRACE_MILLIS}
         * after that is a leak, and is expired and shed like one (#132). Null for a lasting hold, and for
         * a temporary one nobody can vouch for, which then ends only at its lease.
         */
        @Nullable
        public LongSupplier callEndedAt;

        Hold(String id, String caller, int dimension, Set<Long> chunks, boolean temporary,
             long nowMillis, long idleMillis, long maxMillis) {
            this.id = id;
            this.caller = caller;
            this.dimension = dimension;
            this.chunks = Collections.unmodifiableSet(new LinkedHashSet<>(chunks));
            this.temporary = temporary;
            this.createdMillis = nowMillis;
            this.lastUsedMillis = nowMillis;
            this.idleMillis = idleMillis;
            this.maxMillis = maxMillis;
        }

        /**
         * When this hold ends if nothing touches it: idle timeout or absolute lease, whichever first. A
         * temporary hold has no idle timeout, since its call may go minutes without reading in it, only
         * its lease.
         */
        public long expiresAtMillis() {
            return temporary ? createdMillis + maxMillis : Math.min(lastUsedMillis + idleMillis, createdMillis + maxMillis);
        }

        /** Whether this is a temporary hold whose call has been over for longer than its release takes. */
        public boolean callGone(long nowMillis) {
            LongSupplier ended = callEndedAt;
            long at = temporary && ended != null ? ended.getAsLong() : 0L;
            return at != 0L && nowMillis - at >= CALL_GONE_GRACE_MILLIS;
        }
    }

    /** A chunk in a dimension. */
    public static final class Place {

        public final int dimension;
        public final long chunk;

        public Place(int dimension, long chunk) {
            this.dimension = dimension;
            this.chunk = chunk;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Place && ((Place) other).dimension == dimension && ((Place) other).chunk == chunk;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(chunk) * 31 + dimension;
        }
    }

    /**
     * How long after its call ends a temporary hold may still be here before it counts as leaked: its
     * release is a task on the server thread, and may wait a tick or several for it.
     */
    public static final long CALL_GONE_GRACE_MILLIS = 5_000L;

    private final Map<String, Hold> holds = new LinkedHashMap<>();
    private final Map<Place, Integer> references = new HashMap<>();
    private final Set<Place> loadedByUs = new HashSet<>();
    private int nextId = 1;

    public String newId(boolean temporary) {
        return (temporary ? "call" : "h") + nextId++;
    }

    /**
     * Adds a hold.
     *
     * @return the chunks that were not held before, which the caller must now force
     */
    public List<Place> add(Hold hold) {
        holds.put(hold.id, hold);
        List<Place> fresh = new ArrayList<>();
        for (long chunk : hold.chunks) {
            Place place = new Place(hold.dimension, chunk);
            int count = references.getOrDefault(place, 0);
            references.put(place, count + 1);
            if (count == 0) {
                fresh.add(place);
            }
        }
        return fresh;
    }

    public Hold create(String caller, int dimension, Set<Long> chunks, boolean temporary, long nowMillis,
                       long idleMillis, long maxMillis) {
        return new Hold(newId(temporary), caller, dimension, chunks, temporary, nowMillis, idleMillis, maxMillis);
    }

    /**
     * Removes a hold.
     *
     * @return the chunks no hold uses any longer, which the caller must unforce; each says whether
     *         MCMCP loaded it and so may unload it
     */
    public List<Released> remove(String id) {
        Hold hold = holds.remove(id);
        if (hold == null) {
            return Collections.emptyList();
        }
        List<Released> released = new ArrayList<>();
        for (long chunk : hold.chunks) {
            Place place = new Place(hold.dimension, chunk);
            int count = references.getOrDefault(place, 0) - 1;
            if (count > 0) {
                references.put(place, count);
                continue;
            }
            references.remove(place);
            released.add(new Released(place, loadedByUs.remove(place)));
        }
        return released;
    }

    /** A chunk no longer held, and whether MCMCP loaded it. */
    public static final class Released {

        public final Place place;
        public final boolean loadedByUs;

        Released(Place place, boolean loadedByUs) {
            this.place = place;
            this.loadedByUs = loadedByUs;
        }
    }

    /** Records that MCMCP loaded this chunk, so that releasing it may unload it. */
    public void markLoadedByUs(int dimension, long chunk) {
        loadedByUs.add(new Place(dimension, chunk));
    }

    @Nullable
    public Hold get(String id) {
        return holds.get(id);
    }

    public Collection<Hold> holds() {
        return Collections.unmodifiableCollection(new ArrayList<>(holds.values()));
    }

    /** Distinct chunks held, all callers. */
    public int heldChunks() {
        return references.size();
    }

    /** Distinct chunks held by one caller. */
    public int heldChunks(String caller) {
        return placesOf(caller).size();
    }

    private Set<Place> placesOf(String caller) {
        Set<Place> places = new HashSet<>();
        for (Hold hold : holds.values()) {
            if (hold.caller.equals(caller)) {
                for (long chunk : hold.chunks) {
                    places.add(new Place(hold.dimension, chunk));
                }
            }
        }
        return places;
    }

    /** How many distinct callers hold anything, calls in progress included. */
    public int callers() {
        Set<String> callers = new HashSet<>();
        for (Hold hold : holds.values()) {
            callers.add(hold.caller);
        }
        return callers.size();
    }

    public static final String LIMIT_TOTAL = "chunks.maxHeldChunks";
    public static final String LIMIT_PER_CALLER = "chunks.maxHeldChunksPerCaller";

    /** Why a new hold would pass a cap: which one, and who holds what. */
    public static final class CapRefusal {

        /** The config key of the cap: {@link #LIMIT_TOTAL} or {@link #LIMIT_PER_CALLER}. */
        public final String limit;
        public final int cap;
        /** What the hold would add to the count that cap bounds. */
        public final int adding;
        public final int heldChunks;
        public final int callers;
        public final int callerHeldChunks;
        /**
         * Whether another caller's call in progress holds chunks that count against this cap. Those end
         * with their call, so a moment's wait may make room; a lasting hold never frees up that soon.
         */
        public final boolean callsInProgress;
        /**
         * Whether the asker's own calls in progress hold some of these. Not worth waiting for: the
         * asking call may be running inside one of them — a {@code server_run_test} step — and that one
         * cannot end until this one does (#132).
         */
        public final boolean ownCallsInProgress;

        CapRefusal(String limit, int cap, int adding, int heldChunks, int callers, int callerHeldChunks,
                   boolean callsInProgress, boolean ownCallsInProgress) {
            this.limit = limit;
            this.cap = cap;
            this.adding = adding;
            this.heldChunks = heldChunks;
            this.callers = callers;
            this.callerHeldChunks = callerHeldChunks;
            this.callsInProgress = callsInProgress;
            this.ownCallsInProgress = ownCallsInProgress;
        }

        public boolean perCaller() {
            return LIMIT_PER_CALLER.equals(limit);
        }

        /** The refusal as a caller reads it: which cap, the server's count and callers, and their own. */
        public String message() {
            StringBuilder text = new StringBuilder();
            if (perCaller()) {
                text.append("Hold cap reached for you (").append(limit).append(" = ").append(cap)
                    .append("): you hold ").append(callerHeldChunks).append(" chunk(s) and this needs ")
                    .append(adding).append(" more. The server holds ").append(heldChunks).append(" for ")
                    .append(callers).append(" caller(s). Release a hold of yours (server_release_loaded) or ")
                    .append("work in smaller pieces.");
            }
            else {
                text.append("Server hold cap reached (").append(limit).append(" = ").append(cap).append("): ")
                    .append(heldChunks).append(" chunk(s) held by ").append(callers).append(" caller(s), ")
                    .append(callerHeldChunks).append(" of them yours, and this needs ").append(adding)
                    .append(" more. server_keep_loaded op 'list' shows who holds what.");
            }
            if (callsInProgress) {
                text.append(" Calls still running hold some of these and give them back when they end: retry ")
                    .append("after busy.retryAfterMs.");
            }
            else if (ownCallsInProgress) {
                text.append(" Calls of yours still running hold some of these and give them back when they end; ")
                    .append("a call made from inside one of them, such as a server_run_test step, cannot wait ")
                    .append("for that.");
            }
            return text.toString();
        }
    }

    /**
     * Whether {@code caller} may take a hold over {@code chunks} under both caps. The server-wide cap
     * counts the chunks nobody holds yet; the caller's own counts the chunks this caller does not hold
     * yet, so overlapping another caller's hold is free for the server but not for the caller. A
     * background job's holds are taken under {@code job:<id>}, so a job is a caller of its own.
     *
     * @param maxPerCaller 0 for no per-caller cap
     * @return null when it fits
     */
    @Nullable
    public CapRefusal checkCaps(String caller, int dimension, Set<Long> chunks, int maxTotal, int maxPerCaller) {
        int held = heldChunks();
        Set<Place> mine = placesOf(caller);
        int addingTotal = added(dimension, chunks);
        if (held + addingTotal > maxTotal) {
            return new CapRefusal(LIMIT_TOTAL, maxTotal, addingTotal, held, callers(), mine.size(),
                hasCallInProgress(caller, false), hasCallInProgress(caller, true));
        }
        if (maxPerCaller > 0) {
            int addingMine = 0;
            for (long chunk : chunks) {
                if (!mine.contains(new Place(dimension, chunk))) {
                    addingMine++;
                }
            }
            if (mine.size() + addingMine > maxPerCaller) {
                // Only the asker's own chunks count here, so only their own calls could make room.
                return new CapRefusal(LIMIT_PER_CALLER, maxPerCaller, addingMine, held, callers(), mine.size(),
                    false, hasCallInProgress(caller, true));
            }
        }
        return null;
    }

    /** Whether a temporary hold is held by {@code caller} (own) or by anyone else (not own). */
    private boolean hasCallInProgress(String caller, boolean own) {
        for (Hold hold : holds.values()) {
            if (hold.temporary && hold.caller.equals(caller) == own) {
                return true;
            }
        }
        return false;
    }

    /** One caller's share of what is held. */
    public static final class Share {

        public final String caller;
        public final int chunks;
        /** Lasting holds: server_keep_loaded, a watch's. */
        public final int holds;
        /** Calls in progress holding chunks for themselves. */
        public final int calls;
        /** When the first of this caller's lasting holds ends if untouched, or {@link Long#MAX_VALUE}. */
        public final long nextExpiryMillis;

        Share(String caller, int chunks, int holds, int calls, long nextExpiryMillis) {
            this.caller = caller;
            this.chunks = chunks;
            this.holds = holds;
            this.calls = calls;
            this.nextExpiryMillis = nextExpiryMillis;
        }
    }

    /** Who holds what, the biggest holder first: what to read when a cap is in the way. */
    public List<Share> shares() {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Long> expiry = new HashMap<>();
        for (Hold hold : holds.values()) {
            int[] count = counts.computeIfAbsent(hold.caller, key -> new int[2]);
            if (hold.temporary) {
                count[1]++;
            }
            else {
                count[0]++;
                expiry.merge(hold.caller, hold.expiresAtMillis(), Math::min);
            }
        }
        List<Share> shares = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            String caller = entry.getKey();
            shares.add(new Share(caller, heldChunks(caller), entry.getValue()[0], entry.getValue()[1],
                expiry.getOrDefault(caller, Long.MAX_VALUE)));
        }
        shares.sort(Comparator.comparingInt((Share share) -> -share.chunks).thenComparing(share -> share.caller));
        return shares;
    }

    /** How many of {@code chunks} are not already held by anyone: what a new hold would add. */
    public int added(int dimension, Set<Long> chunks) {
        int added = 0;
        for (long chunk : chunks) {
            if (!references.containsKey(new Place(dimension, chunk))) {
                added++;
            }
        }
        return added;
    }

    public boolean isHeld(int dimension, long chunk) {
        return references.containsKey(new Place(dimension, chunk));
    }

    /** Renews the idle timer of every hold that contains one of these chunks: using a hold keeps it. */
    public void touch(int dimension, Set<Long> chunks, long nowMillis) {
        for (Hold hold : holds.values()) {
            if (hold.dimension == dimension && !Collections.disjoint(hold.chunks, chunks)) {
                hold.lastUsedMillis = nowMillis;
            }
        }
    }

    /**
     * When the soonest lease ends among the lasting holds containing any of these chunks: what a read
     * there warns about, since using a hold renews its idle timer but never its lease.
     *
     * @return the time, or {@link Long#MAX_VALUE} when no lasting hold contains them
     */
    public long leaseEndsAtMillis(int dimension, Set<Long> chunks) {
        long soonest = Long.MAX_VALUE;
        for (Hold hold : holds.values()) {
            if (!hold.temporary && hold.dimension == dimension && !Collections.disjoint(hold.chunks, chunks)) {
                soonest = Math.min(soonest, hold.createdMillis + hold.maxMillis);
            }
        }
        return soonest;
    }

    /**
     * Holds whose idle timer or lease has run out. A temporary hold normally ends with its call, not
     * here; it is listed only once its call is known to be over, or at its lease, both of which mean it
     * was never given back (#132).
     */
    public List<String> expired(long nowMillis) {
        List<String> ids = new ArrayList<>();
        for (Hold hold : holds.values()) {
            if (nowMillis >= hold.expiresAtMillis() || hold.callGone(nowMillis)) {
                ids.add(hold.id);
            }
        }
        return ids;
    }

    /** One caller's holds, with or without the temporary holds of their calls still running. */
    public List<String> ownedBy(String caller, boolean callsToo) {
        List<String> ids = new ArrayList<>();
        for (Hold hold : holds.values()) {
            if (hold.caller.equals(caller) && (callsToo || !hold.temporary)) {
                ids.add(hold.id);
            }
        }
        return ids;
    }

    /**
     * The order to give holds back in when the server is struggling: temporary holds whose call is
     * over first, since nothing uses them; then the longest-idle lasting holds, then the oldest. Pinned
     * holds after those, and calls in progress last of all — they end soon on their own.
     */
    public List<String> shedOrder(long nowMillis) {
        List<Hold> sorted = new ArrayList<>(holds.values());
        sorted.sort(Comparator.<Hold>comparingInt(hold -> hold.callGone(nowMillis) ? -1 : hold.temporary ? 2 : hold.pinned ? 1 : 0)
            .thenComparingLong(hold -> hold.lastUsedMillis)
            .thenComparingLong(hold -> hold.createdMillis));
        List<String> ids = new ArrayList<>();
        for (Hold hold : sorted) {
            ids.add(hold.id);
        }
        return ids;
    }
}
