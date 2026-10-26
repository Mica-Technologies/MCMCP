package com.micatechnologies.minecraft.mcmcp.chunkload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PregenPlanTest {

    private static Set<Long> keys(List<PregenPlan.Step> steps, boolean generate) {
        Set<Long> keys = new HashSet<>();
        for (PregenPlan.Step step : steps) {
            if (step.generate == generate) {
                keys.add(step.key());
            }
        }
        return keys;
    }

    @Test
    void anEmptyWorldIsGeneratedWithAMarginOnThePlusSides() {
        // The box's +x and +z edge chunks populate only once their neighbours there exist.
        List<PregenPlan.Step> steps = new PregenPlan(0, 0, 1, 1).steps((x, z) -> false);

        assertEquals(9, steps.size(), "a 2x2 box and its one-chunk margin");
        assertTrue(keys(steps, true).contains(ChunkKeys.of(2, 2)));
        assertFalse(keys(steps, true).contains(ChunkKeys.of(-1, 0)), "no margin on the minus sides");
        long inBox = steps.stream().filter(step -> step.inBox).count();
        assertEquals(4, inBox);
    }

    @Test
    void aBoxThatExistsInFullTouchesNothingSoRunningItAgainResumes() {
        // Even with the margin missing: the box's chunks were populated when they were generated.
        PregenPlan plan = new PregenPlan(0, 0, 3, 3);

        assertTrue(plan.steps((x, z) -> x <= 3 && z <= 3).isEmpty());
    }

    @Test
    void anExistingChunkIsLoadedOnlyWhenItBordersOneThatIsNot() {
        // Chunk (0,0) exists beside a new (1,0): both must be loaded together for (0,0) to populate.
        // (3,3) is far from anything new and stays on disk.
        PregenPlan plan = new PregenPlan(0, 0, 3, 3);
        List<PregenPlan.Step> steps = plan.steps((x, z) -> !(x == 1 && z == 0));

        assertEquals(java.util.Collections.singleton(ChunkKeys.of(1, 0)), keys(steps, true));
        Set<Long> loaded = keys(steps, false);
        assertTrue(loaded.contains(ChunkKeys.of(0, 0)));
        assertTrue(loaded.contains(ChunkKeys.of(2, 1)));
        assertFalse(loaded.contains(ChunkKeys.of(3, 3)));
    }

    @Test
    void stepsGoRowByRowSoRowsCanBeGivenBackInOrder() {
        List<PregenPlan.Step> steps = new PregenPlan(-2, -2, 1, 1).steps((x, z) -> false);

        for (int i = 1; i < steps.size(); i++) {
            PregenPlan.Step before = steps.get(i - 1);
            PregenPlan.Step after = steps.get(i);
            assertTrue(after.z > before.z || after.z == before.z && after.x > before.x, "out of order at " + i);
        }
    }

    @Test
    void aRowIsGivenBackOnlyOnceTheRowAfterItIsFinished() {
        // Row 4 populates while row 5 is generated beside it, so it is needed until row 6 starts.
        assertFalse(PregenPlan.releasable(4, 4));
        assertFalse(PregenPlan.releasable(4, 5));
        assertTrue(PregenPlan.releasable(4, 6));
        assertTrue(PregenPlan.releasable(-3, -1), "negative rows too");
    }

    @Test
    void aBlockBoxCoversTheChunksItTouchesAtNegativeCoordinates() {
        PregenPlan plan = PregenPlan.ofBlocks(-1, -17, 16, 0);

        assertEquals(-1, plan.minX);
        assertEquals(1, plan.maxX);
        assertEquals(-2, plan.minZ);
        assertEquals(0, plan.maxZ);
        assertEquals(9, plan.chunks());
    }
}
