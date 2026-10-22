package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import org.junit.jupiter.api.Test;

/** The {@code profile} argument and the figures it adds to a command result. */
class CommandProfileTest {

    private static final long MILLI = 1_000_000L;

    @Test
    void aCallWithoutProfileOrWithProfileFalseIsNotProfiled() {
        assertNull(CommandProfile.parse(null));
        assertNull(CommandProfile.parse(JsonNull.INSTANCE));
        assertNull(CommandProfile.parse(new JsonPrimitive(false)));
        assertNull(CommandProfile.parse(new JsonPrimitive("no")));
    }

    @Test
    void anEmptyObjectOrTrueProfilesWithTheDefaults() {
        CommandProfile.Options fromObject = CommandProfile.parse(new JsonObject());
        CommandProfile.Options fromTrue = CommandProfile.parse(new JsonPrimitive(true));
        assertNotNull(fromObject);
        assertNotNull(fromTrue);
        assertEquals(2, fromObject.intervalMillis);
        assertEquals(15, fromObject.top);
        assertEquals(2.0D, fromObject.minPercent);
        assertEquals(40, fromObject.maxLines);
        assertFalse(fromObject.wholeTick);
        assertEquals(fromObject.top, fromTrue.top);
    }

    @Test
    void optionsPastThePublishedBoundsAreClampedNotRefused() {
        CommandProfile.Options options = CommandProfile.parse(Json.parse(
            "{\"interval_ms\":0,\"top\":1000,\"min_percent\":99,\"max_lines\":1,\"scope\":\"tick\"}"));
        assertNotNull(options);
        assertEquals(1, options.intervalMillis);
        assertEquals(50, options.top);
        assertEquals(50.0D, options.minPercent);
        assertEquals(10, options.maxLines);
        assertTrue(options.wholeTick);
    }

    @Test
    void anObjectSentAsAStringIsReadAsTheObject() {
        CommandProfile.Options options = CommandProfile.parse(new JsonPrimitive("{\"top\": 5}"));
        assertNotNull(options);
        assertEquals(5, options.top);
    }

    @Test
    void oneCommandReportsItsTickAndItsOwnTimeAndNoBatchFields() {
        CommandProfile profile = new CommandProfile();
        profile.record(0, "fill 0 0 0 9 9 9 stone", 41L, 4_400 * MILLI);
        JsonObject json = new JsonObject();
        profile.addTo(json, tick -> tick == 41L ? 4_571 * MILLI : -1L, 15);

        assertEquals(4571.0D, json.get("tickMs").getAsDouble());
        assertEquals(4400.0D, json.get("commandMs").getAsDouble());
        assertFalse(json.has("ticks"));
        assertFalse(json.has("slowestCommands"));
        assertFalse(json.has("tickMsUnknown"));
    }

    @Test
    void aBatchAcrossTicksReportsTheWorstTickEachTickAndTheSlowestCommands() {
        CommandProfile profile = new CommandProfile();
        profile.record(0, "a", 10L, 2 * MILLI);
        profile.record(1, "b", 10L, 30 * MILLI);
        profile.record(2, "c", 11L, 900 * MILLI);
        JsonObject json = new JsonObject();
        profile.addTo(json, tick -> tick == 10L ? 40 * MILLI : 950 * MILLI, 2);

        assertEquals(950.0D, json.get("tickMs").getAsDouble(), "tickMs is the worst tick");
        assertEquals(932.0D, json.get("commandMs").getAsDouble(), "commandMs is the commands' total");
        assertEquals(2, json.get("ticks").getAsInt());
        JsonArray each = json.getAsJsonArray("tickMsEach");
        assertEquals(2, each.size());
        assertEquals(2, each.get(0).getAsJsonObject().get("commands").getAsInt());
        JsonArray slowest = json.getAsJsonArray("slowestCommands");
        assertEquals(2, slowest.size(), "top caps the list");
        assertEquals(2, slowest.get(0).getAsJsonObject().get("i").getAsInt());
        assertEquals("c", slowest.get(0).getAsJsonObject().get("command").getAsString());
        assertEquals(1, slowest.get(1).getAsJsonObject().get("i").getAsInt());
    }

    @Test
    void aTickThatNeverFinishedIsSaidToBeUnknownRatherThanReportedAsZero() {
        CommandProfile profile = new CommandProfile();
        profile.record(0, "a", 5L, MILLI);
        JsonObject json = new JsonObject();
        profile.addTo(json, tick -> -1L, 15);

        assertFalse(json.has("tickMs"));
        assertTrue(json.has("tickMsUnknown"));
        assertEquals(1.0D, json.get("commandMs").getAsDouble());
    }

    @Test
    void theTickToWaitForIsTheLastOneAnyCommandRanIn() {
        CommandProfile profile = new CommandProfile();
        assertEquals(-1L, profile.lastTick());
        profile.record(0, "a", 3L, MILLI);
        profile.record(1, "b", 4L, MILLI);
        assertEquals(4L, profile.lastTick());
    }
}
