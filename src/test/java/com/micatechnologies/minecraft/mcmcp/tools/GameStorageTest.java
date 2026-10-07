package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** What the Data tab is told about a game folder, and what expiring removes — and leaves. */
class GameStorageTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_800_000_000_000L;

    private static File file(File folder, String name, int bytes, long modified) throws IOException {
        folder.mkdirs();
        File file = new File(folder, name);
        Files.write(file.toPath(), new byte[bytes]);
        assertTrue(file.setLastModified(modified));
        return file;
    }

    private static JsonObject row(JsonArray rows, String kind) {
        for (int i = 0; i < rows.size(); i++) {
            if (kind.equals(rows.get(i).getAsJsonObject().get("kind").getAsString())) {
                return rows.get(i).getAsJsonObject();
            }
        }
        throw new AssertionError(kind);
    }

    @Test
    void onlyScreenshotsMcmcpNamedAreCountedOrRemoved() throws IOException {
        File game = Files.createTempDirectory("mcmcp-storage").toFile();
        File shots = new File(game, "screenshots");
        File old = file(shots, "mcmcp-1.png", 100, NOW - 40 * DAY);
        File mine = file(shots, "2026-01-01_12.00.00.png", 100, NOW - 40 * DAY);
        file(shots, "mcmcp-map-2.png", 50, NOW - DAY);

        assertEquals(150, row(GameStorage.usage(game), "screenshots").get("bytes").getAsLong());
        JsonObject result = GameStorage.expire(game, GameStorage.SCREENSHOTS, 30, NOW);
        assertEquals(1, result.get("removed").getAsInt());
        assertFalse(old.exists());
        assertTrue(mine.exists(), "a person's screenshot is never touched");
    }

    @Test
    void anOldUndoPointGoesWithEveryFileItHas() throws IOException {
        File game = Files.createTempDirectory("mcmcp-storage").toFile();
        File undo = new File(game, "saves/world/mcmcp-undo");
        long oldAt = NOW - 10 * DAY;
        long newAt = NOW - DAY;
        for (String suffix : new String[] {".json", ".dat", "-before.png"}) {
            file(undo, "u" + oldAt + suffix, 10, NOW);
            file(undo, "u" + newAt + suffix, 10, NOW);
        }
        JsonObject usage = row(GameStorage.usage(game), "undo");
        assertEquals(2, usage.get("items").getAsInt());
        assertEquals(oldAt, usage.get("oldestMs").getAsLong());

        GameStorage.expire(game, GameStorage.UNDO, 5, NOW);
        assertEquals(3, undo.list().length);
        assertTrue(new File(undo, "u" + newAt + ".json").exists());
    }

    @Test
    void expiringMarksKeepsTheRecentOnes() throws IOException {
        File game = Files.createTempDirectory("mcmcp-storage").toFile();
        File marks = new File(game, "mcmcp-marks/example.org.json");
        marks.getParentFile().mkdirs();
        Files.write(marks.toPath(), ("[{\"id\":\"m1\",\"at\":" + (NOW - 9 * DAY) + "},"
            + "{\"id\":\"m2\",\"at\":" + (NOW - DAY) + "}]").getBytes(StandardCharsets.UTF_8));

        assertEquals(2, row(GameStorage.usage(game), "marks").get("items").getAsInt());
        assertEquals(1, GameStorage.expire(game, GameStorage.MARKS, 7, NOW).get("removed").getAsInt());
        JsonArray left = Json.parse(new String(Files.readAllBytes(marks.toPath()), StandardCharsets.UTF_8))
            .getAsJsonArray();
        assertEquals(1, left.size());
        assertEquals("m2", left.get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void anEmptyGameFolderReportsNothingRatherThanFailing() throws IOException {
        File game = Files.createTempDirectory("mcmcp-storage").toFile();
        JsonArray rows = GameStorage.usage(game);
        assertEquals(4, rows.size());
        assertEquals(0, row(rows, "dumps").get("items").getAsInt());
        assertFalse(row(rows, "dumps").has("oldestMs"));
    }
}
