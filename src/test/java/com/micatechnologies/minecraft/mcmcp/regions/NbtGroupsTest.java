package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import org.junit.jupiter.api.Test;

/** Grouping tile entities by the values of their NBT keys. */
class NbtGroupsTest {

    private static JsonObject tag(String json) {
        return Json.parse(json).getAsJsonObject();
    }

    @Test
    void entitiesWithTheSameValuesShareOneGroupLargestFirst() {
        NbtGroups groups = new NbtGroups(3);
        groups.add("csm:thermostat", tag("{\"tU\":20,\"pU\":1}"), 0, 64, 0);
        groups.add("csm:thermostat", tag("{\"tU\":22,\"pU\":1}"), 1, 64, 0);
        groups.add("csm:thermostat", tag("{\"tU\":22,\"pU\":1}"), 2, 64, 0);

        JsonArray json = groups.toJson(10);
        assertEquals(2, json.size());
        assertEquals("{\"tU\":22,\"pU\":1}", Json.write(json.get(0).getAsJsonObject().get("value")));
        assertEquals(2, json.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals("[[1,64,0],[2,64,0]]", Json.write(json.get(0).getAsJsonObject().get("samples")));
        assertEquals(3, groups.entities());
    }

    @Test
    void theSameValuesOnDifferentBlocksAreDifferentGroups() {
        NbtGroups groups = new NbtGroups(3);
        groups.add("csm:alarm_horn", tag("{\"a\":1}"), 0, 0, 0);
        groups.add("csm:alarm_strobe", tag("{\"a\":1}"), 0, 1, 0);
        assertEquals(2, groups.size());
    }

    @Test
    void samplesStopAtTheirLimitWhileTheCountGoesOn() {
        NbtGroups groups = new NbtGroups(1);
        for (int i = 0; i < 5; i++) {
            groups.add("b", tag("{}"), i, 0, 0);
        }
        JsonObject only = groups.toJson(10).get(0).getAsJsonObject();
        assertEquals(5, only.get("count").getAsInt());
        assertEquals(1, only.getAsJsonArray("samples").size());
    }

    @Test
    void theLimitCutsTheSmallestGroups() {
        NbtGroups groups = new NbtGroups(0);
        groups.add("b", tag("{\"v\":1}"), 0, 0, 0);
        groups.add("b", tag("{\"v\":2}"), 0, 0, 0);
        groups.add("b", tag("{\"v\":2}"), 0, 0, 0);
        JsonArray json = groups.toJson(1);
        assertEquals(1, json.size());
        assertEquals(2, json.get(0).getAsJsonObject().get("count").getAsInt());
    }
}
