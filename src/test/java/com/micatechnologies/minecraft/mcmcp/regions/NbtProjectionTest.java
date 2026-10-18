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
    void anIndexPicksOneElementAndBracketsAndDotsMeanTheSame() {
        JsonObject projected = NbtProjection.project(controller(), Arrays.asList("links[1].x", "links.0.y"));
        assertEquals("{\"links\":{\"1\":{\"x\":3},\"0\":{\"y\":2}}}", Json.write(projected),
            "indexed elements come back keyed by index, and two paths into one list merge");
    }

    @Test
    void anIndexPastTheEndOfAListIsLeftOut() {
        assertEquals(0, NbtProjection.project(controller(), Collections.singletonList("links[5].x")).size());
    }

    @Test
    void anIndexIntoAListOfListsPicksFromEachLevel() {
        JsonObject tag = Json.parse("{\"grid\":[[1,2],[3,4]]}").getAsJsonObject();
        assertEquals("{\"grid\":{\"1\":{\"0\":3}}}",
            Json.write(NbtProjection.project(tag, Collections.singletonList("grid[1][0]"))));
    }

    @Test
    void aSummaryGivesEachPartsSizeWithoutItsContents() {
        JsonObject summary = NbtProjection.summary(controller(), 2).getAsJsonObject();
        assertEquals(1, summary.getAsJsonObject("keys").get("tcCp").getAsInt());
        JsonObject links = summary.getAsJsonObject("keys").getAsJsonObject("links");
        assertEquals(2, links.get("len").getAsInt());
        assertTrue(links.getAsJsonObject("each").getAsJsonObject("keys").has("x"),
            "a list of compounds shows the shape of one element");
        assertFalse(Json.write(summary).contains("Junction"), "no values, only sizes");
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

    @Test
    void aWildcardMatchesAnywhereInAFilterNotOnlyAtTheEnd() {
        assertTrue(NbtProjection.matches("csm:lift_car_panel_2", Collections.singletonList("*car_panel*")));
        assertTrue(NbtProjection.matches("csm:lift_car_panel_2", Collections.singletonList("csm:*panel*")));
        assertTrue(NbtProjection.matches("csm:lift_car_panel", Collections.singletonList("*_panel")));
        assertTrue(NbtProjection.matches("csm:a_b", Collections.singletonList("csm:*_*")));
        assertFalse(NbtProjection.matches("ldme:lift_car_panel", Collections.singletonList("csm:*panel*")),
            "the namespace before the wildcard still has to match");
        assertFalse(NbtProjection.matches("csm:lift_car_panel_2", Collections.singletonList("*_panel")),
            "a pattern without a trailing '*' must reach the end of the id");
        assertTrue(NbtProjection.isPattern("*x*"));
        assertTrue(NbtProjection.isPattern("csm:"));
        assertFalse(NbtProjection.isPattern("csm:controller"));
    }
}
