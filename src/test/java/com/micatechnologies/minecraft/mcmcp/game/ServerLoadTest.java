package com.micatechnologies.minecraft.mcmcp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ServerHealth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the server's load says to whoever asks: the level, the refusal, the wait (#133). */
class ServerLoadTest {

    @BeforeEach
    @AfterEach
    void startFresh() {
        ServerLoad.reset();
    }

    @Test
    void aStallOutranksAnyMeasurementAndEndsWithIt() {
        ServerLoad.measured(ServerLoad.Level.THROTTLED, 45.0D, 50.0D, 10_000L);
        ServerLoad.stalled(true, 12_000L);
        assertEquals(ServerLoad.Level.STALLED, ServerLoad.level());
        ServerLoad.measured(ServerLoad.Level.OK, 10.0D, 50.0D, 0L);
        assertEquals(ServerLoad.Level.STALLED, ServerLoad.level(), "a measurement taken before the stall ended");
        ServerLoad.stalled(false, 0L);
        assertEquals(ServerLoad.Level.OK, ServerLoad.level());
    }

    @Test
    void writesAreRefusedOnlyWhilePausedOrStalledButJobsHoldBackFromThrottledOn() {
        assertFalse(ServerLoad.refusesWrites());
        assertFalse(ServerLoad.holdsJobs());
        ServerLoad.measured(ServerLoad.Level.THROTTLED, 45.0D, 50.0D, 10_000L);
        assertFalse(ServerLoad.refusesWrites(), "throttled slows MCMCP down; it does not refuse");
        assertTrue(ServerLoad.holdsJobs());
        ServerLoad.measured(ServerLoad.Level.PAUSED, 80.0D, 50.0D, 10_000L);
        assertTrue(ServerLoad.refusesWrites());
        ServerLoad.measured(ServerLoad.Level.OK, 20.0D, 50.0D, 0L);
        ServerLoad.stalled(true, 15_000L);
        assertTrue(ServerLoad.refusesWrites());
        assertTrue(ServerLoad.holdsJobs());
    }

    @Test
    void listenersHearEachChangeOfLevelOnceAndNotTheSameLevelAgain() {
        List<String> heard = new ArrayList<>();
        ServerLoad.Listener listener = (now, before) -> heard.add(before.id() + ">" + now.id());
        ServerLoad.addListener(listener);
        try {
            ServerLoad.measured(ServerLoad.Level.PAUSED, 80.0D, 50.0D, 10_000L);
            ServerLoad.measured(ServerLoad.Level.PAUSED, 82.0D, 50.0D, 9_000L);
            ServerLoad.stalled(true, 11_000L);
            ServerLoad.stalled(true, 12_000L);
            ServerLoad.stalled(false, 0L);
        }
        finally {
            ServerLoad.removeListener(listener);
        }
        assertEquals("[ok>paused, paused>stalled, stalled>paused]", heard.toString());
    }

    @Test
    void aRefusedCallIsToldToWaitLongerTheWorseTheServerIs() {
        assertEquals(2_000L, ServerLoad.retryAfterMillis());
        ServerLoad.measured(ServerLoad.Level.PAUSED, 80.0D, 50.0D, 1_000L);
        assertEquals(5_000L, ServerLoad.retryAfterMillis(), "no sooner than 5 s while paused");
        ServerLoad.measured(ServerLoad.Level.PAUSED, 80.0D, 50.0D, 8_000L);
        assertEquals(8_000L, ServerLoad.retryAfterMillis(), "the earliest it could recover");
        ServerLoad.stalled(true, 20_000L);
        assertEquals(15_000L, ServerLoad.retryAfterMillis());
        JsonObject busy = ServerLoad.busy("server_overloaded");
        assertEquals("server_overloaded", busy.get("reason").getAsString());
        assertEquals("stalled", busy.get("health").getAsString());
        assertEquals(20L, busy.get("stalledSeconds").getAsLong());
        assertEquals(15_000L, busy.get("retryAfterMs").getAsLong());
    }

    @Test
    void callsAndJobsGoByLooserThresholdsThanChunkLoading() {
        // The defaults: chunks.throttleMspt 40, pauseMspt 50; health.holdJobsMspt 60, refuseWritesMspt 100;
        // chunks.pauseHeapPercent 85 for both.
        ServerHealth chunks = new ServerHealth();
        ServerHealth calls = new ServerHealth();
        assertEquals(ServerHealth.State.PAUSED, chunks.update(55.0D, 40.0D, 0L, 40, 50, 85));
        assertEquals(ServerLoad.Level.OK, ServerLoad.fromHealth(calls.update(55.0D, 40.0D, 0L, 60, 100, 85)),
            "a server too busy to load chunks can still take its agents' writes");
        assertEquals(ServerLoad.Level.THROTTLED, ServerLoad.fromHealth(calls.update(70.0D, 40.0D, 1L, 60, 100, 85)),
            "past holdJobsMspt, jobs wait");
        assertEquals(ServerLoad.Level.PAUSED, ServerLoad.fromHealth(calls.update(120.0D, 40.0D, 2L, 60, 100, 85)),
            "past refuseWritesMspt, writes are refused");
        assertEquals(ServerLoad.Level.PAUSED,
            ServerLoad.fromHealth(new ServerHealth().update(20.0D, 90.0D, 0L, 60, 100, 85)),
            "a nearly full heap refuses writes however fast the ticks are");
    }

    @Test
    void theDescriptionGivesTickTimeTpsAndHeapOrHowLongTheStallHasLasted() {
        ServerLoad.measured(ServerLoad.Level.PAUSED, 80.0D, 71.6D, 10_000L);
        assertEquals("paused (mean tick 80.0 ms, 12.5 TPS, heap 72%)", ServerLoad.describe());
        ServerLoad.stalled(true, 14_500L);
        assertEquals("stalled: the server thread has not finished a tick in 14 s", ServerLoad.describe());
    }
}
