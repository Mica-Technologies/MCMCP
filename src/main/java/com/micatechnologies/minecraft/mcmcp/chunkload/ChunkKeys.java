package com.micatechnologies.minecraft.mcmcp.chunkload;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Chunk coordinates packed into one {@code long}, and the chunk sets a box or a list of cells
 * touches. The same packing as vanilla's {@code ChunkPos.asLong}, so keys can be compared with it.
 *
 * <p>Pure: the governor is told exactly which chunks a call needs, and computing that is the part
 * that is easy to get wrong at negative coordinates.
 */
public final class ChunkKeys {

    private ChunkKeys() {
    }

    public static long of(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >>> 32);
    }

    /** The chunk holding block {@code (blockX, blockZ)}. Arithmetic shift: -1 is chunk -1, not 0. */
    public static long ofBlock(int blockX, int blockZ) {
        return of(blockX >> 4, blockZ >> 4);
    }

    /**
     * The most chunks {@link #box} will list: 262,144, a square 8,192 blocks a side, or the thin strip
     * under a fill a million blocks long four times over. Past any real call, and far short of the set
     * that a box with a corner off the map asks for.
     */
    public static final long MAX_BOX_CHUNKS = 262_144L;

    /**
     * Every chunk a block box overlaps, in row order. Corners may be given either way round.
     *
     * @throws IllegalArgumentException for a box over {@link #MAX_BOX_CHUNKS}, before anything is
     *     built. A caller with a limit of its own checks {@link #boxCount} first and says so in its own
     *     words; this is the ceiling under every caller that did not (#219): a set of every chunk under
     *     a box 60 million blocks across was built on the worker until memory ran out, ahead of the
     *     limit that would have refused it.
     */
    public static Set<Long> box(int x1, int z1, int x2, int z2) {
        long count = boxCount(x1, z1, x2, z2);
        if (count > MAX_BOX_CHUNKS) {
            throw new IllegalArgumentException("That area covers " + count + " chunks (x " + Math.min(x1, x2) + " to "
                + Math.max(x1, x2) + ", z " + Math.min(z1, z2) + " to " + Math.max(z1, z2) + "); one call handles "
                + "at most " + MAX_BOX_CHUNKS + ". Nothing was done. Split it.");
        }
        int minX = Math.min(x1, x2) >> 4;
        int maxX = Math.max(x1, x2) >> 4;
        int minZ = Math.min(z1, z2) >> 4;
        int maxZ = Math.max(z1, z2) >> 4;
        Set<Long> keys = new LinkedHashSet<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                keys.add(of(x, z));
            }
        }
        return keys;
    }

    /** How many chunks a block box overlaps, without building the set. */
    public static long boxCount(int x1, int z1, int x2, int z2) {
        long dx = (Math.max(x1, x2) >> 4) - (Math.min(x1, x2) >> 4) + 1L;
        long dz = (Math.max(z1, z2) >> 4) - (Math.min(z1, z2) >> 4) + 1L;
        return dx * dz;
    }

    public static String describe(long key) {
        return "[" + x(key) + "," + z(key) + "]";
    }
}
