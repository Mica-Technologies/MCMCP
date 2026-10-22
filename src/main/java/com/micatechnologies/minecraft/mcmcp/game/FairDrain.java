package com.micatechnologies.minecraft.mcmcp.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The queue behind the server's task pump (#133): any thread offers, one thread drains, taking turns
 * between callers within a time budget.
 *
 * <h2>Why not vanilla's queue</h2>
 *
 * {@code MinecraftServer.addScheduledTask} adds under a lock the server thread holds for as long as it
 * runs every queued task, so a worker offering one more waits, with no timeout, behind all the work
 * already there — and the server thread runs all of it in one tick however long that takes. Here an
 * offer is a lock-free add, and a drain stops starting tasks once its budget is spent.
 *
 * <h2>Fairness</h2>
 *
 * Tasks wait in one line per caller. A drain takes one task from the caller at the front, then sends
 * that caller to the back, so an agent with a hundred slices queued and one with a single read share
 * the tick turn about, and the order carries over from tick to tick. A drain always runs at least one
 * task when there is one, so nothing starves however small the budget. A task that turns out to have
 * been abandoned by its caller costs nothing and does not use a turn.
 *
 * <h2>Who spends the tick</h2>
 *
 * Each drain notes how long each caller's tasks ran, and keeps a moving average of milliseconds per
 * tick per caller (about the last 20 ticks), published for readers on other threads.
 *
 * <p>Pure: no Minecraft class, and the clock is injected, so it is unit-tested as plain Java.
 */
public final class FairDrain<T> {

    /** Runs one task on the draining thread. */
    public interface Runner<T> {

        /** @return whether it did any work: false for a task its caller had already given up on */
        boolean run(T task);
    }

    /** A caller's share of the tick: its moving average in milliseconds per tick. */
    public static final class Share {

        public final String key;
        public final double msPerTick;

        Share(String key, double msPerTick) {
            this.key = key;
            this.msPerTick = msPerTick;
        }
    }

    private static final class Entry<T> {

        final String key;
        final T task;

        Entry(String key, T task) {
            this.key = key;
            this.task = task;
        }
    }

    /** Weight of the newest tick in each caller's moving average: about a 20-tick window. */
    static final double ALPHA = 0.05D;

    /** A caller whose average falls below this, with nothing queued, is forgotten. */
    private static final double FORGET_MS = 0.005D;

    private final LongSupplier nanoClock;
    private final ConcurrentLinkedQueue<Entry<T>> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicInteger depth = new AtomicInteger();

    /** Draining thread only: each caller's line, the caller to serve next first. */
    private final LinkedHashMap<String, ArrayDeque<T>> waiting = new LinkedHashMap<>();
    /** Draining thread only. */
    private final Map<String, Double> averages = new HashMap<>();

    private volatile List<Share> published = Collections.emptyList();

    public FairDrain(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** Any thread: queues a task for {@code key}. Never blocks. */
    public void offer(String key, T task) {
        inbound.add(new Entry<>(key, task));
        depth.incrementAndGet();
    }

    /** Tasks queued and not yet taken, any thread. */
    public int depth() {
        return Math.max(0, depth.get());
    }

    /**
     * Draining thread: runs queued tasks, one per caller in turn, until {@code maxTasks} have run or
     * {@code budgetNanos} has passed since it began. At least one task runs when any is queued; one
     * already started always finishes, since nothing can stop it halfway.
     *
     * @return how many tasks ran
     */
    public int drain(long budgetNanos, int maxTasks, Runner<T> runner) {
        Map<String, Long> spent = new HashMap<>();
        long started = nanoClock.getAsLong();
        int ran = 0;
        take();
        while (!waiting.isEmpty() && ran < Math.max(1, maxTasks)
            && (ran == 0 || nanoClock.getAsLong() - started < budgetNanos)) {
            Iterator<Map.Entry<String, ArrayDeque<T>>> front = waiting.entrySet().iterator();
            Map.Entry<String, ArrayDeque<T>> next = front.next();
            String key = next.getKey();
            ArrayDeque<T> line = next.getValue();
            T task = line.pollFirst();
            front.remove();
            if (!line.isEmpty()) {
                // To the back of the turn order.
                waiting.put(key, line);
            }
            depth.decrementAndGet();
            long before = nanoClock.getAsLong();
            boolean worked = runner.run(task);
            if (worked) {
                ran++;
                spent.merge(key, nanoClock.getAsLong() - before, Long::sum);
            }
            take();
        }
        account(spent);
        return ran;
    }

    /** Draining thread: empties the queue without running anything, for whoever must fail what was in it. */
    public List<T> clear() {
        take();
        List<T> all = new ArrayList<>();
        for (ArrayDeque<T> line : waiting.values()) {
            all.addAll(line);
        }
        waiting.clear();
        depth.addAndGet(-all.size());
        return all;
    }

    /** Callers' moving averages, the biggest first. Any thread. */
    public List<Share> shares() {
        return published;
    }

    /** The sum of every caller's moving average: MCMCP's milliseconds per tick. Any thread. */
    public double totalMsPerTick() {
        double total = 0.0D;
        for (Share share : published) {
            total += share.msPerTick;
        }
        return total;
    }

    /** Moves what other threads offered into the callers' lines, keeping the turn order of the rest. */
    private void take() {
        Entry<T> entry;
        while ((entry = inbound.poll()) != null) {
            ArrayDeque<T> line = waiting.get(entry.key);
            if (line == null) {
                line = new ArrayDeque<>();
                waiting.put(entry.key, line);
            }
            line.addLast(entry.task);
        }
    }

    private void account(Map<String, Long> spent) {
        Map<String, Double> next = new HashMap<>();
        for (Map.Entry<String, Double> old : averages.entrySet()) {
            next.put(old.getKey(), old.getValue() * (1.0D - ALPHA));
        }
        for (Map.Entry<String, Long> tick : spent.entrySet()) {
            next.merge(tick.getKey(), ALPHA * tick.getValue() / 1.0e6D, Double::sum);
        }
        averages.clear();
        List<Share> shares = new ArrayList<>();
        for (Map.Entry<String, Double> average : next.entrySet()) {
            if (average.getValue() < FORGET_MS && !waiting.containsKey(average.getKey())) {
                continue;
            }
            averages.put(average.getKey(), average.getValue());
            shares.add(new Share(average.getKey(), average.getValue()));
        }
        shares.sort((a, b) -> Double.compare(b.msPerTick, a.msPerTick));
        published = Collections.unmodifiableList(shares);
    }
}
