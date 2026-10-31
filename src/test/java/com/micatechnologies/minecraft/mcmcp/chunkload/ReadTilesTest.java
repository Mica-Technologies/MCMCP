package com.micatechnologies.minecraft.mcmcp.chunkload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReadTilesTest {

    /** The box's chunks in row order, as ChunkKeys.box lists them. */
    private static List<Long> rowOrder(int minX, int minZ, int maxX, int maxZ) {
        return new ArrayList<>(ChunkKeys.box(minX, minZ, maxX, maxZ));
    }

    private static void assertCoversInOrder(List<ReadTiles.Tile> tiles, int minX, int minZ, int maxX, int maxZ) {
        List<Long> order = rowOrder(minX, minZ, maxX, maxZ);
        int next = 0;
        for (ReadTiles.Tile tile : tiles) {
            assertEquals(next, tile.firstChunk, "tiles follow one another in the box's chunk list");
            for (int i = tile.firstChunk; i <= tile.lastChunk; i++) {
                long key = order.get(i);
                assertTrue(tile.containsChunk(ChunkKeys.x(key), ChunkKeys.z(key)),
                    "chunk " + i + " of the list lies in the tile that claims it");
            }
            next = tile.lastChunk + 1;
        }
        assertEquals(order.size(), next, "every chunk of the box is in a tile");
    }

    @Test
    void aBoxThatFitsIsOneTileOfTheWholeBox() {
        List<ReadTiles.Tile> tiles = ReadTiles.plan(0, 0, 100, 100, 0, 512);

        assertEquals(1, tiles.size());
        assertEquals(49, tiles.get(0).holdChunks().size());
        assertEquals(100, tiles.get(0).maxX);
    }

    @Test
    void theIssuesCorridorIsReadInBandsOfWholeRowsEachUnderTheCap() {
        // #167: x -1000..240 by z -2560..-2241, 1659 chunks over a 512-chunk per-caller cap.
        List<ReadTiles.Tile> tiles = ReadTiles.plan(-1000, -2560, 240, -2241, 0, 512);

        assertTrue(tiles.size() > 1);
        for (ReadTiles.Tile tile : tiles) {
            assertTrue(tile.holdChunks().size() <= 512, "a tile holds no more than the room");
            assertEquals(-1000, tile.minX, "a band is whole rows");
            assertEquals(240, tile.maxX);
        }
        assertCoversInOrder(tiles, -1000, -2560, 240, -2241);
    }

    @Test
    void aRowWiderThanTheRoomIsCutAlongItAndStaysInListOrder() {
        List<ReadTiles.Tile> tiles = ReadTiles.plan(0, 0, 16 * 100 - 1, 47, 0, 30);

        for (ReadTiles.Tile tile : tiles) {
            assertTrue(tile.holdChunks().size() <= 30);
            assertEquals(tile.minChunkZ, tile.maxChunkZ, "a piece of one row");
        }
        assertCoversInOrder(tiles, 0, 0, 16 * 100 - 1, 47);
    }

    @Test
    void aMarginIsHeldRoundEachTileAndStillCountedAgainstTheRoom() {
        // near's radius 8: neighbours up to 8 blocks out are read, so their chunks are held too.
        List<ReadTiles.Tile> tiles = ReadTiles.plan(0, 0, 640, 640, 8, 200);

        Set<Long> union = new HashSet<>();
        for (ReadTiles.Tile tile : tiles) {
            Set<Long> held = tile.holdChunks();
            assertTrue(held.size() <= 200, "margin included, a tile fits");
            assertTrue(held.contains(ChunkKeys.ofBlock(tile.minX - 8, tile.minZ - 8)));
            union.addAll(held);
        }
        assertTrue(union.containsAll(ChunkKeys.box(-8, -8, 648, 648)), "the whole widened box is read somewhere");
        assertCoversInOrder(tiles, 0, 0, 640, 640);
    }

    @Test
    void negativeCoordinatesClipTilesToTheBoxNotToChunkEdges() {
        List<ReadTiles.Tile> tiles = ReadTiles.plan(-37, -5, 300, 700, 0, 64);

        assertEquals(-37, tiles.get(0).minX);
        assertEquals(-5, tiles.get(0).minZ);
        assertEquals(700, tiles.get(tiles.size() - 1).maxZ);
        assertCoversInOrder(tiles, -37, -5, 300, 700);
    }

    @Test
    void noRoomForEvenOneChunkWithItsMarginGivesNoPlan() {
        assertNull(ReadTiles.plan(0, 0, 1000, 1000, 40, 8));
    }

    @Test
    void everySampledPixelFallsInExactlyOneTile() {
        int minX = -1000;
        int scale = 3;
        int maxX = 239;
        int pixels = (maxX - minX + 1 + scale - 1) / scale;
        // The last pixel's sample point can lie past the box's edge, so a plan walks out to it.
        int lastSample = ReadTiles.lastSamplePoint(minX, scale, pixels);
        assertTrue(lastSample > maxX, "this box's last sample point is past its edge");
        List<ReadTiles.Tile> tiles = ReadTiles.plan(minX, 0, Math.max(maxX, lastSample), 15, 0, 20);
        int[] seen = new int[pixels];
        for (ReadTiles.Tile tile : tiles) {
            int[] range = ReadTiles.pixelRange(minX, scale, pixels, tile.minX, tile.maxX);
            for (int p = range[0]; p < range[1]; p++) {
                seen[p]++;
            }
        }
        int[] once = new int[pixels];
        java.util.Arrays.fill(once, 1);
        assertArrayEquals(once, seen);
    }

    @Test
    void aPixelRangeIsEmptyWhenNoSamplePointIsInside() {
        int[] range = ReadTiles.pixelRange(0, 16, 10, 9, 15);
        assertEquals(range[0], range[1]);
    }
}
