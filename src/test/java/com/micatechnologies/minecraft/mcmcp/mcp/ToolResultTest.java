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
    void a_result_whose_payload_is_text_offers_a_client_nothing_to_read_instead_of_it() {
        // A client may show structuredContent in place of the text blocks when both are present.
        // game_read_log and client_read_chat once sent their lines as text and only the counts as
        // structured content, and through such a client answered "returned: 6" and none of the six.
        // They are text alone now, and this is what makes that safe.
        JsonObject result = ToolResult.text("# latest.log — 1 line\n[12:00:00] a line").toJson(LATEST);

        assertFalse(result.has("structuredContent"));
        assertTrue(textOf(result).contains("[12:00:00] a line"));
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
