package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import org.junit.jupiter.api.Test;

class CoordinateBoundsTest {

    private static String refusal(String json) {
        return CoordinateBounds.refusal(Json.parse(json).getAsJsonObject());
    }

    @Test
    void anOrdinaryCallIsLeftAlone() {
        assertNull(refusal("{\"x\":440,\"y\":78,\"z\":-2294,\"toX\":441,\"toZ\":-2285,\"load\":true}"));
        assertNull(refusal("{\"mode\":\"palette\",\"origin\":{\"x\":-29999000,\"y\":0,\"z\":29999000},\"cells\":[0,0,0,0,1,0,0,0]}"));
        assertNull(refusal("{}"));
        assertNull(CoordinateBounds.refusal(null));
    }

    @Test
    void aCornerAtTheEndOfIntIsRefusedAndNamed() {
        // The one-cell reads and removals that never came back: x <= maxX is never false there.
        String refusal = refusal("{\"x\":2147483647,\"y\":64,\"z\":0}");
        assertNotNull(refusal);
        assertTrue(refusal.startsWith("'x' is 2147483647"), refusal);
        assertNotNull(refusal("{\"x\":0,\"z\":0,\"toX\":-2147483648}"));
        assertNotNull(refusal("{\"x\":30000001,\"z\":0}"));
    }

    @Test
    void aYFarOutsideAnyWorldIsRefusedButAnOutOfRangeProbeIsNot() {
        assertNotNull(refusal("{\"x\":0,\"y\":-2147483648,\"z\":0,\"toY\":2147483647}"));
        // 300 is outside the world's height, which is the tool's to say, not this check's.
        assertNull(refusal("{\"x\":0,\"y\":300,\"z\":0}"));
        assertNull(refusal("{\"x\":0,\"y\":-64,\"z\":0}"));
    }

    @Test
    void positionsInsideListsAndNestedObjectsAreChecked() {
        assertTrue(refusal("{\"to\":[2147483647,64,0]}").startsWith("'to[0]'"));
        assertTrue(refusal("{\"box\":[2147483647,64,5000000,2147483647,65,5000000]}").startsWith("'box[0]'"));
        assertTrue(refusal("{\"blocks\":[{\"x\":1,\"y\":2,\"z\":3},{\"x\":1,\"y\":2,\"z\":1073741824}]}")
            .startsWith("'blocks[1].z'"));
        assertTrue(refusal("{\"openings\":[{\"x\":2147483647,\"y\":64,\"z\":0}]}").startsWith("'openings[0].x'"));
        assertTrue(refusal("{\"steps\":[{\"call\":\"t\",\"restore\":{\"x\":2147483647,\"y\":0,\"z\":0}}]}")
            .startsWith("'steps[0].restore.x'"));
        assertTrue(refusal("{\"kind\":\"get_blocks\",\"args\":{\"x\":0,\"toX\":10,\"y\":0,\"z\":2147483647}}")
            .startsWith("'args.z'"));
        assertTrue(refusal("{\"hold\":[[-30000000,-30000000,30000000,90000000]]}").startsWith("'hold[0][3]'"));
        assertTrue(refusal("{\"cells\":[0,0,0,0,5000000000,0,0,0]}").startsWith("'cells[4]'"));
    }

    @Test
    void numbersThatAreNotPositionsAreNotChecked() {
        assertNull(refusal("{\"tick\":987654321,\"since\":1790000000000,\"cursor\":99999999999,\"limit\":100}"));
        assertNull(refusal("{\"indices\":\"eJzt\",\"sizeX\":16,\"palette\":[\"minecraft:stone\"]}"));
    }

    @Test
    void aPositionGivenAsAStringOrAsNotANumberIsStillChecked() {
        assertNotNull(refusal("{\"x\":\"2147483647\",\"z\":0}"));
        assertNotNull(refusal("{\"x\":\"NaN\",\"z\":0}"));
        assertNotNull(refusal("{\"x\":1e999,\"z\":0}"));
        assertNull(refusal("{\"x\":\"12\",\"z\":\"~\"}"));
    }

    @Test
    void argumentsNestedAbsurdlyDeepAreRefusedRatherThanWalked() {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            json.append("{\"a\":");
        }
        json.append("1");
        for (int i = 0; i < 100; i++) {
            json.append('}');
        }
        JsonObject deep = Json.parse(json.toString()).getAsJsonObject();
        assertNotNull(CoordinateBounds.refusal(deep));
    }
}
