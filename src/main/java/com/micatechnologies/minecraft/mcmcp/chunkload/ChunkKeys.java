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

    /** Every chunk a block box overlaps, in row order. Corners may be given either way round. */
    public static Set<Long> box(int x1, int z1, int x2, int z2) {
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
