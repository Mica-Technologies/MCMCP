package com.micatechnologies.minecraft.mcmcp.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.perf.StallDetector;
import org.junit.jupiter.api.Test;

/** The frame that tells the orchestrator a game thread has stopped. */
class LinkStatusTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aStatusFrameIsAControlFrameTheOrchestratorReadsFieldForField() {
        StallDetector detector = new StallDetector("Client thread");
        detector.setThresholdMillis(10_000L);
        detector.observe(3L, SECOND);
        detector.observe(3L, 12 * SECOND);

        JsonObject frame = LinkStatus.frame(detector, 12 * SECOND);

        assertTrue(LinkFraming.isControlFrame(frame));
        // The exact bytes protocol.rs's test parses. Change one, change the other.
        assertEquals("{\"type\":\"status\",\"linkProtocol\":1,\"gameThread\":"
            + "{\"name\":\"Client thread\",\"responding\":false,\"silentMillis\":11000}}", Json.write(frame));
    }

    @Test
    void aRecoveredThreadIsReportedAsResponding() {
        StallDetector detector = new StallDetector("Client thread");
        detector.setThresholdMillis(10_000L);
        detector.observe(3L, SECOND);
        detector.observe(3L, 12 * SECOND);
        detector.observe(4L, 13 * SECOND);

        JsonObject thread = LinkStatus.frame(detector, 13 * SECOND).getAsJsonObject(LinkProtocol.FIELD_GAME_THREAD);

        assertTrue(thread.get(LinkProtocol.FIELD_RESPONDING).getAsBoolean());
        assertEquals(0L, thread.get(LinkProtocol.FIELD_SILENT_MILLIS).getAsLong());
    }
}
