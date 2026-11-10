package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuarterTurnsTest {

    private static final List<String> COMPASS = Arrays.asList("north", "east", "south", "west", "up", "down");

    @Test
    void degreesAreQuarterTurnsClockwiseAndAnythingElseIsRefused() {
        assertEquals(0, QuarterTurns.ofDegrees(0));
        assertEquals(1, QuarterTurns.ofDegrees(90));
        assertEquals(3, QuarterTurns.ofDegrees(270));
        assertEquals(3, QuarterTurns.ofDegrees(-90));
        assertEquals(0, QuarterTurns.ofDegrees(360));
        assertThrows(IllegalArgumentException.class, () -> QuarterTurns.ofDegrees(45));
    }

    @Test
    void aQuarterTurnTakesNorthToEastAndTheNorthEdgeOfABoxToItsEastEdge() {
        assertEquals("east", QuarterTurns.compass("north", 1));
        assertEquals("west", QuarterTurns.compass("north", 3));
        assertNull(QuarterTurns.compass("up", 1));

        // A 3 x 2 box (x 10..12, z 20..21) turned once is 2 x 3, its minimum corner put at (100, 64, 200).
        QuarterTurns.Box box = new QuarterTurns.Box(10, 64, 20, 12, 66, 21, 1, 100, 64, 200);
        assertArrayEquals(new int[] {100, 64, 200, 101, 66, 202}, box.destination());
        // The north-west corner goes to the north-east, the north-east to the south-east.
        assertArrayEquals(new int[] {101, 64, 200}, box.map(10, 64, 20));
        assertArrayEquals(new int[] {101, 64, 202}, box.map(12, 64, 20));
        assertArrayEquals(new int[] {100, 66, 200}, box.map(10, 66, 21));
    }

    @Test
    void everyTurnFillsItsDestinationBoxExactlyOnce() {
        for (int turns = 0; turns < 4; turns++) {
            QuarterTurns.Box box = new QuarterTurns.Box(-5, 0, 7, -2, 1, 13, turns, 40, 10, -40);
            int[] to = box.destination();
            Set<String> seen = new HashSet<>();
            for (int x = box.minX; x <= box.maxX; x++) {
                for (int y = box.minY; y <= box.maxY; y++) {
                    for (int z = box.minZ; z <= box.maxZ; z++) {
                        int[] at = box.map(x, y, z);
                        assertTrue(at[0] >= to[0] && at[0] <= to[3] && at[1] >= to[1] && at[1] <= to[4]
                            && at[2] >= to[2] && at[2] <= to[5], "turn " + turns + " left its box");
                        assertTrue(seen.add(at[0] + "," + at[1] + "," + at[2]), "turn " + turns + " hit a cell twice");
                    }
                }
            }
            assertEquals(box.cells(), seen.size());
        }
    }

    @Test
    void fourQuarterTurnsOfTheSameBoxComeBackToWhereTheyStarted() {
        int[] at = {3, 70, 2};
        int[] corner = {0, 64, 0};
        int sizeX = 8;
        int sizeZ = 5;
        for (int i = 0; i < 4; i++) {
            QuarterTurns.Box box = new QuarterTurns.Box(corner[0], 64, corner[2], corner[0] + sizeX - 1, 80,
                corner[2] + sizeZ - 1, 1, corner[0], 64, corner[2]);
            at = box.map(at[0], at[1], at[2]);
            int swap = sizeX;
            sizeX = sizeZ;
            sizeZ = swap;
        }
        assertArrayEquals(new int[] {3, 70, 2}, at);
    }

    @Test
    void aDirectionalValueTurnsByItsNameAndStaysAValueThePropertyHas() {
        assertEquals("south", QuarterTurns.value("east", 1, COMPASS));
        assertNull(QuarterTurns.value("up", 1, Arrays.asList("up", "down")), "a name with no compass word in it");
        assertEquals("east_west", QuarterTurns.value("north_south", 1, Arrays.asList("north_south", "east_west")));
        assertEquals("north_south", QuarterTurns.value("east_west", 1, Arrays.asList("north_south", "east_west")),
            "east_west turns into south_north, which the property spells north_south");
        assertEquals("north_south", QuarterTurns.value("north_south", 2, Arrays.asList("north_south", "east_west")));
        assertEquals("ascending_east", QuarterTurns.value("ascending_north", 1,
            Arrays.asList("ascending_north", "ascending_east", "ascending_south", "ascending_west")));
        assertEquals("south_west", QuarterTurns.value("south_east", 1,
            Arrays.asList("south_east", "south_west", "north_west", "north_east")));
    }

    @Test
    void anAxisNameChangesOnAnOddNumberOfQuarterTurnsOnly() {
        List<String> axes = Arrays.asList("x", "y", "z");
        assertEquals("z", QuarterTurns.value("x", 1, axes));
        assertEquals("x", QuarterTurns.value("x", 2, axes));
        assertNull(QuarterTurns.value("y", 1, axes));
        assertEquals("up_z", QuarterTurns.value("up_x", 3, Arrays.asList("up_x", "up_z", "down_x", "down_z")));
        assertEquals("lines_x", QuarterTurns.value("lines_z", 1, Arrays.asList("default", "lines_x", "lines_y", "lines_z")));
    }

    @Test
    void aTurnThePropertyCannotSpellIsNotInvented() {
        // A block that can only face north or south has nothing to become when turned a quarter.
        assertNull(QuarterTurns.value("north", 1, Arrays.asList("north", "south")));
        assertEquals("south", QuarterTurns.value("north", 2, Arrays.asList("north", "south")));
        assertNull(QuarterTurns.value("oak", 1, Arrays.asList("oak", "birch")));
    }
}
