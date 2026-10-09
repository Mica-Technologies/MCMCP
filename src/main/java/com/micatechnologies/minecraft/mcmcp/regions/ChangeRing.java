package com.micatechnologies.minecraft.mcmcp.regions;

import java.util.ArrayList;
import java.util.List;

/**
 * The last N block changes on a server, numbered, so "what changed in this box since I last looked"
 * is one query instead of a re-read.
 *
 * <p>A fixed-size ring: when it is full the oldest change is overwritten. Each change carries a
 * sequence number that only grows, and a token is just a sequence number, so a reader can tell
 * exactly whether the ring still reaches back to its token or has lost some of what happened since
 * ({@code overflow}), and fall back to a re-read when it has.
 *
 * <p>Pure: written from the server thread, read under its lock from tool workers.
 */
public final class ChangeRing {

    /** One change. */
    public static final class Change {

        public final long seq;
        public final int dimension;
        public final int x;
        public final int y;
        public final int z;
        public final int stateId;
        /** Who or what: {@code player:<name>}, or {@code other}. */
        public final String source;

        Change(long seq, int dimension, int x, int y, int z, int stateId, String source) {
            this.seq = seq;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.stateId = stateId;
            this.source = source;
        }
    }

    /** What a query found. */
    public static final class Result {

        public final List<Change> changes;
        /** The token to pass next time: everything up to here has been seen. */
        public final long token;
        /** The ring no longer reaches back to the token asked for: some changes since then are gone. */
        public final boolean overflow;
        /** More matched than the limit allowed; ask again from {@link #token}. */
        public final boolean truncated;

        Result(List<Change> changes, long token, boolean overflow, boolean truncated) {
            this.changes = changes;
            this.token = token;
            this.overflow = overflow;
            this.truncated = truncated;
        }
    }

    private final Change[] ring;
    private long nextSeq = 1L;

    public ChangeRing(int capacity) {
        this.ring = new Change[Math.max(1, capacity)];
    }

    public synchronized void add(int dimension, int x, int y, int z, int stateId, String source) {
        long seq = nextSeq++;
        ring[(int) (seq % ring.length)] = new Change(seq, dimension, x, y, z, stateId, source);
    }

    /** The token for "now": a query from it returns only what happens after this call. */
    public synchronized long token() {
        return nextSeq - 1L;
    }

    /** The oldest sequence number still held. */
    public synchronized long oldest() {
        return Math.max(1L, nextSeq - ring.length);
    }

    /**
     * Changes after {@code since} inside the box, oldest first, at most {@code limit} of them.
     * {@code exclude}, when not null, drops changes whose source equals it.
     */
    public synchronized Result since(long since, int dimension, int minX, int minY, int minZ, int maxX,
                                     int maxY, int maxZ, int limit, String exclude) {
        long first = Math.max(since + 1L, oldest());
        boolean overflow = since + 1L < oldest() && since < nextSeq - 1L;
        List<Change> found = new ArrayList<>();
        long last = since;
        boolean truncated = false;
        for (long seq = first; seq < nextSeq; seq++) {
            Change change = ring[(int) (seq % ring.length)];
            if (change == null || change.seq != seq) {
                continue;
            }
            last = seq;
            if (change.dimension != dimension || change.x < minX || change.x > maxX || change.y < minY
                || change.y > maxY || change.z < minZ || change.z > maxZ) {
                continue;
            }
            if (exclude != null && exclude.equals(change.source)) {
                continue;
            }
            if (found.size() >= limit) {
                truncated = true;
                last = seq - 1L;
                break;
            }
            found.add(change);
        }
        long token = truncated ? last : nextSeq - 1L;
        return new Result(found, token, overflow, truncated);
    }

    public int capacity() {
        return ring.length;
    }
}
