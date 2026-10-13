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
        UndoPoints.prune(folder, 2, 0);
        String[] left = folder.list();
        Arrays.sort(left);
        assertArrayEquals(new String[] {"u10-before.png", "u10.dat", "u10.json",
            "u11-before.png", "u11.dat", "u11.json"}, left);
        deleteFolder(folder);
    }

    @Test
    void theSizeLimitKeepsOnlyAnUnbrokenRunOfTheNewestPointsThatFit() throws IOException {
        File folder = Files.createTempDirectory("mcmcp-undo-test").toFile();
        // Newest first: u4 is 300 bytes, u3 400, u2 500 (does not fit in 1000), u1 a small 10.
        point(folder, "u1", 10);
        point(folder, "u2", 500);
        point(folder, "u3", 400);
        point(folder, "u4", 300);
        UndoPoints.prune(folder, 50, 1000);
        String[] left = folder.list();
        Arrays.sort(left);
        // u1 would fit on its own, but keeping it would leave a hole where u2 was.
        assertArrayEquals(new String[] {"u3.dat", "u3.json", "u4.dat", "u4.json"}, left);
        deleteFolder(folder);
    }

    @Test
    void theNewestPointIsKeptEvenWhenItAloneIsOverTheSizeLimit() throws IOException {
        File folder = Files.createTempDirectory("mcmcp-undo-test").toFile();
        point(folder, "u1", 10);
        point(folder, "u2", 5000);
        UndoPoints.prune(folder, 50, 1000);
        String[] left = folder.list();
        Arrays.sort(left);
        assertArrayEquals(new String[] {"u2.dat", "u2.json"}, left);
        deleteFolder(folder);
    }

    @Test
    void filesThatAreNotUndoPointsAreLeftAlone() throws IOException {
        File folder = Files.createTempDirectory("mcmcp-undo-test").toFile();
        point(folder, "u1", 10);
        point(folder, "u2", 10);
        assertTrue(new File(folder, "notes.json").createNewFile());
        UndoPoints.prune(folder, 1, 0);
        String[] left = folder.list();
        Arrays.sort(left);
        assertArrayEquals(new String[] {"notes.json", "u2.dat", "u2.json"}, left);
        deleteFolder(folder);
    }

    /** An undo point whose .dat is {@code bytes} long. */
    private static void point(File folder, String id, int bytes) throws IOException {
        assertTrue(new File(folder, id + ".json").createNewFile());
        Files.write(new File(folder, id + ".dat").toPath(), new byte[bytes]);
    }

    private static void deleteFolder(File folder) {
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
