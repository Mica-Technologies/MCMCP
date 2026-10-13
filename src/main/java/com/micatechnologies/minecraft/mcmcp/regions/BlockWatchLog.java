package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The record one {@code server_watch_blocks} keeps: for each watched position, every tick its block
 * changed and what it changed from and to (#68).
 *
 * <p>A block is described by a key string the caller builds ({@code minecraft:wool[color=red]}, or
 * only the properties it asked about), and a change is recorded only when the key differs from the
 * last one seen at that position. Keys go into a palette, so a signal head flipping between three
 * colours a hundred times costs three strings and a hundred rows of ints.
 *
 * <p>Written by the server thread, read by tool calls on HTTP workers, so every method is
 * synchronized. No Minecraft class here: it is plain data and is tested as such.
 */
public final class BlockWatchLog {

    /** Ints per recorded change: tick offset from the start, position index, from key, to key. */
    private static final int STRIDE = 4;

    private final String id;
    private final int dimension;
    private final int[] positions;
    private final long startTick;
    private final long endTick;
    private final int maxChanges;

    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    /** The palette index each position last held. */
    private final int[] current;
    private final int[] initial;

    private int[] changes = new int[STRIDE * 64];
    private int count;
    private boolean truncated;
    private long lastTick;
    @Nullable
    private String stopped;

    /**
     * @param positions x, y, z of each watched position, flattened
     * @param initialKeys what each position holds at {@code startTick}
     */
    public BlockWatchLog(String id, int dimension, int[] positions, String[] initialKeys, long startTick,
                         long endTick, int maxChanges) {
        if (positions.length != initialKeys.length * 3) {
            throw new IllegalArgumentException("one initial key per position");
        }
        this.id = id;
        this.dimension = dimension;
        this.positions = positions.clone();
        this.startTick = startTick;
        this.endTick = endTick;
        this.maxChanges = maxChanges;
        this.current = new int[initialKeys.length];
        for (int i = 0; i < initialKeys.length; i++) {
            current[i] = index(initialKeys[i]);
        }
        this.initial = current.clone();
        this.lastTick = startTick;
    }

    public String id() {
        return id;
    }

    public int dimension() {
        return dimension;
    }

    public int size() {
        return current.length;
    }

    public long endTick() {
        return endTick;
    }

    /** Position {@code i}'s x, y and z. */
    public int x(int i) {
        return positions[i * 3];
    }

    public int y(int i) {
        return positions[i * 3 + 1];
    }

    public int z(int i) {
        return positions[i * 3 + 2];
    }

    /** Records that position {@code i} holds {@code key} at {@code tick}, if that is a change. */
    public synchronized void sample(long tick, int i, String key) {
        if (stopped != null) {
            return;
        }
        int to = index(key);
        if (to == current[i]) {
            return;
        }
        if (count == maxChanges) {
            // The record stops growing rather than dropping its oldest rows: the start of a timeline
            // is what explains the rest of it.
            truncated = true;
            stop(tick, "the change limit was reached");
            return;
        }
        if ((count + 1) * STRIDE > changes.length) {
            changes = Arrays.copyOf(changes, changes.length * 2);
        }
        int at = count * STRIDE;
        changes[at] = (int) (tick - startTick);
        changes[at + 1] = i;
        changes[at + 2] = current[i];
        changes[at + 3] = to;
        current[i] = to;
        count++;
    }

    /** Notes that every position has been sampled up to {@code tick}. */
    public synchronized void sampled(long tick) {
        if (stopped == null) {
            lastTick = Math.max(lastTick, tick);
        }
    }

    /** Ends the watch. The first reason given is the one kept. */
    public synchronized void stop(long tick, String reason) {
        if (stopped == null) {
            lastTick = Math.max(lastTick, tick);
            stopped = reason;
        }
    }

    public synchronized boolean isDone() {
        return stopped != null;
    }

    public synchronized int changeCount() {
        return count;
    }

    /**
     * The record as JSON: {@code changes} rows are {@code [tick, x, y, z, from, to]}, with {@code from}
     * and {@code to} indexes into {@code palette}.
     *
     * @param fromChange the first change to include, to read only what is new since the last read
     * @param withInitial include each position's starting block, as {@code [x, y, z, paletteIndex]}
     */
    public synchronized JsonObject toJson(int fromChange, boolean withInitial) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("dimension", dimension);
        json.addProperty("startTick", startTick);
        json.addProperty("endTick", endTick);
        json.addProperty("sampledTo", lastTick);
        json.addProperty("done", stopped != null);
        if (stopped != null) {
            json.addProperty("stopReason", stopped);
        }
        json.addProperty("positions", current.length);
        json.addProperty("changeCount", count);
        if (truncated) {
            json.addProperty("truncated", true);
        }
        int first = Math.max(0, Math.min(fromChange, count));
        json.addProperty("fromChange", first);
        json.addProperty("nextChange", count);
        JsonArray rows = new JsonArray();
        for (int c = first; c < count; c++) {
            int at = c * STRIDE;
            int i = changes[at + 1];
            JsonArray row = new JsonArray();
            row.add(startTick + changes[at]);
            row.add(x(i));
            row.add(y(i));
            row.add(z(i));
            row.add(changes[at + 2]);
            row.add(changes[at + 3]);
            rows.add(row);
        }
        if (withInitial) {
            JsonArray start = new JsonArray();
            for (int i = 0; i < initial.length; i++) {
                JsonArray row = new JsonArray();
                row.add(x(i));
                row.add(y(i));
                row.add(z(i));
                row.add(initial[i]);
                start.add(row);
            }
            json.add("initial", start);
        }
        JsonArray names = new JsonArray();
        for (String key : palette) {
            names.add(key);
        }
        json.add("palette", names);
        json.add("changes", rows);
        return json;
    }

    private int index(String key) {
        Integer index = paletteIndex.get(key);
        if (index == null) {
            index = palette.size();
            palette.add(key);
            paletteIndex.put(key, index);
        }
        return index;
    }
}
