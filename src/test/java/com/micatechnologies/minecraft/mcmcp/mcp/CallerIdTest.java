package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class CallerIdTest {

    private static JsonObject stamped(String id, String name) {
        return Json.parse("{\"_meta\":{\"mcmcp/caller\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\"}}}")
            .getAsJsonObject();
    }

    private static McpSession companion() {
        McpSession session = new McpSession("player/link", 0L);
        session.setPrincipal(Principal.companion("u1", "Builder", Collections.singleton("load")));
        return session;
    }

    @Test
    void twoAgentsOnOnePlayersCompanionSessionAreTwoCallers() {
        // #142: the orchestrator routes every agent's server calls through the player's one companion
        // session, and its stamp, forwarded unchanged, is what tells them apart.
        McpSession session = companion();

        CallerId a = CallerId.of(session, stamped("c1", "agent-a"));
        CallerId b = CallerId.of(session, stamped("c2", "agent-b"));

        assertEquals("agent:c1", a.id());
        assertNotEquals(a.id(), b.id());
    }

    @Test
    void anUnstampedCompanionCallIsItsSessions() {
        assertEquals("session:player/link", CallerId.of(companion(), null).id());
    }

    @Test
    void anAgentsStampPassedOnByTheClientNamesTheSameAgentOnTheServer() {
        // #144: bringPlayer's teleport is the agent's own, so its lease must not refuse it.
        McpSession link = new McpSession("link", 0L, true);
        CallerId onClient = CallerId.of(link, stamped("c1", "agent-a"));

        JsonObject forwarded = new JsonObject();
        forwarded.add("_meta", onClient.stamp());

        assertEquals("agent:c1", CallerId.of(companion(), forwarded).id());
    }

    @Test
    void aStampOnAPlainEndpointSessionIsNotTrusted() {
        // Only the orchestrator link (and, through it, the companion) carries stamps it vouches for.
        McpSession session = new McpSession("direct", 0L);
        assertEquals("session:direct", CallerId.of(session, stamped("c1", "spoof")).id());
    }
}
