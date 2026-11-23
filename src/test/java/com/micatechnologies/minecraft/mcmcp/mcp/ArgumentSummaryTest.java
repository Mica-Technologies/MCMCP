package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import org.junit.jupiter.api.Test;

class ArgumentSummaryTest {

    @Test
    void theCallThatHungAServerIsNamedWithItsEmptyCellList() {
        JsonObject arguments = Json.parse("{\"mode\":\"palette\",\"cells\":[],\"expectPalette\":[],"
            + "\"origin\":{\"x\":440,\"y\":78,\"z\":-2294},\"load\":true,\"onConflict\":\"abort\"}").getAsJsonObject();
        String summary = ArgumentSummary.of("server_set_blocks", arguments, 400);
        assertTrue(summary.startsWith("server_set_blocks {mode=palette, cells=[]"), summary);
        assertTrue(summary.contains("origin={\"x\":440,\"y\":78,\"z\":-2294}"), summary);
        assertTrue(summary.contains("load=true"), summary);
    }

    @Test
    void aLongListIsWrittenAsItsSizeAndNeverWalked() {
        JsonArray cells = new JsonArray();
        for (int i = 0; i < 100_000; i++) {
            cells.add(i);
        }
        JsonObject arguments = new JsonObject();
        arguments.add("cells", cells);
        arguments.addProperty("dimension", 0);
        assertEquals("server_set_blocks {cells=[100000 items], dimension=0}",
            ArgumentSummary.of("server_set_blocks", arguments, 400));
    }

    @Test
    void aLongStringIsCutAndALargeObjectCounted() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            text.append('x');
        }
        JsonObject big = new JsonObject();
        for (int i = 0; i < 20; i++) {
            big.addProperty("k" + i, i);
        }
        JsonObject arguments = new JsonObject();
        arguments.addProperty("indices", text.toString());
        arguments.add("palette", big);
        String summary = ArgumentSummary.of("t", arguments, 400);
        assertTrue(summary.contains("(500 chars)"), summary);
        assertTrue(summary.contains("palette={20 keys}"), summary);
        assertFalse(summary.contains("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"), summary);
    }

    @Test
    void theWholeLineIsCappedHoweverManyArgumentsThereAre() {
        JsonObject arguments = new JsonObject();
        for (int i = 0; i < 200; i++) {
            arguments.addProperty("argument" + i, "value" + i);
        }
        assertTrue(ArgumentSummary.of("t", arguments, 120).length() <= 120);
    }

    @Test
    void noArgumentsIsJustTheTool() {
        assertEquals("game_health", ArgumentSummary.of("game_health", new JsonObject(), 400));
        assertEquals("a call", ArgumentSummary.of(null, null, 400));
    }
}
