package com.micatechnologies.minecraft.mcmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

/** The session cap: what a full endpoint evicts, what it never evicts, and what it says when it refuses. */
class McpSessionManagerTest {

    private static final long IDLE_TIMEOUT = 30L * 60L * 1000L;
    private static final long GRACE = McpSessionManager.EVICTION_GRACE_MILLIS;

    @Test
    void aFullEndpointEvictsTheLongestIdleSessionInsteadOfRefusing() {
        McpSessionManager sessions = new McpSessionManager(IDLE_TIMEOUT, 2);
        McpSession older = sessions.create(false, 0L);
        McpSession newer = sessions.create(false, 10_000L);

        McpSession arriving = sessions.create(false, 10_000L + GRACE * 2);

        assertNull(sessions.get(older.getId()), "the longest-idle session made room");
        assertNotNull(sessions.get(newer.getId()));
        assertNotNull(sessions.get(arriving.getId()));
        assertEquals(2, sessions.count());
    }

    @Test
    void anOrchestratorLinkIsNeverEvicted() {
        McpSessionManager sessions = new McpSessionManager(IDLE_TIMEOUT, 2);
        McpSession link = sessions.create(true, 0L);
        McpSession script = sessions.create(false, 5_000L);

        sessions.create(false, GRACE * 3);

        assertNotNull(sessions.get(link.getId()), "evicting the link drops every game it routes");
        assertNull(sessions.get(script.getId()));
    }

    @Test
    void aSessionWithARequestStillRunningIsNeverEvicted() {
        // A long client_wait does not touch its session while it runs, so it looks idle.
        McpSessionManager sessions = new McpSessionManager(IDLE_TIMEOUT, 1);
        McpSession waiting = sessions.create(false, 0L);
        waiting.beginRequest(new JsonPrimitive(1));

        JsonRpcException refused = assertThrows(JsonRpcException.class,
            () -> sessions.create(false, GRACE * 3));
        assertTrue(refused.getMessage().contains("request still running"), refused.getMessage());
    }

    @Test
    void aRefusalSaysWhenASlotFreesAndHowToRaiseTheCap() {
        McpSessionManager sessions = new McpSessionManager(IDLE_TIMEOUT, 1);
        sessions.create(false, 0L);

        JsonRpcException refused = assertThrows(JsonRpcException.class,
            () -> sessions.create(false, GRACE - 20_000L));
        String message = refused.getMessage();
        assertTrue(message.contains("A slot frees in about 20s"), message);
        assertTrue(message.contains("limits.maxSessions"), message);
        assertTrue(message.contains("DELETE"), message);
    }
}
