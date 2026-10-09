package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Keeping only the NBT fields asked for, and choosing which blocks' tile entities to read. */
class NbtProjectionTest {

    private static JsonObject controller() {
        return Json.parse("{\"tcCp\":3,\"tcFm\":\"\",\"links\":[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}],"
            + "\"display\":{\"Name\":\"Junction\",\"Lore\":\"long\"},\"huge\":\"...\"}").getAsJsonObject();
    }

    @Test
    void onlyTheNamedFieldsComeBackNestedAsTheyWere() {
        JsonObject projected = NbtProjection.project(controller(), Arrays.asList("tcCp", "display.Name"));
        assertEquals("{\"tcCp\":3,\"display\":{\"Name\":\"Junction\"}}", Json.write(projected));
    }

    @Test
    void aPathThroughAListAppliesToEveryElement() {
        JsonObject projected = NbtProjection.project(controller(), Collections.singletonList("links.x"));
        assertEquals("{\"links\":[{\"x\":1},{\"x\":3}]}", Json.write(projected));
    }

    @Test
    void anAbsentPathIsLeftOutRatherThanInvented() {
        JsonObject projected = NbtProjection.project(controller(), Arrays.asList("nope", "display.nope"));
        assertEquals(0, projected.size());
    }

    @Test
    void aFilterMatchesAnIdANamespaceOrAPrefix() {
        assertTrue(NbtProjection.matches("csm:controller", Collections.singletonList("csm:controller")));
        assertTrue(NbtProjection.matches("csm:controller", Collections.singletonList("csm:")));
        assertTrue(NbtProjection.matches("csm:signal_head_3", Collections.singletonList("csm:signal_*")));
        assertFalse(NbtProjection.matches("csm:controller_extra", Collections.singletonList("csm:controller")));
        assertFalse(NbtProjection.matches("minecraft:chest", Arrays.asList("csm:", "railcraft:")));
        assertTrue(NbtProjection.matches("anything", Collections.emptyList()), "no filter means every block");
    }
}
