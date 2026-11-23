package com.micatechnologies.minecraft.mcmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolBusyException;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import org.junit.jupiter.api.Test;

/** What the error reply of a write that stopped part way carries (#220). */
class FailureDetailsTest {

    private static ToolContext context() {
        return new ToolContext(null, new JsonObject(), null, null, 1000L, null);
    }

    @Test
    void aWriteThatTimedOutPartWayNamesItsUndoPointBesideItsBusyBlock() {
        ToolContext context = context();
        context.noteFailureDetail("undoPoint", new JsonPrimitive("u123"));
        JsonObject partial = new JsonObject();
        partial.addProperty("written", 4096);
        context.noteFailureDetail("partial", partial);

        ToolResult result = McpDispatcher.withFailureDetails(context,
            ToolBusyException.startedNotFinished(5000L, 4997L).toResult(), "timeout");
        JsonObject structured = result.getStructuredContent();
        assertEquals("u123", structured.get("undoPoint").getAsString());
        assertEquals(4096, structured.getAsJsonObject("partial").get("written").getAsInt());
        assertTrue(structured.get("timedOut").getAsBoolean());
        assertTrue(structured.has("busy"), "the busy block is kept");
        assertFalse(structured.has("failed"));
        assertTrue(Json.write(result.toJson(McpProtocol.LATEST_VERSION)).contains("undo point u123"));
    }

    @Test
    void aWriteThatFailedPartWaySaysFailedAndAStepsDetailsReachTheWholeCall() {
        ToolContext whole = context();
        ToolContext step = context();
        step.shareFailureDetails(whole);
        step.noteFailureDetail("undoPoint", new JsonPrimitive("u9"));
        ToolResult result = McpDispatcher.withFailureDetails(whole, ToolResult.error("a mod hook threw"), "error");
        assertEquals("u9", result.getStructuredContent().get("undoPoint").getAsString());
        assertTrue(result.getStructuredContent().get("failed").getAsBoolean());
    }

    @Test
    void anErrorWithNothingNotedIsLeftExactlyAsItWas() {
        ToolResult plain = ToolResult.error("no such block");
        ToolResult result = McpDispatcher.withFailureDetails(context(), plain, "error");
        assertEquals(null, result.getStructuredContent());
    }
}
