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
        /** The tool whose call took this, for a listing to say what a call's hold is for; null if unknown. */
        @Nullable
        public String tool;
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
    private final java.util.ArrayDeque<Ended> ended = new java.util.ArrayDeque<>();
    private int nextId = 1;
    /** Bumped by every hold added or removed: the only thing that can make room under a cap. */
    private long version;

    /** Changes whenever what the caps count may have changed. */
    public long version() {
        return version;
    }

    /** How many ended holds are remembered, newest last. */
    public static final int ENDED_KEPT = 32;

    /** A lasting hold that is over, and what ended it. */
    public static final class Ended {

        public final String id;
        public final String caller;
        public final int chunks;
        public final String why;
        public final long atMillis;

        Ended(String id, String caller, int chunks, String why, long atMillis) {
            this.id = id;
            this.caller = caller;
            this.chunks = chunks;
            this.why = why;
            this.atMillis = atMillis;
        }
    }

    /**
     * Remembers that a lasting hold ended and why, so its owner can be told instead of finding it gone:
     * a hold that idled out under a test nothing read during took the test's chunks with it, and
     * nothing said so.
     */
    public void noteEnded(Hold hold, String why, long nowMillis) {
        ended.addLast(new Ended(hold.id, hold.caller, hold.chunks.size(), why, nowMillis));
        while (ended.size() > ENDED_KEPT) {
            ended.removeFirst();
        }
    }

    /** The lasting holds that ended lately, oldest first; those {@code key} covers, or all for null. */
    public List<Ended> recentlyEnded(@Nullable String key) {
        List<Ended> out = new ArrayList<>();
        for (Ended entry : ended) {
            if (key == null || covers(key, entry.caller)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** The remembered end of hold {@code id}, or null. */
    @Nullable
    public Ended endOf(String id) {
        for (Ended entry : ended) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

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
        version++;
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
        version++;
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

    /** Distinct chunks held by every agent of a caller's player (or by the caller alone, if it has none). */
    public int heldChunksOfGroup(String caller) {
        return placesIn(Scope.PLAYER, caller, false).size();
    }

    /** Distinct chunks held by one caller. */
    public int heldChunks(String caller) {
        return placesOf(caller).size();
    }

    /**
     * The group a caller belongs to. A companion agent holds under {@code player:<uuid>~<agent>} (#142):
     * its holds are its own to release, but its player's cap counts every agent of that player, since
     * the access is the player's. Any other caller is a group of one.
     */
    public static String groupOf(String caller) {
        int agent = caller.indexOf('~');
        return agent < 0 ? caller : caller.substring(0, agent);
    }

    /**
     * Whether {@code key} covers holds taken under {@code holdCaller}: an agent's key covers only its
     * own; a player's key ({@code player:<uuid>}, as a logout or a revoked access names it) covers every
     * agent of that player.
     */
    public static boolean covers(String key, String holdCaller) {
        return holdCaller.equals(key) || (key.indexOf('~') < 0 && groupOf(holdCaller).equals(key));
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

    // ------------------------------------------------------------------
    // Hold caps
    //
    // Three caps, one model. Each bounds the distinct chunks held by the holds in one scope:
    //
    //   caller  (chunks.maxHeldChunksPerCaller) the holds taken under one caller key: one agent on the
    //           endpoint, one agent of a companion player, or one background job;
    //   player  (chunks.maxHeldChunksPerPlayer) every agent of one companion player together; a caller
    //           that is not a player's agent has no player scope;
    //   server  (chunks.maxHeldChunks)          everyone.
    //
    // A new hold is checked against each scope the asker is in, counting what it would add to that
    // scope: the chunks no hold in the scope has yet. That one rule is what checkCaps, room and the
    // admission line all use, so the three can never disagree about whether something fits (#214).
    // ------------------------------------------------------------------

    public static final String LIMIT_TOTAL = "chunks.maxHeldChunks";
    public static final String LIMIT_PER_PLAYER = "chunks.maxHeldChunksPerPlayer";
    public static final String LIMIT_PER_CALLER = "chunks.maxHeldChunksPerCaller";

    /** The caps as configured. */
    public static final class Caps {

        public final int total;
        /** 0, or anything at or above {@link #total}: no player cap but the server's. */
        public final int perPlayer;
        /** 0 for none. */
        public final int perCaller;

        public Caps(int total, int perPlayer, int perCaller) {
            this.total = total;
            this.perPlayer = perPlayer;
            this.perCaller = perCaller;
        }

        /** The player cap that binds before the server's, or 0 when none does. */
        public int playerLimit() {
            return perPlayer > 0 && perPlayer < total ? perPlayer : 0;
        }

        /** The most chunks one caller could ever hold at once, with nobody else holding anything. */
        public int largestHold(String caller) {
            int most = total;
            if (perCaller > 0) {
                most = Math.min(most, perCaller);
            }
            if (isPlayerAgent(caller) && playerLimit() > 0) {
                most = Math.min(most, playerLimit());
            }
            return most;
        }
    }

    /** What one cap counts, narrowest first: the order caps are checked and reported in. */
    enum Scope {
        CALLER(LIMIT_PER_CALLER),
        PLAYER(LIMIT_PER_PLAYER),
        SERVER(LIMIT_TOTAL);

        final String limit;

        Scope(String limit) {
            this.limit = limit;
        }

        /** Whether holds taken under {@code holder} count toward {@code asker}'s cap of this scope. */
        boolean contains(String asker, String holder) {
            switch (this) {
                case CALLER:
                    return holder.equals(asker);
                case PLAYER:
                    return groupOf(holder).equals(groupOf(asker));
                default:
                    return true;
            }
        }

        /** This scope's cap for {@code asker}, or 0 when it has none. */
        int limitFor(String asker, Caps caps) {
            switch (this) {
                case CALLER:
                    return caps.perCaller;
                case PLAYER:
                    // A caller that is nobody's agent is a group of one: its player scope is its caller scope.
                    return isPlayerAgent(asker) ? caps.playerLimit() : 0;
                default:
                    return caps.total;
            }
        }
    }

    /** Whether {@code caller} is one agent of a companion player ({@code player:<uuid>~<agent>}). */
    public static boolean isPlayerAgent(String caller) {
        return caller.indexOf('~') >= 0;
    }

    /**
     * The distinct chunks held in one of {@code asker}'s scopes.
     *
     * @param afterRunningCalls leave out what only other callers' calls in progress hold: what the scope
     *                          holds once those calls end, which they do on their own and soon. The asker's
     *                          own calls are never left out — a {@code server_run_test} step runs inside one,
     *                          which cannot end until the step does (#132).
     */
    private Set<Place> placesIn(Scope scope, String asker, boolean afterRunningCalls) {
        Set<Place> places = new HashSet<>();
        for (Hold hold : holds.values()) {
            if (!scope.contains(asker, hold.caller)) {
                continue;
            }
            if (afterRunningCalls && hold.temporary && !hold.caller.equals(asker)) {
                continue;
            }
            for (long chunk : hold.chunks) {
                places.add(new Place(hold.dimension, chunk));
            }
        }
        return places;
    }

    private static int adding(Set<Place> held, int dimension, Set<Long> chunks) {
        int adding = 0;
        for (long chunk : chunks) {
            if (!held.contains(new Place(dimension, chunk))) {
                adding++;
            }
        }
        return adding;
    }

    /** When the first lasting hold in one of {@code asker}'s scopes ends if untouched, or Long.MAX_VALUE. */
    private long soonestLastingEnd(Scope scope, String asker) {
        long soonest = Long.MAX_VALUE;
        for (Hold hold : holds.values()) {
            if (!hold.temporary && scope.contains(asker, hold.caller)) {
                soonest = Math.min(soonest, hold.expiresAtMillis());
            }
        }
        return soonest;
    }

    /** Why a new hold would pass a cap: which one, and who holds what. */
    public static final class CapRefusal {

        /** The config key of the cap: {@link #LIMIT_PER_CALLER}, {@link #LIMIT_PER_PLAYER} or {@link #LIMIT_TOTAL}. */
        public final String limit;
        public final int cap;
        /** What the hold would add to the count that cap bounds. */
        public final int adding;
        /** What that cap counts now. */
        public final int scopeHeldChunks;
        /** Every chunk held, all callers. */
        public final int heldChunks;
        public final int callers;
        /** What the asker holds under its own key: the count its own listing shows. */
        public final int callerHeldChunks;
        /**
         * What the asker's player holds, every agent together. The same as {@link #callerHeldChunks}
         * for a caller that is nobody's agent.
         */
        public final int groupHeldChunks;
        /**
         * Whether the hold would fit once other callers' calls in progress, counted against this cap,
         * give their chunks back. Those end with their call, so it is worth waiting in line for; a cap
         * filled by lasting holds is not, since they end minutes away, if at all.
         */
        public final boolean mayClear;
        /**
         * Whether the asker's own calls in progress hold some of what this cap counts. Never waited
         * for: the asking call may be running inside one of them — a {@code server_run_test} step — and
         * that one cannot end until this one does (#132).
         */
        public final boolean ownCallsInProgress;
        /** When the first lasting hold this cap counts ends if untouched, or Long.MAX_VALUE. */
        public final long soonestLastingEndMillis;
        final Scope scope;

        CapRefusal(Scope scope, int cap, int adding, int scopeHeldChunks, int heldChunks, int callers,
                   int callerHeldChunks, int groupHeldChunks, boolean mayClear, boolean ownCallsInProgress,
                   long soonestLastingEndMillis) {
            this.scope = scope;
            this.limit = scope.limit;
            this.cap = cap;
            this.adding = adding;
            this.scopeHeldChunks = scopeHeldChunks;
            this.heldChunks = heldChunks;
            this.callers = callers;
            this.callerHeldChunks = callerHeldChunks;
            this.groupHeldChunks = groupHeldChunks;
            this.mayClear = mayClear;
            this.ownCallsInProgress = ownCallsInProgress;
            this.soonestLastingEndMillis = soonestLastingEndMillis;
        }

        public boolean perCaller() {
            return scope == Scope.CALLER;
        }

        public boolean perPlayer() {
            return scope == Scope.PLAYER;
        }

        /** The refusal as a caller reads it: which cap, what it counts, and the caller's own share. */
        public String message() {
            StringBuilder text = new StringBuilder();
            switch (scope) {
                case CALLER:
                    text.append("Hold cap reached for you (").append(limit).append(" = ").append(cap)
                        .append(", your own: other agents' holds do not count toward it): you hold ")
                        .append(callerHeldChunks).append(" chunk(s) and this needs ").append(adding)
                        .append(" more. Release a hold of yours (server_release_loaded) or work in smaller pieces.");
                    break;
                case PLAYER:
                    // Said as "you hold 510", a refusal sent an agent holding nothing to look for holds to
                    // release (#192): the count is every agent of the player's.
                    text.append("Hold cap reached for your player (").append(limit).append(" = ").append(cap)
                        .append(", shared by every agent on your connection): they hold ").append(groupHeldChunks)
                        .append(" chunk(s) between them, ").append(callerHeldChunks).append(" of them yours, and ")
                        .append("this needs ").append(adding).append(" more. server_keep_loaded op 'list' shows ")
                        .append("which agent holds what.");
                    break;
                default:
                    text.append("Server hold cap reached (").append(limit).append(" = ").append(cap).append("): ")
                        .append(heldChunks).append(" chunk(s) held by ").append(callers).append(" caller(s), ")
                        .append(callerHeldChunks).append(" of them yours, and this needs ").append(adding)
                        .append(" more. server_keep_loaded op 'list' shows who holds what.");
                    break;
            }
            if (mayClear) {
                text.append(" Calls still running hold enough of these to make room when they end: retry after ")
                    .append("busy.retryAfterMs.");
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
     * Whether {@code caller} may take a hold over {@code chunks} under every cap it is under. Each cap
     * counts what the hold would add to its own scope, so overlapping another caller's hold is free for
     * the server's cap but not for the caller's own. The narrowest cap passed is the one reported.
     *
     * @return null when it fits
     */
    @Nullable
    public CapRefusal checkCaps(String caller, int dimension, Set<Long> chunks, Caps caps) {
        return checkCaps(caller, dimension, chunks, caps, Collections.<Pending>emptyList());
    }

    /** A hold not taken yet but promised: room the admission line has reserved for a waiter. */
    public static final class Pending {

        final String caller;
        final int dimension;
        final Set<Long> chunks;

        public Pending(String caller, int dimension, Set<Long> chunks) {
            this.caller = caller;
            this.dimension = dimension;
            this.chunks = chunks;
        }
    }

    /**
     * As above, counting {@code reserved} as if their holds were already taken: whether this fits beside
     * the room the line has promised to others. A reservation is a call that will run and end, so it is
     * left out of what may clear like any other caller's call in progress.
     */
    @Nullable
    public CapRefusal checkCaps(String caller, int dimension, Set<Long> chunks, Caps caps, List<Pending> reserved) {
        for (Scope scope : Scope.values()) {
            int limit = scope.limitFor(caller, caps);
            if (limit <= 0) {
                continue;
            }
            Set<Place> held = placesIn(scope, caller, false);
            for (Pending pending : reserved) {
                if (scope.contains(caller, pending.caller)) {
                    for (long chunk : pending.chunks) {
                        held.add(new Place(pending.dimension, chunk));
                    }
                }
            }
            int adding = adding(held, dimension, chunks);
            if (held.size() + adding <= limit) {
                continue;
            }
            Set<Place> after = placesIn(scope, caller, true);
            boolean mayClear = after.size() < held.size() && after.size() + adding(after, dimension, chunks) <= limit;
            return new CapRefusal(scope, limit, adding, held.size(), heldChunks(), callers(), heldChunks(caller),
                heldChunksOfGroup(caller), mayClear, hasCallInProgress(caller), soonestLastingEnd(scope, caller));
        }
        return null;
    }

    /** As above, with the per-player cap left to the server's. For tests of the two-cap cases. */
    @Nullable
    public CapRefusal checkCaps(String caller, int dimension, Set<Long> chunks, int maxTotal, int maxPerCaller) {
        return checkCaps(caller, dimension, chunks, new Caps(maxTotal, 0, maxPerCaller));
    }

    /**
     * How many chunks nobody in any of its scopes holds yet {@code caller} may take now: the largest
     * fresh hold {@link #checkCaps} admits. Never negative.
     */
    public int room(String caller, Caps caps) {
        int room = Integer.MAX_VALUE;
        for (Scope scope : Scope.values()) {
            int limit = scope.limitFor(caller, caps);
            if (limit > 0) {
                room = Math.min(room, limit - placesIn(scope, caller, false).size());
            }
        }
        return Math.max(0, room);
    }

    /** Whether {@code caller} has a call in progress holding chunks. */
    public boolean hasCallInProgress(String caller) {
        for (Hold hold : holds.values()) {
            if (hold.temporary && hold.caller.equals(caller)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code asker} may renew or release a hold taken under {@code holder}. The endpoint is the
     * server's own operator, and its agents may manage any hold; a companion player only theirs, any
     * agent of theirs included, since the access is the player's.
     */
    public static boolean mayManage(boolean companion, String asker, String holder) {
        return !companion || groupOf(holder).equals(groupOf(asker));
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
            if (covers(caller, hold.caller) && (callsToo || !hold.temporary)) {
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
