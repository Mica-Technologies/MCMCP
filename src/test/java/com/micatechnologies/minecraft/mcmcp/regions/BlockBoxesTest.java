package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Compact region reads: cuboid boxes and text slices. */
class BlockBoxesTest {

    /** A 40 x 10 x 30 room: stone floor, glass walls, a wool rug, air inside. */
    private static int[] room(int sx, int sy, int sz) {
        int[] cells = new int[sx * sy * sz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int v = 0;
                    if (y == 0) {
                        v = 1;
                    }
                    else if (x == 0 || z == 0 || x == sx - 1 || z == sz - 1) {
                        v = 2;
                    }
                    else if (y == 1 && x > 10 && x < 20 && z > 5 && z < 15) {
                        v = 3;
                    }
                    cells[x + sx * (z + sz * y)] = v;
                }
            }
        }
        return cells;
    }

    @Test
    void aRoomOfAFewMaterialsComesBackAsAFewDozenBoxesAndPaintsBackExactly() {
        int[] cells = room(40, 10, 30);
        BlockBoxes.Result result = BlockBoxes.merge(cells, 40, 10, 30, 0, 10000);
        assertFalse(result.truncated);
        assertTrue(result.boxes.size() < 40, "12,000 cells became " + result.boxes.size() + " boxes");
        assertArrayEquals(cells, BlockBoxes.paint(result.boxes, 40, 10, 30, 0));
    }

    @Test
    void noiseStillRoundTripsWithEveryCellInExactlyOneBox() {
        int[] cells = new int[17 * 9 * 13];
        Random random = new Random(11);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = random.nextInt(4);
        }
        BlockBoxes.Result skipping = BlockBoxes.merge(cells, 17, 9, 13, 0, 100000);
        assertArrayEquals(cells, BlockBoxes.paint(skipping.boxes, 17, 9, 13, 0));
        BlockBoxes.Result all = BlockBoxes.merge(cells, 17, 9, 13, -1, 100000);
        long covered = 0;
        for (int[] box : all.boxes) {
            covered += (long) (box[4] - box[1] + 1) * (box[5] - box[2] + 1) * (box[6] - box[3] + 1);
        }
        assertEquals(cells.length, covered);
        assertArrayEquals(cells, BlockBoxes.paint(all.boxes, 17, 9, 13, 99));
    }

    @Test
    void aSolidRegionIsOneBoxAndAnEmptyOneIsNone() {
        int[] solid = new int[8 * 8 * 8];
        Arrays.fill(solid, 5);
        List<int[]> boxes = BlockBoxes.merge(solid, 8, 8, 8, 0, 10).boxes;
        assertEquals(1, boxes.size());
        assertArrayEquals(new int[] {5, 0, 0, 0, 7, 7, 7}, boxes.get(0));
        assertTrue(BlockBoxes.merge(new int[27], 3, 3, 3, 0, 10).boxes.isEmpty());
    }

    @Test
    void maxBoxesStopsTheMergeAndSaysSo() {
        int[] cells = new int[10];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = i % 2 + 1;
        }
        BlockBoxes.Result result = BlockBoxes.merge(cells, 10, 1, 1, 0, 3);
        assertTrue(result.truncated);
        assertEquals(3, result.boxes.size());
    }

    @Test
    void aFloorPlanHasNorthUpWestLeftAndLabelledRows() {
        // 3 x 1 x 2: z=10 row is stone, stone, air; z=11 row is glass, air, stone.
        String[] ids = {"minecraft:stone", "minecraft:air", "minecraft:glass"};
        int[] cells = {0, 0, 1, 2, 1, 0};
        char[] chars = BlockSlices.assign(ids, new int[] {3, 2, 1});
        assertEquals('s', chars[0]);
        assertEquals('.', chars[1]);
        assertEquals('g', chars[2]);
        List<BlockSlices.Slice> slices = BlockSlices.render(cells, chars, 100, 64, 10, 3, 1, 2, 'y');
        assertEquals(1, slices.size());
        assertEquals(64, slices.get(0).at);
        assertEquals(Arrays.asList("10|ss.", "11|g.s"), slices.get(0).rows);
    }

    @Test
    void anElevationPutsTheTopLayerFirst() {
        // 2 x 2 x 1, z slice: y=0 is stone, stone; y=1 is air, glass.
        String[] ids = {"minecraft:stone", "minecraft:air", "minecraft:glass"};
        int[] cells = {0, 0, 1, 2};
        char[] chars = BlockSlices.assign(ids, new int[] {2, 1, 1});
        List<BlockSlices.Slice> slices = BlockSlices.render(cells, chars, 0, 9, 0, 2, 2, 1, 'z');
        assertEquals(Arrays.asList("10|.g", " 9|ss"), slices.get(0).rows);
    }

    @Test
    void blocksSharingAFirstLetterGetDistinctCharactersAndALegend() {
        String[] ids = {"minecraft:stone", "minecraft:stonebrick", "minecraft:stone:3", "mcmcp:unloaded"};
        char[] chars = BlockSlices.assign(ids, new int[] {9, 5, 1, 1});
        assertEquals('s', chars[0]);
        assertEquals('S', chars[1]);
        assertEquals('#', chars[2]);
        assertEquals('?', chars[3]);
        assertEquals("minecraft:stonebrick", BlockSlices.legend(ids, chars).get("S"));
    }

    @Test
    void moreBlocksThanCharactersShareTheOtherCharacter() {
        String[] ids = new String[100];
        int[] counts = new int[100];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "mod:block" + i;
            counts[i] = 1000 - i;
        }
        char[] chars = BlockSlices.assign(ids, counts);
        assertEquals('b', chars[0]);
        assertEquals(BlockSlices.OTHER, chars[99]);
        assertTrue(BlockSlices.legend(ids, chars).containsKey(String.valueOf(BlockSlices.OTHER)));
    }
}
