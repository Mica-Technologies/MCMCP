package com.micatechnologies.minecraft.mcmcp.link;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.perf.StallDetector;

/**
 * The {@code status} control frame: how an instance tells the orchestrator its game thread has
 * stopped, or started again.
 *
 * <p>Pushed rather than asked for, because asking means a request to the instance, and the point of
 * the frame is to be believed about an instance that may not answer requests. It is sent from the
 * link's writer thread, which does not need the game thread for anything.
 *
 * <p>Only on a change. The orchestrator keeps the last one it was told and does the arithmetic on
 * how long ago, so nothing needs to be re-sent while a stall goes on.
 */
public final class LinkStatus {

    private LinkStatus() {
    }

    public static JsonObject frame(StallDetector detector, long nowNanos) {
        JsonObject thread = new JsonObject();
        thread.addProperty(LinkProtocol.FIELD_THREAD_NAME, detector.threadName());
        thread.addProperty(LinkProtocol.FIELD_RESPONDING, !detector.isStalled());
        thread.addProperty(LinkProtocol.FIELD_SILENT_MILLIS, detector.silentMillis(nowNanos));

        JsonObject frame = new JsonObject();
        frame.addProperty(LinkProtocol.FIELD_TYPE, LinkProtocol.TYPE_STATUS);
        frame.addProperty(LinkProtocol.FIELD_LINK_PROTOCOL, LinkProtocol.VERSION);
        frame.add(LinkProtocol.FIELD_GAME_THREAD, thread);
        return frame;
    }
}
