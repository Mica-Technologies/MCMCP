package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** server_run_test scripts that need no game: the finally block and what it can see (issue #116). */
class ServerTestToolsTest {

    private static final GameThreadBridge NO_GAME = new GameThreadBridge() {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public void runOnGameThread(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public McmcpSide getSide() {
            return McmcpSide.SERVER;
        }
    };

    private static JsonObject run(String arguments) throws Exception {
        McpSession session = new McpSession("test", 0L);
        ToolContext context = new ToolContext(session, new JsonParser().parse(arguments).getAsJsonObject(), NO_GAME,
            session.beginRequest(new JsonPrimitive(1)), 1000L, new JsonPrimitive("token"));
        return ServerTestTools.run(context).getStructuredContent();
    }

    @Test
    void finallyCanIncludeTheTopLevelDefsAndSeesTheRunsVars() throws Exception {
        // The issue's repro, with a def that reads a variable the steps set: finally includes the
        // same def, sees x, and its check counts in the verdict.
        JsonObject result = run("{\"defs\": {\"r\": [{\"set\": {\"reset\": \"${x + 1}\"}}]},"
            + " \"steps\": [{\"set\": {\"x\": 1}}, {\"include\": \"r\"}],"
            + " \"finally\": [{\"include\": \"r\"}, {\"check\": \"${reset == 2}\", \"label\": \"finally ran r\"}]}");
        assertTrue(result.get("passed").getAsBoolean(), result.toString());
        assertNull(result.get("warnings"), result.toString());
        assertNull(result.get("finallyStopped"), result.toString());
        assertEquals(1, result.get("checksPassed").getAsInt(), result.toString());
    }

    @Test
    void aFinallyThatStopsEarlyFailsTheRun() throws Exception {
        JsonObject result = run("{\"steps\": [{\"set\": {\"x\": 1}}],"
            + " \"finally\": [{\"sleepUntil\": 0, \"since\": \"never\"}]}");
        assertFalse(result.get("passed").getAsBoolean(), result.toString());
        assertTrue(result.get("finallyStopped").getAsString().contains("never"), result.toString());
        assertEquals(1, result.getAsJsonArray("warnings").size(), result.toString());
    }

    @Test
    void finallySeesTheRunsMarks() throws Exception {
        JsonObject result = run("{\"steps\": [{\"mark\": \"alarm\"}],"
            + " \"finally\": [{\"sleepUntil\": 0, \"since\": \"alarm\"}]}");
        assertTrue(result.get("passed").getAsBoolean(), result.toString());
    }
}
