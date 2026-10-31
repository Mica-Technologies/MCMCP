package com.micatechnologies.minecraft.mcmcp.chunkload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Cuts a read-only box into pieces that each fit the chunks a caller may still hold (#167).
 *
 * <p>A read with {@code load: true} over a box bigger than {@code chunks.maxHeldChunksPerCaller} (or
 * the server's {@code chunks.maxHeldChunks}) was refused outright, though a read needs no chunk to stay
 * resident across the whole box: it can load a piece, read it, give it back and move on. This works out
 * the pieces. The caps then pace a large read rather than block it.
 *
 * <p>Every tile is a contiguous run of the box's chunks in {@link ChunkKeys#box} row order (z outer, x
 * inner): a band of whole rows when a row fits, otherwise a piece of one row. So a read that walks the
 * box's chunks as a list — {@code server_find_blocks}, whose cursor is an index into that list — visits
 * them in the same order tiled or not.
 *
 * <p>Pure: no Minecraft class, so the arithmetic is unit-tested.
 */
public final class ReadTiles {

    /** Below this many chunks of room, tiling would take more round trips than it saves; refuse instead. */
    public static final int MIN_ROOM = 16;

    private ReadTiles() {
    }

    /** One piece of a box: a chunk rectangle, and the block rectangle of the box inside it. */
    public static final class Tile {

        public final int index;
        public final int minChunkX;
        public final int minChunkZ;
        public final int maxChunkX;
        public final int maxChunkZ;
        /** The box's blocks in this tile, corners inclusive. */
        public final int minX;
        public final int minZ;
        public final int maxX;
        public final int maxZ;
        /** Where this tile starts and ends in the box's chunk list (row order), inclusive. */
        public final int firstChunk;
        public final int lastChunk;
        /** What a tile holds loaded: its blocks widened by the read's margin, as chunks. */
        private final int margin;

        Tile(int index, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, int boxMinX, int boxMinZ,
             int boxMaxX, int boxMaxZ, int firstChunk, int lastChunk, int margin) {
            this.index = index;
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkX = maxChunkX;
            this.maxChunkZ = maxChunkZ;
            this.minX = Math.max(boxMinX, minChunkX << 4);
            this.minZ = Math.max(boxMinZ, minChunkZ << 4);
            this.maxX = Math.min(boxMaxX, (maxChunkX << 4) + 15);
            this.maxZ = Math.min(boxMaxZ, (maxChunkZ << 4) + 15);
            this.firstChunk = firstChunk;
            this.lastChunk = lastChunk;
            this.margin = margin;
        }

        /** The chunks this tile holds while it is read. */
        public Set<Long> holdChunks() {
            return ChunkKeys.box(minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        }

        public boolean containsBlock(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean containsChunk(int chunkX, int chunkZ) {
            return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }

        public int chunkCount() {
            return lastChunk - firstChunk + 1;
        }
    }

    /** The box as one tile: the shape every read takes when nothing needs cutting. */
    public static Tile whole(int minX, int minZ, int maxX, int maxZ, int margin) {
        int cMinX = minX >> 4;
        int cMinZ = minZ >> 4;
        int cMaxX = maxX >> 4;
        int cMaxZ = maxZ >> 4;
        int count = (cMaxX - cMinX + 1) * (cMaxZ - cMinZ + 1);
        return new Tile(0, cMinX, cMinZ, cMaxX, cMaxZ, minX, minZ, maxX, maxZ, 0, count - 1, margin);
    }

    /**
     * The tiles of a block box, each holding at most {@code room} chunks.
     *
     * @param margin blocks round each tile that must be loaded too ({@code near}'s radius); 0 for none
     * @return the tiles in row order, or null when not even one chunk at a time fits in {@code room}
     */
    @Nullable
    public static List<Tile> plan(int minX, int minZ, int maxX, int maxZ, int margin, int room) {
        int cMinX = minX >> 4;
        int cMinZ = minZ >> 4;
        int cMaxX = maxX >> 4;
        int cMaxZ = maxZ >> 4;
        int width = cMaxX - cMinX + 1;
        int depth = cMaxZ - cMinZ + 1;
        // A margin of m blocks widens a tile by at most ceil(m / 16) chunks on each side.
        int ring = (Math.max(0, margin) + 15) / 16;
        if ((long) (width + 2 * ring) * (depth + 2 * ring) <= room) {
            return Collections.singletonList(whole(minX, minZ, maxX, maxZ, margin));
        }
        List<Tile> tiles = new ArrayList<>();
        int rows = room / (width + 2 * ring) - 2 * ring;
        if (rows >= 1) {
            for (int z = cMinZ; z <= cMaxZ; z += rows) {
                int toZ = Math.min(cMaxZ, z + rows - 1);
                int first = (z - cMinZ) * width;
                int last = (toZ - cMinZ + 1) * width - 1;
                tiles.add(new Tile(tiles.size(), cMinX, z, cMaxX, toZ, minX, minZ, maxX, maxZ, first, last, margin));
            }
            return tiles;
        }
        int length = room / (1 + 2 * ring) - 2 * ring;
        if (length < 1) {
            return null;
        }
        for (int z = cMinZ; z <= cMaxZ; z++) {
            for (int x = cMinX; x <= cMaxX; x += length) {
                int toX = Math.min(cMaxX, x + length - 1);
                int first = (z - cMinZ) * width + (x - cMinX);
                int last = first + (toX - x);
                tiles.add(new Tile(tiles.size(), x, z, toX, z, minX, minZ, maxX, maxZ, first, last, margin));
            }
        }
        return tiles;
    }

    /**
     * The pixels of one axis of a sampled map whose sample point falls in {@code [from, to]}, as
     * {@code {first, endExclusive}}. A map samples pixel {@code p} at {@code origin + p * scale + scale / 2}.
     */
    public static int[] pixelRange(int origin, int scale, int pixels, int from, int to) {
        int offset = scale / 2;
        int first = ceilDiv(from - origin - offset, scale);
        int last = Math.floorDiv(to - origin - offset, scale);
        first = Math.max(0, first);
        int end = Math.min(pixels, last + 1);
        return new int[] {first, Math.max(first, end)};
    }

    /**
     * Where a sampled map's last pixel samples. It can lie up to half a pixel past the box's far edge, so
     * a tiled map walks out to it, or that pixel would be in no tile and never drawn.
     */
    public static int lastSamplePoint(int origin, int scale, int pixels) {
        return origin + (pixels - 1) * scale + scale / 2;
    }

    private static int ceilDiv(int a, int b) {
        return -Math.floorDiv(-a, b);
    }
}
