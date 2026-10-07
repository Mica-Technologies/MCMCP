package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import org.junit.jupiter.api.Test;

/**
 * What the in-game activity line reads: the call running now, and how far through it is. Each test
 * ends every call it starts, since the registry is shared across the process.
 */
class ToolActivityTest {

    @Test
    void theNewestRunningCallIsTheOneShownAndCarriesItsProgress() {
        long first = ToolActivity.callStarted(McmcpSide.CLIENT, "client_wait");
        long second = ToolActivity.callStarted(McmcpSide.SERVER, "server_set_blocks");
        ToolActivity.progress(second, 4096, 32768, "Wrote 4096 of 32768 blocks");

        ToolActivity.RunningCall shown = ToolActivity.newestRunning();
        assertEquals("server_set_blocks", shown.getTool());
        assertEquals(4096, shown.getProgress(), 0);
        assertEquals("Wrote 4096 of 32768 blocks", shown.getMessage());
        assertEquals(2, ToolActivity.runningCount());

        ToolActivity.callEnded(second);
        assertEquals("client_wait", ToolActivity.newestRunning().getTool());
        ToolActivity.callEnded(first);
        assertNull(ToolActivity.newestRunning());
    }

    @Test
    void progressForACallThatEndedChangesNothing() {
        long id = ToolActivity.callStarted(McmcpSide.CLIENT, "client_look");
        ToolActivity.callEnded(id);
        ToolActivity.progress(id, 1, 2, "late");
        assertNull(ToolActivity.newestRunning());
        assertTrue(ToolActivity.lastEndedMillis() > 0);
    }

    @Test
    void aCallWithNoProgressYetSaysSo() {
        long id = ToolActivity.callStarted(McmcpSide.CLIENT, "client_look");
        ToolActivity.RunningCall call = ToolActivity.newestRunning();
        assertTrue(call.getProgress() < 0);
        assertNull(call.getMessage());
        ToolActivity.callEnded(id);
        assertSame(null, ToolActivity.newestRunning());
    }
}
