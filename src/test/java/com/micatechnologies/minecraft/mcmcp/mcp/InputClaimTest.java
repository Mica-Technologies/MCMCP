package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import org.junit.jupiter.api.Test;

class InputClaimTest {

    private static CallerId throughOrchestrator(String connection, String name) {
        McpSession link = new McpSession("link", 0L, true);
        return CallerId.of(link, Json.parse("{\"_meta\":{\"mcmcp/caller\":{\"id\":\"" + connection
            + "\",\"name\":\"" + name + "\"}}}").getAsJsonObject());
    }

    @Test
    void twoAgentsOnOneOrchestratorLinkAreTwoCallersAndOneAgentIsOne() {
        CallerId a = throughOrchestrator("c1", "agent");
        CallerId b = throughOrchestrator("c2", "agent");
        assertNotEquals(a.id(), b.id(), "the same client name on two connections is still two agents");
        assertEquals(a.id(), throughOrchestrator("c1", "agent").id());
        assertEquals("agent (c1)", a.label());
    }

    @Test
    void onlyTheOrchestratorLinkMayNameItsCaller() {
        // A direct client could otherwise claim to be whoever holds the lock.
        McpSession direct = new McpSession("direct", 0L);
        CallerId claimed = CallerId.of(direct, Json.parse("{\"_meta\":{\"mcmcp/caller\":{\"id\":\"c1\"}}}")
            .getAsJsonObject());
        assertEquals("session:direct", claimed.id());
    }

    @Test
    void anExclusiveLockRefusesOtherAgentsButNotItsHolder() {
        InputClaim claim = new InputClaim();
        CallerId holder = throughOrchestrator("c1", "lifts");
        CallerId other = throughOrchestrator("c2", "towers");
        claim.claimed(holder, "lift ride test", true, 60_000L);

        assertNull(claim.refusalFor(holder, 1_000L));
        String refusal = claim.refusalFor(other, 1_000L);
        assertNotNull(refusal);
        assertTrue(refusal.contains("lifts (c1)") && refusal.contains("lift ride test"), refusal);
        assertEquals(59_000L, claim.busy(1_000L).get("retryAfterMs").getAsLong());
        assertTrue(claim.heldExclusivelyByAnother(other, 1_000L));
    }

    @Test
    void aSharedLockIsReportedButRefusesNobody() {
        InputClaim claim = new InputClaim();
        claim.claimed(throughOrchestrator("c1", "lifts"), "", false, 60_000L);
        CallerId other = throughOrchestrator("c2", "towers");

        assertNull(claim.refusalFor(other, 1_000L));
        JsonObject status = claim.status(other, 1_000L);
        assertTrue(status.get("locked").getAsBoolean());
        assertFalse(status.get("yours").getAsBoolean());
        assertEquals(59, status.get("secondsLeft").getAsInt());
    }

    @Test
    void anExpiredOrReleasedLockHoldsNobodyOff() {
        InputClaim claim = new InputClaim();
        CallerId other = throughOrchestrator("c2", "towers");
        claim.claimed(throughOrchestrator("c1", "lifts"), "", true, 60_000L);
        assertNull(claim.refusalFor(other, 60_000L), "it ends at its expiry, even before the client notices");
        assertFalse(claim.status(other, 60_000L).get("locked").getAsBoolean());

        claim.claimed(throughOrchestrator("c1", "lifts"), "", true, 120_000L);
        claim.released();
        assertNull(claim.refusalFor(other, 1_000L));
    }
}
