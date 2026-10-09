package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the request journal writes, what it reads back after a crash, and what it keeps. */
class RequestJournalTest {

    private static final long NOW = 1_800_000_000_000L;

    private static final class Settings implements RequestJournal.Settings {

        boolean enabled = true;
        long maxBytes = 1_000_000L;
        int files = 3;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public long maxFileBytes() {
            return maxBytes;
        }

        @Override
        public int files() {
            return files;
        }
    }

    private static File directory() throws IOException {
        return Files.createTempDirectory("mcmcp-journal").toFile();
    }

    private static List<String> lines(File file) throws IOException {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void aCallThatStartedAndNeverEndedIsNamedByTheNextRun() throws IOException {
        File dir = directory();
        Settings settings = new Settings();
        RequestJournal first = new RequestJournal(dir, "client", settings);
        first.open(42L, "v1", NOW);
        first.start(1L, "client_wait", Json.obj("ticks", new com.google.gson.JsonPrimitive(20)), "agent", null, NOW);
        first.end(1L, "ok", 1000L, 50L, null, null, NOW + 1000);
        JsonObject box = new JsonObject();
        box.addProperty("x", 0);
        box.addProperty("y", 0);
        box.addProperty("z", 0);
        box.addProperty("toX", 59);
        box.addProperty("toY", 12);
        box.addProperty("toZ", 59);
        first.start(2L, "client_get_blocks", box, "agent", null, NOW + 2000);
        // The process dies here: no end line, no close line.

        JsonObject previous = new RequestJournal(dir, "client", settings).previousRun();
        assertTrue(previous.get("found").getAsBoolean());
        assertFalse(previous.get("endedCleanly").getAsBoolean());
        assertEquals(42L, previous.get("pid").getAsLong());
        JsonArray unfinished = previous.getAsJsonArray("unfinishedCalls");
        assertEquals(1, unfinished.size());
        JsonObject call = unfinished.get(0).getAsJsonObject();
        assertEquals("client_get_blocks", call.get("tool").getAsString());
        assertEquals(60L * 13L * 60L, call.getAsJsonObject("args").get("~volume").getAsLong());
    }

    @Test
    void aRunThatClosedCleanlyLeavesNothingUnfinished() throws IOException {
        File dir = directory();
        Settings settings = new Settings();
        RequestJournal first = new RequestJournal(dir, "server", settings);
        first.open(7L, "v1", NOW);
        first.start(1L, "server_get_block", new JsonObject(), null, null, NOW);
        first.end(1L, "error", 3L, -1L, null, null, NOW);
        first.close(NOW + 10);

        JsonObject previous = new RequestJournal(dir, "server", settings).previousRun();
        assertTrue(previous.get("endedCleanly").getAsBoolean());
        assertEquals(0, previous.getAsJsonArray("unfinishedCalls").size());
        assertEquals(1, previous.get("calls").getAsInt());
    }

    @Test
    void aHalfWrittenLastLineIsSkippedRatherThanFailingTheRead() throws IOException {
        File dir = directory();
        Settings settings = new Settings();
        RequestJournal first = new RequestJournal(dir, "client", settings);
        first.open(1L, "v1", NOW);
        first.start(5L, "client_sequence", new JsonObject(), null, null, NOW);
        Files.write(first.getFile().toPath(), "{\"t\":\"2026-10-09T00:00:00.000Z\",\"ev\":\"end\",\"id\":5,\"sta"
            .getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);

        JsonObject previous = new RequestJournal(dir, "client", settings).previousRun();
        assertEquals(1, previous.getAsJsonArray("unfinishedCalls").size(),
            "the end line was cut off, so the call still counts as unfinished");
        assertEquals(1, previous.get("unreadableLines").getAsInt());
    }

    @Test
    void onlyTheLastRunInTheFileIsReported() {
        List<String> lines = Arrays.asList(
            "{\"t\":\"a\",\"ev\":\"open\",\"pid\":1,\"version\":\"v1\"}",
            "{\"t\":\"b\",\"ev\":\"start\",\"id\":1,\"tool\":\"old_call\"}",
            "{\"t\":\"c\",\"ev\":\"open\",\"pid\":2,\"version\":\"v2\"}",
            "{\"t\":\"d\",\"ev\":\"start\",\"id\":1,\"tool\":\"new_call\"}",
            "{\"t\":\"e\",\"ev\":\"end\",\"id\":1,\"status\":\"ok\"}");
        JsonObject run = RequestJournal.scan(lines);
        assertEquals(2L, run.get("pid").getAsLong());
        assertEquals(0, run.getAsJsonArray("unfinishedCalls").size(),
            "a call left open by an earlier run is that run's, not this one's");
    }

    @Test
    void aDisabledJournalSaysSoInsteadOfReportingAnAllClear() throws IOException {
        Settings settings = new Settings();
        settings.enabled = false;
        RequestJournal journal = new RequestJournal(directory(), "client", settings);
        journal.open(1L, "v1", NOW);
        journal.start(1L, "client_get_blocks", new JsonObject(), null, null, NOW);
        assertFalse(journal.getFile().exists(), "nothing is written while the journal is off");

        JsonObject previous = journal.previousRun();
        assertEquals("disabled", previous.get("journal").getAsString());
        assertFalse(previous.get("found").getAsBoolean());
        assertTrue(previous.has("note"));
    }

    @Test
    void turningTheJournalOnLaterStillMarksWhereTheRunBegan() throws IOException {
        File dir = directory();
        Settings settings = new Settings();
        settings.enabled = false;
        RequestJournal journal = new RequestJournal(dir, "client", settings);
        journal.open(9L, "v1", NOW);
        settings.enabled = true;
        journal.start(1L, "client_look", new JsonObject(), null, null, NOW);

        List<String> written = lines(journal.getFile());
        assertEquals(2, written.size());
        assertTrue(written.get(0).contains("\"ev\":\"open\""));
        assertEquals(9L, RequestJournal.scan(written).get("pid").getAsLong());
    }

    @Test
    void argumentsAreSummarisedNeverStoredWhole() {
        JsonObject arguments = new JsonObject();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longText.append('x');
        }
        arguments.addProperty("command", longText.toString());
        JsonArray blocks = new JsonArray();
        for (int i = 0; i < 5000; i++) {
            blocks.add(Json.obj("block", "minecraft:stone"));
        }
        arguments.add("blocks", blocks);
        arguments.addProperty("dimension", 0);

        JsonObject summary = RequestJournal.summarizeArguments(arguments);
        assertEquals(5000, summary.getAsJsonObject("blocks").get("~len").getAsInt());
        assertTrue(summary.get("command").getAsString().length() < 100);
        assertTrue(summary.get("command").getAsString().endsWith("(300)"));
        assertEquals(0, summary.get("dimension").getAsInt());
    }

