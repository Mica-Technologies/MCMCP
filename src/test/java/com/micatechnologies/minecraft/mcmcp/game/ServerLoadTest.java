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

    @Test
    void aLowRealTpsHoldsJobsAndThenRefusesWritesHoweverShortTheTicks() {
        // health defaults: holdJobsBelowTps 15, refuseWritesBelowTps 8.
        ServerHealth calls = new ServerHealth();
        assertEquals(ServerHealth.State.OK, calls.update(0.2D, 40.0D, 19.5D, 0L, 60, 100, 85, 15, 8));
        assertEquals(ServerHealth.State.THROTTLED, calls.update(0.2D, 40.0D, 12.0D, 1L, 60, 100, 85, 15, 8),
            "a mean tick of 0.2 ms says nothing of the time between ticks");
        assertEquals(ServerHealth.State.PAUSED, calls.update(0.2D, 40.0D, 3.3D, 2L, 60, 100, 85, 15, 8));
        assertEquals(ServerHealth.State.OK,
            new ServerHealth().update(0.2D, 40.0D, -1.0D, 0L, 60, 100, 85, 15, 8), "not known: the tick time decides");
        assertEquals(ServerHealth.State.OK,
            new ServerHealth().update(0.2D, 40.0D, 3.3D, 0L, 60, 100, 85, 0, 0), "0 turns the TPS thresholds off");
    }

    @Test
    void theRealTpsLooksBackFiveSecondsOrToTheServersFirstTickAndNotBeforeASecond() {
        com.micatechnologies.minecraft.mcmcp.perf.TickClock clock = new com.micatechnologies.minecraft.mcmcp.perf.TickClock();
        long ms = 1_000_000L;
        for (int i = 1; i <= 200; i++) {
            clock.completed(ms, 1_000_000L * ms + i * 50L * ms);
        }
        long start = 1_000_000L * ms;
        assertEquals(-1.0D, ServerLoad.realTps(clock, start + 500L * ms, start), "half a second in: not known yet");
        assertEquals(20.0D, ServerLoad.realTps(clock, start + 2_000L * ms, start), 0.001D, "since the first tick");
        assertEquals(20.0D, ServerLoad.realTps(clock, start + 10_000L * ms, start), 0.001D, "the last five seconds");
        assertEquals(10.0D, ServerLoad.realTps(clock, start + 12_500L * ms, start), 0.001D,
            "2.5 s of the last 5 without a tick");
    }

    @Test
    void aStepDroppedOnAHealthyServerIsCalledBusyWithTheQueueNotOverloaded() {
        ServerLoad.setQueueDepth(() -> 7);
        try {
            JsonObject busy = ServerLoad.notStarted();
            assertEquals("server_busy", busy.get("reason").getAsString());
            assertEquals("ok", busy.get("health").getAsString());
            assertEquals(7, busy.get("queuedTasks").getAsInt());
            assertTrue(ServerLoad.notStartedCause().contains("healthy"), ServerLoad.notStartedCause());
            assertTrue(ServerLoad.notStartedCause().contains("7 task(s) waiting"), ServerLoad.notStartedCause());
            ServerLoad.measured(ServerLoad.Level.PAUSED, 120.0D, 40.0D, 10_000L);
            assertEquals("server_overloaded", ServerLoad.notStarted().get("reason").getAsString());
            assertTrue(ServerLoad.notStartedCause().startsWith("The server is paused"), ServerLoad.notStartedCause());
        }
        finally {
            ServerLoad.setQueueDepth(() -> -1);
        }
    }

    @Test
    void theRealTpsIsWhatTheDescriptionAndTheHealthReportGive() {
        ServerLoad.measured(ServerLoad.Level.PAUSED, 0.2D, 40.0D, 3.33D, 10_000L);
        assertEquals("paused (mean tick 0.2 ms, 3.3 TPS, heap 40%)", ServerLoad.describe());
        assertEquals(3.3D, ServerLoad.toJson().get("realTps").getAsDouble(), 0.001D);
        assertEquals(3.3D, ServerLoad.busy("server_overloaded").get("realTps").getAsDouble(), 0.001D);
        ServerLoad.measured(ServerLoad.Level.OK, 10.0D, 40.0D, 10_000L);
        assertFalse(ServerLoad.toJson().has("realTps"), "an integrated server's is not measured");
    }
}
