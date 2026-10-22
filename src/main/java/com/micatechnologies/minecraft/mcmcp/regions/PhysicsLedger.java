package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bookkeeping of a bulk write that holds gravity blocks and fluids still until it ends (#118).
 *
 * <p>Sand falls and water flows on a scheduled tick, never inside the write: the write only schedules
 * the tick (a falling block's {@code onBlockAdded} or {@code neighborChanged}, a still fluid turning
 * flowing). The write's game-thread slices have world ticks between them, and those ticks are where an
 * excavation's sand fell before the slab above it was written. So the Forge half takes those ticks out
 * of the world's queue at the end of every slice, and this ledger keeps them until the call ends, along
 * with the cells the write changed and the cells found, at the end, that would still move.
 *
 * <p>Positions are packed as {@code BlockPos.toLong} packs them. {@code B} is the block type, kept
 * generic so this class names nothing of Minecraft's.
 *
 * @param <B> the block type of a held tick
 */
public final class PhysicsLedger<B> {

    /** Positions listed in a result. */
    public static final int SAMPLED = 10;

    private static final int X_BITS = 26;
    private static final int Z_BITS = 26;
    private static final int Y_BITS = 64 - X_BITS - Z_BITS;
    private static final int Y_SHIFT = Z_BITS;
    private static final int X_SHIFT = Y_SHIFT + Y_BITS;
    private static final long X_MASK = (1L << X_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final long Z_MASK = (1L << Z_BITS) - 1L;

    /** What would move. */
    public enum Kind {
        GRAVITY, FLUID
    }

    /** A tick taken out of the world's queue: where, for which block, when it was due and its priority. */
    public static final class Held<B> {
        public final int x;
        public final int y;
        public final int z;
        public final B block;
        public final long scheduledTime;
        public final int priority;

        Held(int x, int y, int z, B block, long scheduledTime, int priority) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.scheduledTime = scheduledTime;
            this.priority = priority;
        }
    }

    private final int[] box;
    private long[] changed = new long[256];
    private int changedSize;
    private final Map<Long, List<Held<B>>> held = new LinkedHashMap<>();
    private int heldCount;
    private final Set<Long> movers = new HashSet<>();
    private int gravity;
    private int fluid;
    private final JsonArray sample = new JsonArray();

    /**
     * @param minX the write's box, inclusive; the ledger covers it and one cell around it, where a
     *             block resting on or flowing into a written cell sits
     */
    public PhysicsLedger(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.box = new int[] {minX - 1, Math.max(0, minY - 1), minZ - 1, maxX + 1, Math.min(255, maxY + 1), maxZ + 1};
    }

    /** The box held, one cell larger than the write's on every side: min x, y, z, max x, y, z, inclusive. */
    public int[] box() {
        return box.clone();
    }

    public boolean inBox(int x, int y, int z) {
        return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
    }

    public static long pack(int x, int y, int z) {
        return ((long) x & X_MASK) << X_SHIFT | ((long) y & Y_MASK) << Y_SHIFT | (long) z & Z_MASK;
    }

    public static int x(long packed) {
        return (int) (packed << (64 - X_SHIFT - X_BITS) >> (64 - X_BITS));
    }

    public static int y(long packed) {
        return (int) (packed << (64 - Y_SHIFT - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long packed) {
        return (int) (packed << (64 - Z_BITS) >> (64 - Z_BITS));
    }

    /**
     * The delay to schedule a held tick with again: what was left of it, never negative. A tick that
     * fell due while it was held runs on the next tick.
     */
    public static int delayFrom(long scheduledTime, long now) {
        long left = scheduledTime - now;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, left));
    }

    /** The write changed this cell. */
    public void changed(int x, int y, int z) {
        if (changedSize == changed.length) {
            changed = Arrays.copyOf(changed, changed.length * 2);
        }
        changed[changedSize++] = pack(x, y, z);
    }

    /**
     * Keeps a tick taken out of the world's queue. A second tick for the same cell and block keeps the
     * first, as the world's own queue does.
     */
    public void hold(int x, int y, int z, B block, long scheduledTime, int priority) {
        List<Held<B>> at = held.computeIfAbsent(pack(x, y, z), k -> new ArrayList<>(1));
        for (Held<B> existing : at) {
            if (existing.block.equals(block)) {
                return;
            }
        }
        at.add(new Held<>(x, y, z, block, scheduledTime, priority));
        heldCount++;
    }

    public int heldCount() {
        return heldCount;
    }

    /** Every held tick, in the order it was taken. */
    public List<Held<B>> held() {
        List<Held<B>> all = new ArrayList<>(heldCount);
        for (List<Held<B>> at : held.values()) {
            all.addAll(at);
        }
        return all;
    }

    /**
     * The cells to look at once the write is done, sorted and each once: every changed cell and every
     * held tick's cell. Each one's neighbours are looked at too; the caller does that, since a cell can
     * only move if it or a neighbour changed.
     */
    public long[] candidates() {
        long[] all = Arrays.copyOf(changed, changedSize + held.size());
        int n = changedSize;
        for (long key : held.keySet()) {
            all[n++] = key;
        }
        Arrays.sort(all, 0, n);
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            if (distinct == 0 || all[i] != all[distinct - 1]) {
                all[distinct++] = all[i];
            }
        }
        return Arrays.copyOf(all, distinct);
    }

    /**
     * A cell found that would still move. Counted once however many times it is found.
     *
     * @return true the first time
     */
    public boolean mover(Kind kind, int x, int y, int z, String block) {
        if (!movers.add(pack(x, y, z))) {
            return false;
        }
        if (kind == Kind.GRAVITY) {
            gravity++;
        }
        else {
            fluid++;
        }
        if (sample.size() < SAMPLED) {
            JsonArray item = new JsonArray();
            item.add(x);
            item.add(y);
            item.add(z);
            item.add(block);
            sample.add(item);
        }
        return true;
    }

    /** The cells found that would move, packed, in no order. */
    public long[] movers() {
        long[] all = new long[movers.size()];
        int i = 0;
        for (long key : movers) {
            all[i++] = key;
        }
        return all;
    }

    public int gravityUnsupported() {
        return gravity;
    }

    public int fluidsExposed() {
        return fluid;
    }

    /**
     * The result's {@code physics} object.
     *
     * @param restored held ticks put back in the world's queue
     * @param woken    movers given a tick of their own, having none held
     * @param wake     whether movers without a held tick were given one (the write's neighbour updates
     *                 were on), or only reported
     */
    public JsonObject toJson(int restored, int woken, boolean wake) {
        JsonObject json = new JsonObject();
        json.addProperty("ticksHeld", heldCount);
        json.addProperty("ticksRestored", restored);
        json.addProperty("gravityUnsupported", gravity);
        json.addProperty("fluidsExposed", fluid);
        if (woken > 0) {
            json.addProperty("woken", woken);
        }
        if (sample.size() > 0) {
            json.add("sample", sample);
            json.addProperty("note", wake
                ? "These move from the next tick on: the hold lasts one call."
                : "Neighbour updates were off, so only those with a held tick move; the rest stay until a "
                    + "neighbour changes.");
        }
        return json;
    }
}