    @Test
    void aSummaryTooLongForOneLineFallsBackToTheArgumentNames() {
        JsonObject arguments = new JsonObject();
        for (int i = 0; i < 40; i++) {
            arguments.addProperty("argument" + i, "a value that is not short " + i);
        }
        JsonObject summary = RequestJournal.summarizeArguments(arguments);
        assertTrue(summary.has("~keys"));
        assertTrue(Json.write(summary).length() < 2 * RequestJournal.MAX_SUMMARY_CHARS);
    }

    @Test
    void anEndLineCarriesTheResultCountersAndTheStepTimings() throws IOException {
        Settings settings = new Settings();
        RequestJournal journal = new RequestJournal(directory(), "server", settings);
        JsonObject result = new JsonObject();
        result.addProperty("written", 120);
        result.addProperty("note", "text is not a counter");
        result.add("positions", new JsonArray());
        JsonObject steps = Json.obj("client_wait:chunksLoaded",
            Json.obj("n", new com.google.gson.JsonPrimitive(3)));
        journal.end(4L, "ok", 25L, 900L, result, steps, NOW);

        JsonObject line = Json.parse(lines(journal.getFile()).get(0)).getAsJsonObject();
        assertEquals(120, line.getAsJsonObject("counts").get("written").getAsInt());
        assertNull(line.getAsJsonObject("counts").get("note"));
        assertEquals(0, line.getAsJsonObject("counts").get("positions.len").getAsInt());
        assertTrue(line.getAsJsonObject("steps").has("client_wait:chunksLoaded"));
        assertEquals(900L, line.get("bytes").getAsLong());
    }

    @Test
    void rotationKeepsTheConfiguredNumberOfFilesAndNoMore() throws IOException {
        Settings settings = new Settings();
        settings.maxBytes = 400L;
        settings.files = 3;
        RequestJournal journal = new RequestJournal(directory(), "client", settings);
        for (int i = 0; i < 60; i++) {
            journal.start(i, "client_player_state", new JsonObject(), null, null, NOW);
        }
        List<File> files = journal.files();
        assertEquals(3, files.size());
        assertFalse(new File(journal.getFile().getParentFile(), journal.getFile().getName() + ".3").exists());
        for (File file : files) {
            assertTrue(file.length() <= 400L + 200L, file + " stays near the size limit");
        }
    }

    @Test
    void aJournalThatCannotWriteGivesUpQuietlyAndSaysSoOnce() throws IOException {
        File blocker = Files.createTempFile("mcmcp-journal-blocker", ".tmp").toFile();
        // A file where the directory should be: every write fails.
        RequestJournal journal = new RequestJournal(blocker, "client", new Settings());
        int[] told = {0};
        journal.setOnFailure(() -> told[0]++);
        for (int i = 0; i < 10; i++) {
            journal.start(i, "client_look", new JsonObject(), null, null, NOW);
        }
        assertTrue(journal.isBroken());
        assertEquals(1, told[0]);
        assertEquals("failed", journal.previousRun().get("journal").getAsString());
    }
}
