package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayerLeasesTest {

    private static final long MINUTE = 60_000L;
    private static final Map<Integer, String> NONE = Collections.emptyMap();

    @Test
    void anotherAgentsItemUseIsRefusedWhileOneAgentHoldsTheLease() {
        PlayerLeases leases = new PlayerLeases();
        assertNotNull(leases.acquire("Builder", "a", "agent a", "linking alarms", NONE, MINUTE, 0L).granted);

        String refusal = leases.refusalFor("builder", "b", 1_000L);

        assertNotNull(refusal, "names are matched without case");
        assertTrue(refusal.contains("agent a") && refusal.contains("linking alarms"), refusal);
        assertNull(leases.refusalFor("Builder", "a", 1_000L), "the holder itself is never refused");
    }

    @Test
    void aHelperScriptOfTheSamePlayerActsUnderTheLeaseWithItsToken() {
        PlayerLeases leases = new PlayerLeases();
        PlayerLeases.Lease lease = leases.acquire("MicaAlex", "player:u1~agent:c4", "agent c4", "alarm test",
            NONE, MINUTE, 0L).granted;
        assertNotNull(lease);
        assertEquals(32, lease.token.length());
        String script = "player:u1~session:u1/s9";

        assertNotNull(leases.refusalFor("MicaAlex", script, null, 1_000L), "without the token it is another caller");
        assertNotNull(leases.refusalFor("MicaAlex", script, "not-the-token", 1_000L));
        assertNull(leases.refusalFor("MicaAlex", script, lease.token, 1_000L), "with it, it is the holder");
        assertTrue(leases.holds("MicaAlex", script, lease.token, 1_000L));
        assertNotNull(leases.release("MicaAlex", script, lease.token, false), "and it may end the lease");
    }

    @Test
    void aLeaseTokenBuysNothingOnAnotherPlayersConnection() {
        PlayerLeases leases = new PlayerLeases();
        PlayerLeases.Lease lease = leases.acquire("MicaAlex", "player:u1~agent:c4", "agent c4", "", NONE, MINUTE,
            0L).granted;

        assertNotNull(leases.refusalFor("MicaAlex", "player:u2~agent:x", lease.token, 1_000L),
            "access comes from the connection, not from knowing the token");
        assertNotNull(leases.refusalFor("MicaAlex", "endpoint/session:s1", lease.token, 1_000L));
        assertNull(leases.release("MicaAlex", "player:u2~agent:x", lease.token, false));
    }

    @Test
    void directEndpointCallersShareOneTokenScope() {
        PlayerLeases leases = new PlayerLeases();
        PlayerLeases.Lease lease = leases.acquire("Builder", "endpoint/session:a", "a", "", NONE, MINUTE, 0L).granted;

        assertNull(leases.refusalFor("Builder", "endpoint/session:b", lease.token, 1_000L));
        assertNotNull(leases.refusalFor("Builder", "endpoint/session:b", null, 1_000L));
    }

    @Test
    void waitersAreGrantedInTheOrderTheyFirstAsked() {
        PlayerLeases leases = new PlayerLeases();
        leases.acquire("Builder", "a", "a", "", NONE, MINUTE, 0L);
        assertEquals(1, leases.acquire("Builder", "b", "b", "", NONE, MINUTE, 100L).place);
        assertEquals(2, leases.acquire("Builder", "c", "c", "", NONE, MINUTE, 200L).place);
        leases.release("Builder", "a", false);

        assertNull(leases.acquire("Builder", "c", "c", "", NONE, MINUTE, 300L).granted, "b asked first");
        assertNotNull(leases.acquire("Builder", "b", "b", "", NONE, MINUTE, 400L).granted);
    }

    @Test
    void aWaiterPollingEverySecondKeepsItsPlaceInLine() {
        // A remote bringPlayer asks with waitSeconds 0 once a second; each refused ask must not cost it
        // its place, or a later asker overtakes it.
        PlayerLeases leases = new PlayerLeases();
        leases.acquire("Builder", "a", "a", "", NONE, MINUTE, 0L);
        leases.acquire("Builder", "b", "b", "", NONE, MINUTE, 1_000L);
        leases.acquire("Builder", "c", "c", "", NONE, MINUTE, 1_500L);
        leases.acquire("Builder", "b", "b", "", NONE, MINUTE, 2_000L);
        assertEquals(2, leases.status("Builder", "a", 2_100L).get("waiting").getAsInt());
        leases.release("Builder", "a", false);

        assertNull(leases.acquire("Builder", "c", "c", "", NONE, MINUTE, 2_500L).granted);
        assertNotNull(leases.acquire("Builder", "b", "b", "", NONE, MINUTE, 3_000L).granted);
    }

    @Test
    void aWaiterThatStopsAskingLosesItsPlace() {
        PlayerLeases leases = new PlayerLeases();
        leases.acquire("Builder", "a", "a", "", NONE, MINUTE, 0L);
        leases.acquire("Builder", "b", "b", "", NONE, MINUTE, 0L);
        leases.release("Builder", "a", false);

        long later = PlayerLeases.WAITER_GONE_MILLIS + 1L;
        assertNotNull(leases.acquire("Builder", "c", "c", "", NONE, MINUTE, later).granted);
    }

    @Test
    void usingThePlayerRenewsTheHoldersLeaseAndAnUnusedOneRunsOutWithItsSlots() {
        PlayerLeases leases = new PlayerLeases();
        leases.acquire("Builder", "a", "a", "", Collections.singletonMap(2, "{id:\"minecraft:torch\"}"), MINUTE, 0L);
        leases.refusalFor("Builder", "a", 50_000L);

        assertTrue(leases.expire(MINUTE + 1L).isEmpty(), "renewed at 50 s, so good until 110 s");
        List<PlayerLeases.Lease> ended = leases.expire(110_001L);

        assertEquals(1, ended.size());
        assertEquals("{id:\"minecraft:torch\"}", ended.get(0).slots.get(2), "its slots go back to the player");
    }

    @Test
    void aLeaseThatRanOutWithSlotsIsNotGrantedOverBeforeItsSlotsAreBack() {
        PlayerLeases leases = new PlayerLeases();
        leases.acquire("Builder", "a", "a", "", Collections.singletonMap(0, "{}"), MINUTE, 0L);

        assertNull(leases.acquire("Builder", "b", "b", "", NONE, MINUTE, MINUTE + 1L).granted);
        leases.expire(MINUTE + 2L);
        assertNotNull(leases.acquire("Builder", "b", "b", "", NONE, MINUTE, MINUTE + 3L).granted);
    }

    @Test
    void onlyTheHolderReleasesWithoutForce() {
        PlayerLeases leases = new PlayerLeases();
        leases.acquire("Builder", "a", "a", "", NONE, MINUTE, 0L);

        assertNull(leases.release("Builder", "b", false));
        assertNotNull(leases.release("Builder", "b", true));
    }

    @Test
    void aPlayersCompanionAccessEndingReleasesEveryAgentOfTheirsButNotOthers() {
        PlayerLeases leases = new PlayerLeases();
        leases.acquire("Builder", "player:u1~agent:a", "a", "", NONE, MINUTE, 0L);
        leases.acquire("Helper", "player:u2~agent:a", "a", "", NONE, MINUTE, 0L);

        assertEquals(1, leases.releaseHolder("player:u1").size());
        assertNotNull(leases.refusalFor("Helper", "x", 1L));
    }
}
