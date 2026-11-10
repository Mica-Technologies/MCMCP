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

    private static CallerId direct(String session) {
        return CallerId.of(new McpSession(session, 0L), null);
    }

    @Test
    void theHoldersOwnSecondConnectionIsLetThroughOnceItPresentsTheLocksToken() {
        InputClaim claim = new InputClaim();
        CallerId agent = throughOrchestrator("c4", "claude-code");
        CallerId script = direct("companion-script");
        CallerId stranger = direct("someone-else");
        claim.claimed(agent, "finishing the fire alarm", true, 1_500_000L);

        assertNotNull(claim.refusalFor(script, 1_000L), "until it joins it is anyone");
        assertTrue(claim.refusalFor(script, 1_000L).contains("op 'join'"));
        String token = claim.tokenFor(agent, 1_000L);
        assertNotNull(token);
        assertEquals(16, token.length());

        assertNull(claim.join(script, token, 1_000L));
        assertNull(claim.refusalFor(script, 2_000L), "the script now drives the client as its agent does");
        assertNotNull(claim.refusalFor(stranger, 2_000L), "and nobody else got in with it");
        assertTrue(claim.status(script, 2_000L).has("joined"));
        assertFalse(claim.status(script, 2_000L).get("yours").getAsBoolean());
        assertTrue(claim.heldExclusivelyByAnother(script, 2_000L), "the lock is still the agent's to set or release");
    }

    @Test
    void theTokenIsToldOnlyToTheHolderAndAWrongOneLetsNobodyIn() {
        InputClaim claim = new InputClaim();
        CallerId agent = throughOrchestrator("c4", "claude-code");
        CallerId stranger = direct("someone-else");
        claim.claimed(agent, "", true, 60_000L);

        assertNull(claim.tokenFor(stranger, 1_000L));
        assertFalse(claim.status(stranger, 1_000L).has("token"));
        assertEquals(claim.tokenFor(agent, 1_000L), claim.status(agent, 1_000L).get("token").getAsString());
        assertNotNull(claim.join(stranger, "0000000000000000", 1_000L));
        assertNotNull(claim.join(stranger, null, 1_000L));
        assertNotNull(claim.refusalFor(stranger, 1_000L));
    }

    @Test
    void whoWasLetInStaysInWhileTheHolderExtendsAndIsOutWhenTheLockEnds() {
        InputClaim claim = new InputClaim();
        CallerId agent = throughOrchestrator("c4", "claude-code");
        CallerId script = direct("companion-script");
        claim.claimed(agent, "", true, 60_000L);
        String token = claim.tokenFor(agent, 1_000L);
        assertNull(claim.join(script, token, 1_000L));

        claim.claimed(agent, "", true, 120_000L);
        assertEquals(token, claim.tokenFor(agent, 70_000L), "extending keeps the token");
        assertNull(claim.refusalFor(script, 70_000L));

        claim.released();
        claim.claimed(throughOrchestrator("c9", "towers"), "", true, 200_000L);
        assertNotNull(claim.refusalFor(script, 130_000L), "another agent's lock is not the one it joined");
        assertNotNull(claim.join(script, token, 130_000L), "and the old token opens nothing");
    }

    @Test
    void aLockThatIsNotExclusiveHasNoTokenAndNothingToJoin() {
        InputClaim claim = new InputClaim();
        CallerId agent = throughOrchestrator("c4", "claude-code");
        claim.claimed(agent, "", false, 60_000L);
        assertNull(claim.tokenFor(agent, 1_000L));
        assertNotNull(claim.join(direct("companion-script"), "anything", 1_000L));
        assertNull(claim.refusalFor(direct("companion-script"), 1_000L), "which is fine: nobody is refused");
    }
}
