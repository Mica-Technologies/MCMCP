package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The parts of undo points that need no world: their ids, their files, and what gets drawn. */
class UndoPointsTest {

    @Test
    void idsAreUniqueAndSortByAgeEvenWithinOneMillisecond() {
        String first = UndoPoints.newId();
        String second = UndoPoints.newId();
        assertTrue(Long.parseLong(second.substring(1)) > Long.parseLong(first.substring(1)));
        assertTrue(UndoPoints.isId(first));
    }

    @Test
    void anIdCannotNameAFileOutsideTheFolder() {
        assertFalse(UndoPoints.isId("../../level"));
        assertFalse(UndoPoints.isId("u12/../x"));
        assertFalse(UndoPoints.isId(null));
        assertTrue(UndoPoints.isId("u1791392863587"));
    }

    @Test
    void pruningKeepsTheNewestPointsAndTakesEveryFileOfTheOldOnes() throws IOException {
        File folder = Files.createTempDirectory("mcmcp-undo-test").toFile();
        // Numerically, not as text: u9 is older than u10.
        for (String id : new String[] {"u9", "u10", "u11"}) {
            for (String suffix : new String[] {".json", ".dat", "-before.png"}) {
                assertTrue(new File(folder, id + suffix).createNewFile());
            }
        }
        UndoPoints.prune(folder, 2);
        String[] left = folder.list();
        Arrays.sort(left);
        assertArrayEquals(new String[] {"u10-before.png", "u10.dat", "u10.json",
            "u11-before.png", "u11.dat", "u11.json"}, left);
        for (File file : folder.listFiles()) {
            file.delete();
        }
        folder.delete();
    }

    @Test
    void aSmallWriteIsDrawnWithItsSurroundingsAndASprawlingOneIsNotDrawn() {
        int[] area = UndoPoints.imageArea(0, 0, 15, 15);
        assertEquals(-2, area[0]);
        assertEquals(17, area[2]);
        // Two placements a thousand blocks apart would be a map of nothing.
        assertNull(UndoPoints.imageArea(0, 0, 1000, 3));
    }
}
