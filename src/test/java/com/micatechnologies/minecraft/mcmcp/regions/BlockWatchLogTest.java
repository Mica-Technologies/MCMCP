package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import org.junit.jupiter.api.Test;

class BlockWatchLogTest {

    private static BlockWatchLog twoSignals(int maxChanges) {
        return new BlockWatchLog("w1", 0, new int[] {3203, 4, 480, 3205, 4, 480},
            new String[] {"csm:signal[color=red]", "csm:signal[color=green]"}, 1000, 5000, maxChanges);
    }

    @Test
    void only_changes_are_recorded_with_their_tick_position_and_before_and_after() {
        BlockWatchLog log = twoSignals(100);
        log.sample(1001, 0, "csm:signal[color=red]");
        log.sample(1080, 1, "csm:signal[color=yellow]");
        log.sample(1160, 1, "csm:signal[color=red]");
        log.sampled(1200);

        JsonObject json = log.toJson(0, true);

        assertEquals("[\"csm:signal[color=red]\",\"csm:signal[color=green]\",\"csm:signal[color=yellow]\"]",
            Json.write(json.get("palette")));
        assertEquals("[[1080,3205,4,480,1,2],[1160,3205,4,480,2,0]]", Json.write(json.get("changes")));
        assertEquals("[[3203,4,480,0],[3205,4,480,1]]", Json.write(json.get("initial")));
        assertEquals(1200, json.get("sampledTo").getAsLong());
        assertFalse(json.get("done").getAsBoolean());
    }

    @Test
    void reading_from_a_change_index_returns_only_what_is_new() {
        BlockWatchLog log = twoSignals(100);
        log.sample(1080, 1, "csm:signal[color=yellow]");
        int next = log.toJson(0, false).get("nextChange").getAsInt();
        log.sample(1160, 1, "csm:signal[color=red]");

        JsonObject json = log.toJson(next, false);

        assertEquals("[[1160,3205,4,480,2,0]]", Json.write(json.get("changes")));
        assertEquals(2, json.get("nextChange").getAsInt());
        assertFalse(json.has("initial"));
    }

    @Test
    void the_record_stops_at_its_limit_and_keeps_the_start_of_the_timeline() {
        BlockWatchLog log = twoSignals(2);
        log.sample(1010, 0, "a");
        log.sample(1020, 0, "b");
        log.sample(1030, 0, "c");
        log.sample(1040, 0, "d");

        JsonObject json = log.toJson(0, false);

        assertTrue(json.get("truncated").getAsBoolean());
        assertTrue(json.get("done").getAsBoolean());
        assertEquals("[[1010,3203,4,480,0,2],[1020,3203,4,480,2,3]]", Json.write(json.get("changes")));
    }

    @Test
    void nothing_is_recorded_after_a_watch_stops_and_the_first_reason_is_kept() {
        BlockWatchLog log = twoSignals(100);
        log.stop(1100, "stopped");
        log.stop(1200, "done");
        log.sample(1150, 0, "csm:signal[color=green]");

        JsonObject json = log.toJson(0, false);

        assertEquals("stopped", json.get("stopReason").getAsString());
        assertEquals(0, json.get("changeCount").getAsInt());
        assertEquals(1100, json.get("sampledTo").getAsLong());
    }
}
