package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The two space checks a builder ran on every building as its own scripts: is the inside sealed, and can
 * a person walk where they should and only there (#138 plan, Phase 1; ported from the UIA builder's
 * {@code watertight.py} and fit-out walk checks, and tested against their own fixtures).
 *
 * <p><b>Enclosure.</b> Interior air is a 6-connected flood from the seeds through {@link VoxelGrid#OPEN}
 * cells only: a door, a pane, glass, a fence, a slab is closed, which is what makes them designed
 * openings rather than holes. A cell is <em>outside</em> when it has open sky straight up, or a clear
 * line of open cells to the box edge along x or z, or lies in a designed open zone (a terrace, an open
 * deck). A leak is an interior cell next to an outside one; leaks are reported as 26-connected
 * clusters, largest first.
 *
 * <p><b>Walk.</b> A person stands in a cell when it and the {@code headroom - 1} cells above are open (or
 * an open door) and the cell below is solid. From a cell they move to one of the four neighbours, at
 * the same height first, then up to {@code step} up, then down, taking the first that is standable and
 * not yet reached. The order is the builder's, so the counts match its checks exactly.
 *
 * <p>Pure: a {@link VoxelGrid} in, counts and clusters out.
 */
public final class SpaceChecks {

    private SpaceChecks() {
    }

    // ------------------------------------------------------------------
    // Enclosure
    // ------------------------------------------------------------------

    /** What an enclosure flood found. */
    public static final class Enclosure {
        public final BitSet interior;
        public final BitSet leaks;
        public final int seedsUsed;
        public final List<int[]> seedsNotOpen;

        Enclosure(BitSet interior, BitSet leaks, int seedsUsed, List<int[]> seedsNotOpen) {
            this.interior = interior;
            this.leaks = leaks;
            this.seedsUsed = seedsUsed;
            this.seedsNotOpen = seedsNotOpen;
        }
    }

    /**
     * @param openZones boxes {x1, y1, z1, x2, y2, z2} whose open cells count as outside: designed
     *                  open-air spaces such as roof decks, terraces and atria open to the sky
     */
    public static Enclosure enclosure(VoxelGrid grid, List<int[]> seeds, List<int[]> openZones) {
        BitSet outside = outside(grid, openZones);
        BitSet interior = new BitSet(grid.volume());
        BitSet leaks = new BitSet(grid.volume());
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        int used = 0;
        List<int[]> notOpen = new ArrayList<>();
        for (int[] seed : seeds) {
            if (grid.get(seed[0], seed[1], seed[2]) != VoxelGrid.OPEN) {
                notOpen.add(seed);
                continue;
            }
            used++;
            int at = grid.index(seed[0], seed[1], seed[2]);
            if (!interior.get(at)) {
                interior.set(at);
                queue.add(seed);
            }
        }
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] d : N6) {
                int x = cell[0] + d[0];
                int y = cell[1] + d[1];
                int z = cell[2] + d[2];
                if (grid.get(x, y, z) != VoxelGrid.OPEN) {
                    continue;
                }
                int at = grid.index(x, y, z);
                if (interior.get(at)) {
                    continue;
                }
                if (outside.get(at)) {
                    leaks.set(grid.index(cell[0], cell[1], cell[2]));
                    continue;
                }
                interior.set(at);
                queue.add(new int[] {x, y, z});
            }
        }
        return new Enclosure(interior, leaks, used, notOpen);
    }

    private static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Open cells that are obviously outside: open sky above, a clear line to the box edge, or an open zone. */
    static BitSet outside(VoxelGrid grid, List<int[]> openZones) {
        BitSet out = new BitSet(grid.volume());
        for (int x = grid.minX; x <= grid.maxX; x++) {
            for (int z = grid.minZ; z <= grid.maxZ; z++) {
                for (int y = grid.maxY; y >= grid.minY && grid.get(x, y, z) == VoxelGrid.OPEN; y--) {
                    out.set(grid.index(x, y, z));
                }
            }
        }
        for (int y = grid.minY; y <= grid.maxY; y++) {
            for (int z = grid.minZ; z <= grid.maxZ; z++) {
                for (int x = grid.minX; x <= grid.maxX && grid.get(x, y, z) == VoxelGrid.OPEN; x++) {
                    out.set(grid.index(x, y, z));
                }
                for (int x = grid.maxX; x >= grid.minX && grid.get(x, y, z) == VoxelGrid.OPEN; x--) {
                    out.set(grid.index(x, y, z));
                }
            }
            for (int x = grid.minX; x <= grid.maxX; x++) {
                for (int z = grid.minZ; z <= grid.maxZ && grid.get(x, y, z) == VoxelGrid.OPEN; z++) {
                    out.set(grid.index(x, y, z));
                }
                for (int z = grid.maxZ; z >= grid.minZ && grid.get(x, y, z) == VoxelGrid.OPEN; z--) {
                    out.set(grid.index(x, y, z));
                }
            }
        }
        for (int[] zone : openZones) {
            for (int x = Math.max(grid.minX, Math.min(zone[0], zone[3])); x <= Math.min(grid.maxX, Math.max(zone[0], zone[3])); x++) {
                for (int y = Math.max(grid.minY, Math.min(zone[1], zone[4])); y <= Math.min(grid.maxY, Math.max(zone[1], zone[4])); y++) {
                    for (int z = Math.max(grid.minZ, Math.min(zone[2], zone[5])); z <= Math.min(grid.maxZ, Math.max(zone[2], zone[5])); z++) {
                        if (grid.get(x, y, z) == VoxelGrid.OPEN) {
                            out.set(grid.index(x, y, z));
                        }
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Walk
    // ------------------------------------------------------------------

    /** Whether a person can stand in a cell. */
    public static boolean standable(VoxelGrid grid, int x, int y, int z, int headroom, boolean doorsOpen) {
        byte below = grid.get(x, y - 1, z);
        if (below != VoxelGrid.SOLID) {
            return false;
        }
        for (int h = 0; h < headroom; h++) {
            byte cell = grid.get(x, y + h, z);
            if (!(cell == VoxelGrid.OPEN || (doorsOpen && cell == VoxelGrid.DOOR))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The standable cells reachable from the seeds. Moves stay within the box's x and z and strictly
     * inside its y range, as the builder's walker does.
     */
    public static BitSet walk(VoxelGrid grid, List<int[]> seeds, int headroom, int step, boolean doorsOpen) {
        BitSet seen = new BitSet(grid.volume());
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int[] seed : seeds) {
            if (grid.contains(seed[0], seed[1], seed[2]) && standable(grid, seed[0], seed[1], seed[2], headroom, doorsOpen)
                && !seen.get(grid.index(seed[0], seed[1], seed[2]))) {
                seen.set(grid.index(seed[0], seed[1], seed[2]));
                queue.add(seed);
            }
        }
        int[] heights = new int[1 + 2 * step];
        heights[0] = 0;
        for (int s = 1; s <= step; s++) {
            heights[s] = s;
            heights[step + s] = -s;
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] d : dirs) {
                for (int dy : heights) {
                    int x = cell[0] + d[0];
                    int y = cell[1] + dy;
                    int z = cell[2] + d[1];
                    if (x < grid.minX || x > grid.maxX || z < grid.minZ || z > grid.maxZ || y <= grid.minY || y >= grid.maxY) {
                        continue;
                    }
                    int at = grid.index(x, y, z);
                    if (seen.get(at)) {
                        continue;
                    }
                    if (standable(grid, x, y, z, headroom, doorsOpen)) {
                        seen.set(at);
                        queue.add(new int[] {x, y, z});
                        break;
                    }
                }
            }
        }
        return seen;
    }

    /** A test for one cell of a grid. */
    public interface CellTest {
        boolean test(int x, int y, int z);
    }

    /**
     * The cell nearest {@code at} within {@code radius} (by steps along the axes) that passes, or null.
     * Ties go to the same layer first, then lower x, y, z, so the answer does not depend on hash order.
     * A seed or probe given on a wardrobe top or a counter flooded nothing (#147); moved to the floor
     * beside it, it means what the caller meant.
     */
    @Nullable
    public static int[] nearest(VoxelGrid grid, int[] at, int radius, CellTest passes) {
        if (grid.contains(at[0], at[1], at[2]) && passes.test(at[0], at[1], at[2])) {
            return at;
        }
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestLayer = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (distance == 0 || distance > radius) {
                        continue;
                    }
                    int x = at[0] + dx;
                    int y = at[1] + dy;
                    int z = at[2] + dz;
                    if (!grid.contains(x, y, z) || !passes.test(x, y, z)) {
                        continue;
                    }
                    int layer = Math.abs(dy);
                    if (distance < bestDistance || distance == bestDistance && layer < bestLayer) {
                        best = new int[] {x, y, z};
                        bestDistance = distance;
                        bestLayer = layer;
                    }
                }
            }
        }
        return best;
    }

    /** Only the cells of a set whose y is one of {@code layers}; the set itself when there are none. */
    public static BitSet onLayers(VoxelGrid grid, BitSet cells, Set<Integer> layers) {
        if (layers.isEmpty()) {
            return cells;
        }
        BitSet kept = new BitSet(grid.volume());
        for (int x = grid.minX; x <= grid.maxX; x++) {
            for (int y : layers) {
                if (y < grid.minY || y > grid.maxY) {
                    continue;
                }
                for (int z = grid.minZ; z <= grid.maxZ; z++) {
                    int at = grid.index(x, y, z);
                    if (cells.get(at)) {
                        kept.set(at);
                    }
                }
            }
        }
        return kept;
    }

    /** Every standable cell in the box (strictly inside its y range), reached or not. */
    public static BitSet standableCells(VoxelGrid grid, int headroom, boolean doorsOpen) {
        BitSet all = new BitSet(grid.volume());
        for (int x = grid.minX; x <= grid.maxX; x++) {
            for (int y = grid.minY + 1; y < grid.maxY; y++) {
                for (int z = grid.minZ; z <= grid.maxZ; z++) {
                    if (standable(grid, x, y, z, headroom, doorsOpen)) {
                        all.set(grid.index(x, y, z));
                    }
                }
            }
        }
        return all;
    }

    // ------------------------------------------------------------------
    // Clusters
    // ------------------------------------------------------------------

    /** A 26-connected group of cells. */
    public static final class Cluster {
        public final int count;
        public final int[] min;
        public final int[] max;
        public final int[] sample;

        Cluster(int count, int[] min, int[] max, int[] sample) {
            this.count = count;
            this.min = min;
            this.max = max;
            this.sample = sample;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("count", count);
            json.add("min", array(min));
            json.add("max", array(max));
            json.add("sample", array(sample));
            return json;
        }
    }

    /** The cells of a set as 26-connected clusters, largest first. */
    public static List<Cluster> clusters(VoxelGrid grid, BitSet cells) {
        BitSet left = (BitSet) cells.clone();
        List<Cluster> out = new ArrayList<>();
        int sizeY = grid.maxY - grid.minY + 1;
        int sizeZ = grid.maxZ - grid.minZ + 1;
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int start = left.nextSetBit(0); start >= 0; start = left.nextSetBit(start + 1)) {
            left.clear(start);
            stack.push(start);
            int count = 0;
            int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            int[] sample = null;
            while (!stack.isEmpty()) {
                int at = stack.pop();
                int z = at % sizeZ + grid.minZ;
                int y = (at / sizeZ) % sizeY + grid.minY;
                int x = at / sizeZ / sizeY + grid.minX;
                count++;
                if (sample == null) {
                    sample = new int[] {x, y, z};
                }
                min[0] = Math.min(min[0], x);
                min[1] = Math.min(min[1], y);
                min[2] = Math.min(min[2], z);
                max[0] = Math.max(max[0], x);
                max[1] = Math.max(max[1], y);
                max[2] = Math.max(max[2], z);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            int nz = z + dz;
                            if (grid.contains(nx, ny, nz)) {
                                int n = grid.index(nx, ny, nz);
                                if (left.get(n)) {
                                    left.clear(n);
                                    stack.push(n);
                                }
                            }
                        }
                    }
                }
            }
            out.add(new Cluster(count, min, max, sample));
        }
        Collections.sort(out, Comparator.comparingInt((Cluster c) -> -c.count));
        return out;
    }

    /** The clusters of at least {@code min} cells: smaller ones are furniture tops, not rooms. */
    public static List<Cluster> atLeast(List<Cluster> clusters, int min) {
        List<Cluster> kept = new ArrayList<>();
        for (Cluster cluster : clusters) {
            if (cluster.count >= min) {
                kept.add(cluster);
            }
        }
        return kept;
    }

    /** The first {@code limit} clusters as JSON. */
    public static JsonArray clustersJson(List<Cluster> clusters, int limit) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < clusters.size() && i < limit; i++) {
            array.add(clusters.get(i).toJson());
        }
        return array;
    }

    static JsonArray array(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }
}
