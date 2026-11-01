package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BiomeSurveyTest {

    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        NAMES.put(1, "minecraft:plains");
        NAMES.put(4, "minecraft:forest");
        NAMES.put(2, "minecraft:desert");
    }

    @Test
    void samples_start_at_the_low_corner_and_never_pass_the_high_one() {
        assertArrayEquals(new int[] {-10, -6, -2}, BiomeSurvey.axis(0, -10, 4));
        assertArrayEquals(new int[] {5}, BiomeSurvey.axis(5, 5, 16));
    }

    @Test
    void the_default_step_keeps_a_large_square_near_the_default_sample_count() {
        int step = BiomeSurvey.defaultStep(4000, 4000);
        int perSide = BiomeSurvey.axis(0, 3999, step).length;
        assertEquals(63, step);
        assertEquals(64, perSide);
        assertEquals(1, BiomeSurvey.defaultStep(10, 10));
    }

    @Test
    void the_tally_puts_the_most_common_biome_first_with_its_share_of_known_samples() {
        BiomeSurvey survey = new BiomeSurvey(new int[] {0, 1}, new int[] {0, 1});
        survey.set(0, 4, BiomeSurvey.LOADED);
        survey.set(1, 1, BiomeSurvey.SAVED);
        survey.set(2, 4, BiomeSurvey.PREDICTED);

        JsonArray tally = survey.tally(NAMES);
        assertEquals("minecraft:forest", tally.get(0).getAsJsonObject().get("biome").getAsString());
        assertEquals(2, tally.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(0.6667D, tally.get(0).getAsJsonObject().get("share").getAsDouble(), 1e-9);

        JsonObject sources = survey.sources();
        assertEquals(1, sources.get("loaded").getAsInt());
        assertEquals(1, sources.get("saved").getAsInt());
        assertEquals(1, sources.get("predicted").getAsInt());
        assertEquals(1, sources.get("unknown").getAsInt());
    }

    @Test
    void a_match_counts_only_the_listed_biomes() {
        BiomeSurvey survey = new BiomeSurvey(new int[] {0, 1, 2, 3}, new int[] {0});
        survey.set(0, 1, BiomeSurvey.LOADED);
        survey.set(1, 2, BiomeSurvey.LOADED);
        survey.set(2, 4, BiomeSurvey.LOADED);
        survey.set(3, 1, BiomeSurvey.LOADED);

        JsonObject match = survey.matching(Arrays.asList(1, 2));
        assertEquals(3, match.get("count").getAsInt());
        assertEquals(0.75D, match.get("share").getAsDouble(), 1e-9);
    }

    @Test
    void the_grid_runs_along_x_from_the_lowest_z_with_a_source_letter_per_sample() {
        BiomeSurvey survey = new BiomeSurvey(new int[] {0, 16}, new int[] {0, 16});
        survey.set(survey.index(0, 0), 1, BiomeSurvey.LOADED);
        survey.set(survey.index(1, 0), 4, BiomeSurvey.SAVED);
        survey.set(survey.index(0, 1), 1, BiomeSurvey.PREDICTED);

        assertEquals(16, survey.x(1));
        assertEquals(16, survey.z(2));
        JsonObject grid = survey.grid(NAMES);
        assertEquals("[\"minecraft:plains\",\"minecraft:forest\"]", grid.get("palette").toString());
        assertEquals("[0,1,0,-1]", grid.get("indexes").toString());
        assertEquals("LSP-", grid.get("source").getAsString());
    }
}
