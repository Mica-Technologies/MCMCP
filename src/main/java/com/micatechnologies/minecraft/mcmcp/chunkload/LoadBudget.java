package com.micatechnologies.minecraft.mcmcp.chunkload;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * How many chunk loads MCMCP may cause right now: a per-tick allowance, and sliding one-minute
 * budgets for the whole server and for each caller.
 *
 * <p>Only real loads are counted. A chunk already in memory — near a player, held by anyone, forced
 * by another mod — costs the server nothing to read, so it costs nothing here either.
 *
 * <p>Pure and single-threaded: the governor calls it from the server thread only.
 */
public final class LoadBudget {

    private static final long MINUTE_MILLIS = 60_000L;

    private final Deque<long[]> all = new ArrayDeque<>();
    private final Map<String, Deque<long[]>> byCaller = new HashMap<>();
    private int usedThisTick;

    /** Starts a new tick's allowance. */
    public void newTick() {
        usedThisTick = 0;
    }

    /**
     * How many of {@code wanted} loads {@code caller} may make now.
     *
     * @param perTick the tick's allowance, already reduced for a server under load
     */
    public int allowance(String caller, int wanted, long nowMillis, int perTick, int perMinute,
                         int perMinutePerCaller) {
        expire(nowMillis);
        int tickLeft = Math.max(0, perTick - usedThisTick);
        int minuteLeft = Math.max(0, perMinute - count(all));
        Deque<long[]> mine = byCaller.get(caller);
        int callerLeft = Math.max(0, perMinutePerCaller - (mine == null ? 0 : count(mine)));
        return Math.min(wanted, Math.min(tickLeft, Math.min(minuteLeft, callerLeft)));
    }

    /** Records {@code loads} made by {@code caller}. */
    public void record(String caller, int loads, long nowMillis) {
        if (loads <= 0) {
            return;
        }
        usedThisTick += loads;
        all.addLast(new long[] {nowMillis, loads});
        byCaller.computeIfAbsent(caller, key -> new ArrayDeque<>()).addLast(new long[] {nowMillis, loads});
    }

    /** Loads in the last minute, server-wide. */
    public int lastMinute(long nowMillis) {
        expire(nowMillis);
        return count(all);
    }

    /** Loads in the last minute by {@code caller}. */
    public int lastMinute(String caller, long nowMillis) {
        expire(nowMillis);
        Deque<long[]> mine = byCaller.get(caller);
        return mine == null ? 0 : count(mine);
    }

    /**
     * When the per-minute budgets free up enough for one more load, in milliseconds from now: the
     * basis of a {@code retryAfterMs} that is measured, not guessed.
     */
    public long millisUntilRoom(String caller, long nowMillis, int perMinute, int perMinutePerCaller) {
        expire(nowMillis);
        long wait = 0L;
        if (count(all) >= perMinute && !all.isEmpty()) {
            wait = Math.max(wait, all.peekFirst()[0] + MINUTE_MILLIS - nowMillis);
        }
        Deque<long[]> mine = byCaller.get(caller);
        if (mine != null && count(mine) >= perMinutePerCaller && !mine.isEmpty()) {
            wait = Math.max(wait, mine.peekFirst()[0] + MINUTE_MILLIS - nowMillis);
        }
        return Math.max(0L, wait);
    }

    private void expire(long nowMillis) {
        long cutoff = nowMillis - MINUTE_MILLIS;
        while (!all.isEmpty() && all.peekFirst()[0] <= cutoff) {
            all.pollFirst();
        }
        byCaller.values().removeIf(deque -> {
            while (!deque.isEmpty() && deque.peekFirst()[0] <= cutoff) {
                deque.pollFirst();
            }
            return deque.isEmpty();
        });
    }

    private static int count(Deque<long[]> entries) {
        int total = 0;
        for (long[] entry : entries) {
            total += (int) entry[1];
        }
        return total;
    }
}
