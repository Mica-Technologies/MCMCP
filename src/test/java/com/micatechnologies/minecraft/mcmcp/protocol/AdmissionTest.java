package com.micatechnologies.minecraft.mcmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerLoad;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which calls a paused or stalled server still takes (#133). */
class AdmissionTest {

    private static GameThreadBridge bridge(final McmcpSide side) {
        return new GameThreadBridge() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public boolean isGameThread() {
                return false;
            }

            @Override
            public <T> T callOnGameThread(Callable<T> task, long timeoutMillis) {
                throw new IllegalStateException("no game");
            }

            @Override
            public void runOnGameThread(Runnable task) {
            }

            @Override
            public McmcpSide getSide() {
                return side;
            }
        };
    }

    private final McpDispatcher server = new McpDispatcher(McmcpSide.SERVER, bridge(McmcpSide.SERVER), 1000L, "test");
    private final McpDispatcher client = new McpDispatcher(McmcpSide.CLIENT, bridge(McmcpSide.CLIENT), 1000L, "test");

    @BeforeEach
    @AfterEach
    void healthy() {
        ServerLoad.reset();
    }

    @Test
    void aHealthyOrMerelyThrottledServerTakesEveryCall() {
        assertNull(server.admissionRefusal("server_set_blocks", false));
        ServerLoad.measured(ServerLoad.Level.THROTTLED, 45.0D, 40.0D, 10_000L);
        assertNull(server.admissionRefusal("server_set_blocks", false));
    }

    @Test
    void aPausedServerRefusesWritesButStillAnswersReads() {
        ServerLoad.measured(ServerLoad.Level.PAUSED, 90.0D, 40.0D, 10_000L);
        String refusal = server.admissionRefusal("server_set_blocks", false);
        assertNotNull(refusal);
        assertTrue(refusal.contains("Nothing changed"), refusal);
        assertTrue(refusal.contains("paused (mean tick 90.0 ms"), refusal);
        assertNull(server.admissionRefusal("server_get_blocks", true));
    }

    @Test
    void aStalledServerStillTakesWhatItTakesToSeeAndStopMcmcp() {
        ServerLoad.stalled(true, 20_000L);
        assertNotNull(server.admissionRefusal("server_run_commands", false));
        for (String exempt : new String[] {"server_estop", "server_release_loaded", "server_job_cancel",
            "server_job_pause", "server_job_resume", "server_job_submit", "game_health", "server_tick_stats",
            "mcmcp_endpoint_info", "server_stop"}) {
            assertNull(server.admissionRefusal(exempt, false), exempt);
        }
    }

    @Test
    void theClientEndpointIsNeverRefusedForTheServersLoad() {
        ServerLoad.stalled(true, 20_000L);
        assertNull(client.admissionRefusal("client_move", false), "a singleplayer world shares the JVM, not the thread");
    }
}
