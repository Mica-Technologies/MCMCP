package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ToolResultTest {

    private static final String LATEST = "2025-06-18";

    private static String textOf(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void a_structured_result_serialises_its_text_compactly_rather_than_pretty_printed() {
        // This text block is read into a model's context and paid for on every call. Gson's pretty
        // printer puts each array element on its own indented line, which turned the palette index
        // array that makes server_get_blocks affordable into one line per block: a 16-cubed region
        // measured 29,420 bytes pretty against 8,649 compact.
        JsonArray indices = new JsonArray();
        for (int i = 0; i < 64; i++) {
            indices.add(i % 4);
        }
        JsonObject data = new JsonObject();
        data.add("indices", indices);

        String text = textOf(ToolResult.structured(data).toJson(LATEST));

        assertFalse(text.contains("\n"), "the wire form should carry no formatting newlines");
        assertEquals("{\"indices\":[0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,"
            + "0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3,0,1,2,3]}", text);
    }

    @Test
    void a_structured_result_still_carries_the_machine_readable_copy() {
        // Compacting the text must not be mistaken for dropping structuredContent: a client on a
        // protocol that supports it may be reading that instead of the prose.
        JsonObject data = new JsonObject();
        data.addProperty("fps", 60);

        JsonObject result = ToolResult.structured(data).toJson(LATEST);

        assertTrue(result.has("structuredContent"));
        assertEquals(60, result.getAsJsonObject("structuredContent").get("fps").getAsInt());
    }

    @Test
    void a_tool_that_ran_and_failed_is_a_successful_response_carrying_an_error_flag() {
        // A JSON-RPC error is eaten by the client's plumbing and frequently never reaches the model,
        // which then retries the identical call forever.
        JsonObject result = ToolResult.error("No player is logged in.").toJson(LATEST);

        assertTrue(result.get("isError").getAsBoolean());
        assertEquals("No player is logged in.", textOf(result));
    }
}
