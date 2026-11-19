package com.micatechnologies.minecraft.mcmcp.companion.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanionPeerTest {

    private static CompanionPeer peer() {
        return new CompanionPeer(UUID.randomUUID(), "MicaAlex", 1 << 20, 8);
    }

    @Test
    void aNewSessionPastTheCapClosesTheOneIdleLongestNotTheOldest() {
        CompanionPeer peer = peer();
        McpSession orchestrator = peer.session("orchestrator", 3);
        McpSession scriptA = peer.session("a", 3);
        McpSession scriptB = peer.session("b", 3);
        orchestrator.touch(3_000L);
        scriptA.touch(1_000L);
        scriptB.touch(2_000L);

        assertNotNull(peer.session("c", 3));

        assertNull(peer.existingSession("a"), "the script idle longest makes room");
        assertSame(orchestrator, peer.existingSession("orchestrator"), "the oldest, still in use, stays");
    }

    @Test
    void aSessionWithACallRunningIsNeverClosedToMakeRoom() {
        // The orchestrator's session is the oldest, and was closed mid-call: "Session closed" (#211).
        CompanionPeer peer = peer();
        McpSession orchestrator = peer.session("orchestrator", 2);
        McpSession script = peer.session("a", 2);
        orchestrator.touch(1_000L);
        script.touch(2_000L);
        McpSession.CancellationToken running = orchestrator.beginRequest(new JsonPrimitive(7));

        assertNotNull(peer.session("b", 2));

        assertSame(orchestrator, peer.existingSession("orchestrator"));
        assertTrue(!running.isCancelled(), "its call keeps running");
        assertNull(peer.existingSession("a"));
    }

    @Test
    void whenEverySessionHasACallRunningNoneIsClosedAndTheNewOneWaits() {
        CompanionPeer peer = peer();
        peer.session("a", 2).beginRequest(new JsonPrimitive(1));
        peer.session("b", 2).beginRequest(new JsonPrimitive(1));

        assertNull(peer.session("c", 2), "the caller is told to retry");
        assertNotNull(peer.existingSession("a"));
        assertNotNull(peer.existingSession("b"));
    }
}
