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

        /** When this hold ends if nothing touches it: idle timeout or absolute lease, whichever first. */
        public long expiresAtMillis() {
            return Math.min(lastUsedMillis + idleMillis, createdMillis + maxMillis);
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
        Set<Place> places = new HashSet<>();
        for (Hold hold : holds.values()) {
            if (hold.caller.equals(caller)) {
                for (long chunk : hold.chunks) {
                    places.add(new Place(hold.dimension, chunk));
                }
            }
        }
        return places.size();
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

    /** Holds whose idle timer or lease has run out. Temporary holds end with their call, not here. */
    public List<String> expired(long nowMillis) {
        List<String> ids = new ArrayList<>();
        for (Hold hold : holds.values()) {
            if (!hold.temporary && nowMillis >= hold.expiresAtMillis()) {
                ids.add(hold.id);
            }
        }
        return ids;
    }

    /** One caller's holds. */
    public List<String> ownedBy(String caller) {
        List<String> ids = new ArrayList<>();
        for (Hold hold : holds.values()) {
            if (hold.caller.equals(caller)) {
                ids.add(hold.id);
            }
        }
        return ids;
    }

    /**
     * The order to give holds back in when the server is struggling: the longest-idle first, then the
     * oldest. Calls in progress (temporary holds) last of all — they end soon on their own.
     */
    public List<String> shedOrder() {
        List<Hold> sorted = new ArrayList<>(holds.values());
        sorted.sort(Comparator.<Hold>comparingInt(hold -> hold.temporary ? 1 : 0)
            .thenComparingLong(hold -> hold.lastUsedMillis)
            .thenComparingLong(hold -> hold.createdMillis));
        List<String> ids = new ArrayList<>();
        for (Hold hold : sorted) {
            ids.add(hold.id);
        }
        return ids;
    }
}
