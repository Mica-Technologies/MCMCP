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

    @Test
    void eachWriterKeepsItsOwnNewestPointsSoABusyOneCannotEvictAnother() throws IOException {
        File folder = Files.createTempDirectory("mcmcp-undo-test").toFile();
        // A quiet builder's two points, then a busy agent's four after them (#95).
        owned(folder, "u1", "orchestrator:c1");
        owned(folder, "u2", "orchestrator:c1");
        for (int i = 3; i <= 6; i++) {
            owned(folder, "u" + i, "orchestrator:c2");
        }
        UndoPoints.prune(folder, 2, 0);
        String[] left = folder.list();
        Arrays.sort(left);
        assertArrayEquals(new String[] {"u1.dat", "u1.json", "u2.dat", "u2.json",
            "u5.dat", "u5.json", "u6.dat", "u6.json"}, left);
        deleteFolder(folder);
    }

    @Test
    void aPruneCountsWhatItDeletedForSizeAndForCountSeparately() throws IOException {
        File folder = Files.createTempDirectory("mcmcp-undo-test").toFile();
        owned(folder, "u1", "orchestrator:c1");
        owned(folder, "u2", "orchestrator:c1");
        owned(folder, "u3", "orchestrator:c1");
        point(folder, "u4", 500);
        point(folder, "u5", 600);
        // Newest first: u5 600 fits alone, u4 500 does not fit beside it, and everything older goes with it.
        UndoPoints.Pruned bySize = UndoPoints.prune(folder, 50, 1000);
        assertEquals(4, bySize.bySize);
        assertEquals(0, bySize.byCount);
        assertFalse(bySize.newestOverLimit);
        deleteFolder(folder);

        folder = Files.createTempDirectory("mcmcp-undo-test").toFile();
        owned(folder, "u1", "orchestrator:c1");
        owned(folder, "u2", "orchestrator:c1");
        owned(folder, "u3", "orchestrator:c1");
        UndoPoints.Pruned byCount = UndoPoints.prune(folder, 2, 0);
        assertEquals(0, byCount.bySize);
        assertEquals(1, byCount.byCount);
        deleteFolder(folder);
    }

    @Test
    void aPointOverTheSizeLimitOnItsOwnIsReportedWithTheLimitAndAWayToKeepUndo() throws IOException {
        File folder = Files.createTempDirectory("mcmcp-undo-test").toFile();
        point(folder, "u1", 3 * 1024 * 1024);
        UndoPoints.Pruned pruned = UndoPoints.prune(folder, 50, 1024L * 1024L);
        assertTrue(pruned.newestOverLimit);
        String note = pruned.note(1, 50, false);
        assertTrue(note.contains("3.0 MB"), note);
        assertTrue(note.contains("undo.maxUndoMegabytes (1 MB)"), note);
        assertTrue(note.contains("config/mcmcp.cfg"), note);
        deleteFolder(folder);
    }

    @Test
    void anOlderPointGivingWayIsNewsToAJobButNotToAnInteractiveWrite() {
        UndoPoints.Pruned pruned = new UndoPoints.Pruned();
        pruned.bySize = 2;
        pruned.byCount = 1;
        assertNull(pruned.note(64, 50, false), "an interactive write's ring turning over is not a warning");
        String note = pruned.note(64, 50, true);
        assertTrue(note.contains("2 older undo point(s)") && note.contains("(64 MB)"), note);
        assertTrue(note.contains("undo.maxUndoPoints (50 per writer)"), note);
        assertNull(new UndoPoints.Pruned().note(64, 50, true), "nothing deleted, nothing said");
    }

    /** An undo point made by {@code owner}. */
    private static void owned(File folder, String id, String owner) throws IOException {
        Files.write(new File(folder, id + ".json").toPath(),
            ("{\"id\":\"" + id + "\",\"owner\":\"" + owner + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(new File(folder, id + ".dat").toPath(), new byte[10]);
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
