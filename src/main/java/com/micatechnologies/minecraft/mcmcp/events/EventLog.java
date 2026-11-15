package com.micatechnologies.minecraft.mcmcp.events;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/**
 * A bounded ring of {@link GameEvent}s with a sequence number per event, and a blocking wait for
 * the next ones a filter matches (#201).
 *
 * <h2>Why a cursor, not "what is new"</h2>
 *
 * Polling chat for hours lost lines whenever more arrived between two reads than one read
 * returned, and de-duplicated by text, so two identical messages became one. Here every event has
 * a {@code seq}, a waiter says the last one it saw, and the answer always carries the cursor for
 * the next call ({@code next}). Nothing between two calls is skipped unless it fell off the ring,
 * and then the answer says how many did ({@code missed}).
 *
 * <p>{@code next} moves past events the filter did not match as well. A waiter for {@code @Claude}
 * on a busy server would otherwise rescan the same few hundred lines on every call.
 *
 * <h2>The stream id</h2>
 *
 * Seqs restart at 1 with the game. A cursor kept across a restart would wait until the new game
 * counted past it, or skip everything before that. So each log has a random {@code stream} id; a
 * waiter that names a different one is told {@code reset} and given the ring from its start.
 *
 * <p>Appends come from the game thread and never block on a waiter; waiters block on HTTP or link
 * worker threads, never on the game thread.
 */
public final class EventLog {

    /** How many events a log keeps. A few hundred covers a long gap between waits on a busy server. */
    public static final int DEFAULT_CAPACITY = 512;

    /** How often a waiter wakes to check its deadline and cancellation, at most. */
    static final long WAKE_MILLIS = 250L;

    private static final Map<String, EventLog> BY_SIDE = new ConcurrentHashMap<>();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final int capacity;
    private final LongSupplier clock;
    private final String stream;
    private final ArrayDeque<GameEvent> ring;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long lastSeq;

    public EventLog() {
        this(DEFAULT_CAPACITY, System::currentTimeMillis);
    }

    public EventLog(int capacity, LongSupplier clock) {
        this.capacity = Math.max(1, capacity);
        this.clock = clock;
        this.ring = new ArrayDeque<>(this.capacity);
        this.stream = String.format("%08x", RANDOM.nextInt());
    }

    /** The log for one side ({@code client} or {@code server}), created on first use. */
    public static EventLog forSide(String sideId) {
        return BY_SIDE.computeIfAbsent(sideId, id -> new EventLog());
    }

    public String getStream() {
        return stream;
    }

    /** Called after every append, outside the lock. For resource-updated notifications; must not block. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Records an event and wakes every waiter. */
    public GameEvent append(GameEvent.Draft draft) {
        GameEvent event;
        synchronized (this) {
            event = new GameEvent(++lastSeq, clock.getAsLong(), draft);
            if (ring.size() >= capacity) {
                ring.removeFirst();
            }
            ring.addLast(event);
            notifyAll();
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            }
            catch (RuntimeException ignored) {
                // A listener is a notification fan-out; one that fails must not cost the event.
            }
        }
        return event;
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    /** The newest {@code limit} events, oldest first. */
    public synchronized List<GameEvent> recent(int limit) {
        List<GameEvent> all = new ArrayList<>(ring);
        return new ArrayList<>(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    /**
     * Asked between waits: return false to stop waiting now (the caller cancelled). Also where the
     * caller sends its progress heartbeat, which a long wait through the orchestrator needs.
     */
    public interface Waiter {
        boolean keepWaiting();
    }

    /**
     * Waits until at least one event after the cursor matches, or the timeout passes.
     *
     * @param since   the last seq the caller saw; null for "only what happens from now on"
     * @param stream  the stream id {@code since} belongs to, or null if unknown
     * @param timeoutMillis 0 to answer at once with whatever is already there
     */
    public Batch await(@Nullable Long since, @Nullable String stream, EventFilter filter, int max,
        long timeoutMillis, Waiter waiter) throws InterruptedException {
        int limit = Math.max(1, max);
        long deadline = clock.getAsLong() + Math.max(0L, timeoutMillis);
        boolean reset = false;
        long cursor;
        long missed = 0;
        synchronized (this) {
            if (stream != null && !stream.isEmpty() && !stream.equals(this.stream)) {
                reset = true;
                cursor = 0;
            }
            else if (since == null) {
                cursor = lastSeq;
            }
            else if (since > lastSeq) {
                // A cursor from the future can only be from an earlier game: same as a changed stream.
                reset = true;
                cursor = 0;
            }
            else {
                cursor = Math.max(0L, since);
            }
            long oldest = ring.isEmpty() ? lastSeq + 1 : ring.peekFirst().getSeq();
            if (!reset && oldest > cursor + 1) {
                missed = oldest - cursor - 1;
            }
        }

        while (true) {
            List<GameEvent> matched = new ArrayList<>();
            long next;
            synchronized (this) {
                next = cursor;
                for (GameEvent event : ring) {
                    if (event.getSeq() <= cursor) {
                        continue;
                    }
                    if (filter.matches(event)) {
                        if (matched.size() >= limit) {
                            break;
                        }
                        matched.add(event);
                    }
                    next = event.getSeq();
                }
                // Past the end of the ring: nothing newer than lastSeq exists, so start there next time.
                if (matched.size() < limit) {
                    next = Math.max(next, lastSeq);
                }
                if (matched.isEmpty()) {
                    long remaining = deadline - clock.getAsLong();
                    if (remaining <= 0) {
                        return new Batch(this.stream, next, matched, true, missed, reset);
                    }
                    cursor = next;
                    wait(Math.min(remaining, WAKE_MILLIS));
                }
            }
            if (!matched.isEmpty()) {
                return new Batch(this.stream, next, matched, false, missed, reset);
            }
            if (!waiter.keepWaiting()) {
                return new Batch(this.stream, cursor, Collections.<GameEvent>emptyList(), true, missed, reset);
            }
        }
    }

    /** One answer to {@link #await}. */
    public static final class Batch {

        public final String stream;
        /** Pass as {@code since} on the next call. */
        public final long next;
        public final List<GameEvent> events;
        /** True when nothing matched before the timeout (or the wait was cancelled). */
        public final boolean timedOut;
        /** Events, matching or not, that fell off the ring after the cursor before they were read. */
        public final long missed;
        /** True when the cursor was from another stream: the game restarted since the caller's last call. */
        public final boolean reset;

        Batch(String stream, long next, List<GameEvent> events, boolean timedOut, long missed, boolean reset) {
            this.stream = stream;
            this.next = next;
            this.events = events;
            this.timedOut = timedOut;
            this.missed = missed;
            this.reset = reset;
        }
    }
}
