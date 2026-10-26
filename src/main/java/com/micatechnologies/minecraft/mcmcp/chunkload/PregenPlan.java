package com.micatechnologies.minecraft.mcmcp.chunkload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which chunks a pregeneration pass touches, in what order, and when each may be given back (#138).
 *
 * <p>Generating a chunk is half the work. Vanilla 1.12 <em>populates</em> a chunk (trees, ores,
 * structures' blocks, most mods' world generation) only while it and its +x, +z and +x+z neighbours
 * are loaded together, so a pass that generated and unloaded one chunk at a time would leave a world
 * of bare terrain to be populated, at full cost, the first time a player walks through it. Hence:
 *
 * <ul>
 *   <li>The pass covers a margin of one chunk past the box's +x and +z edges, so the box's own edge
 *       chunks populate.</li>
 *   <li>A chunk already on disk is skipped, unless it borders one that is not: then it is loaded
 *       beside its new neighbour so that both populate. A box that exists in full touches nothing,
 *       which is what makes running the same pass again the way to resume it.</li>
 *   <li>Rows go in order, and a row is given back once the row after next has started: by then every
 *       chunk in it has had all its +x / +z neighbours loaded alongside it. At most about three
 *       rows are loaded at once.</li>
 * </ul>
 *
 * <p>Pure: the Forge half is {@link ChunkPregen}.
 */
public final class PregenPlan {

    /** Whether a chunk exists, loaded or on disk. */
    public interface Generated {
        boolean at(int chunkX, int chunkZ);
    }

    /** One chunk to touch. */
    public static final class Step {
        public final int x;
        public final int z;
        /** True to generate it; false to load it from disk so that a neighbour can populate. */
        public final boolean generate;
        /** Whether it is inside the box asked for, rather than in the margin. */
        public final boolean inBox;

        Step(int x, int z, boolean generate, boolean inBox) {
            this.x = x;
            this.z = z;
            this.generate = generate;
            this.inBox = inBox;
        }

        public long key() {
            return ChunkKeys.of(x, z);
        }
    }

    public final int minX;
    public final int minZ;
    public final int maxX;
    public final int maxZ;

    /** A box of chunks, corners inclusive and either way round. */
    public PregenPlan(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        this.minX = Math.min(chunkX1, chunkX2);
        this.maxX = Math.max(chunkX1, chunkX2);
        this.minZ = Math.min(chunkZ1, chunkZ2);
        this.maxZ = Math.max(chunkZ1, chunkZ2);
    }

    /** The chunks of a block box. */
    public static PregenPlan ofBlocks(int x1, int z1, int x2, int z2) {
        return new PregenPlan(x1 >> 4, z1 >> 4, x2 >> 4, z2 >> 4);
    }

    public long chunks() {
        return (maxX - minX + 1L) * (maxZ - minZ + 1L);
    }

    /**
     * The chunks to touch, rows in order of z and each row in order of x. Empty when every chunk of
     * the box already exists, margin or no margin: then there is nothing to populate either.
     */
    public List<Step> steps(Generated generated) {
        int width = maxX - minX + 2;
        int depth = maxZ - minZ + 2;
        boolean[] exists = new boolean[width * depth];
        boolean missingInBox = false;
        for (int dz = 0; dz < depth; dz++) {
            for (int dx = 0; dx < width; dx++) {
                boolean present = generated.at(minX + dx, minZ + dz);
                exists[dz * width + dx] = present;
                if (!present && dx < width - 1 && dz < depth - 1) {
                    missingInBox = true;
                }
            }
        }
        if (!missingInBox) {
            return Collections.emptyList();
        }
        List<Step> steps = new ArrayList<>();
        for (int dz = 0; dz < depth; dz++) {
            for (int dx = 0; dx < width; dx++) {
                boolean present = exists[dz * width + dx];
                if (!present || bordersMissing(exists, width, depth, dx, dz)) {
                    steps.add(new Step(minX + dx, minZ + dz, !present, dx < width - 1 && dz < depth - 1));
                }
            }
        }
        return steps;
    }

    private static boolean bordersMissing(boolean[] exists, int width, int depth, int dx, int dz) {
        for (int oz = -1; oz <= 1; oz++) {
            for (int ox = -1; ox <= 1; ox++) {
                int x = dx + ox;
                int z = dz + oz;
                if (x >= 0 && z >= 0 && x < width && z < depth && !exists[z * width + x]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a chunk the pass loaded in row {@code chunkZ} may be given back once the pass has
     * reached row {@code currentZ}. A row is done with once the row after it has been finished, that
     * is, once the pass is two rows past it.
     */
    public static boolean releasable(int chunkZ, int currentZ) {
        return chunkZ <= currentZ - 2;
    }
}
