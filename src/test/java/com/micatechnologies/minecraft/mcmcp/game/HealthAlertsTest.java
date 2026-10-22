package com.micatechnologies.minecraft.mcmcp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When the server's health is announced to ops, and in what words (#133). */
class HealthAlertsTest {

    private static final long MINUTE = 60_000L;

    @Test
    void nothingIsSaidWhileTheServerStaysHealthy() {
        HealthAlerts alerts = new HealthAlerts();
        assertNull(alerts.poll(ServerLoad.Level.OK, 0L, MINUTE));
        assertNull(alerts.poll(ServerLoad.Level.OK, 10 * MINUTE, MINUTE));
    }

    @Test
    void eachChangeIsAnnouncedOnceAndRecoveryOnlyAfterTrouble() {
        HealthAlerts alerts = new HealthAlerts();
        assertEquals(ServerLoad.Level.THROTTLED, alerts.poll(ServerLoad.Level.THROTTLED, 0L, MINUTE));
        assertNull(alerts.poll(ServerLoad.Level.THROTTLED, 1_000L, MINUTE), "already said");
        assertEquals(ServerLoad.Level.PAUSED, alerts.poll(ServerLoad.Level.PAUSED, 2_000L, MINUTE),
            "a new level is news even within the minute");
        assertEquals(ServerLoad.Level.OK, alerts.poll(ServerLoad.Level.OK, 3_000L, MINUTE));
    }

    @Test
    void aServerHoveringAtAThresholdCostsOneLinePerLevelPerInterval() {
        HealthAlerts alerts = new HealthAlerts();
        int lines = 0;
        for (long t = 0L; t < MINUTE; t += 5_000L) {
            ServerLoad.Level level = (t / 5_000L) % 2 == 0 ? ServerLoad.Level.THROTTLED : ServerLoad.Level.OK;
            if (alerts.poll(level, t, MINUTE) != null) {
                lines++;
            }
        }
        assertEquals(2, lines, "throttled once, back to OK once, in a minute of flapping every 5 s");
    }

    @Test
    void aLevelTheIntervalHeldBackIsAnnouncedOnceTheIntervalIsOverIfTheServerIsStillThere() {
        HealthAlerts alerts = new HealthAlerts();
        alerts.poll(ServerLoad.Level.PAUSED, 0L, MINUTE);
        alerts.poll(ServerLoad.Level.OK, 10_000L, MINUTE);
        assertNull(alerts.poll(ServerLoad.Level.PAUSED, 20_000L, MINUTE), "paused was said 20 s ago");
        assertNull(alerts.poll(ServerLoad.Level.PAUSED, 59_999L, MINUTE));
        assertEquals(ServerLoad.Level.PAUSED, alerts.poll(ServerLoad.Level.PAUSED, MINUTE, MINUTE),
            "ops must not go on believing the OK");
    }

    @Test
    void aResetForgetsWhatWasSaidForTheNextServer() {
        HealthAlerts alerts = new HealthAlerts();
        alerts.poll(ServerLoad.Level.PAUSED, 0L, MINUTE);
        alerts.reset();
        assertNull(alerts.poll(ServerLoad.Level.OK, 1_000L, MINUTE), "no recovery to announce");
        assertEquals(ServerLoad.Level.PAUSED, alerts.poll(ServerLoad.Level.PAUSED, 2_000L, MINUTE));
    }

    @Test
    void anAlertGivesTheNumbersTheLongestCallAndTheWayToStopMcmcp() {
        String paused = HealthAlerts.message(ServerLoad.Level.PAUSED, 87.0D, 72.4D, 0L, "orchestrator: server_set_blocks (12 s)",
            null);
        assertTrue(paused.contains("mean tick 87.0 ms (11.5 TPS), heap 72%"), paused);
        assertTrue(paused.contains("Longest MCMCP call: orchestrator: server_set_blocks (12 s)"), paused);
        assertTrue(paused.endsWith("/mcmcp estop stops every MCMCP change."), paused);
        String stalled = HealthAlerts.message(ServerLoad.Level.STALLED, 0.0D, 0.0D, 14L, null, "/srv/mcmcp/stalls/a.txt");
        assertTrue(stalled.contains("no tick in 14 s"), stalled);
        assertTrue(stalled.contains("/srv/mcmcp/stalls/a.txt"), stalled);
        String ok = HealthAlerts.message(ServerLoad.Level.OK, 31.0D, 50.0D, 0L, "x: y (1 s)", null);
        assertFalse(ok.contains("estop"), "recovery needs no stop button: " + ok);
    }
}
