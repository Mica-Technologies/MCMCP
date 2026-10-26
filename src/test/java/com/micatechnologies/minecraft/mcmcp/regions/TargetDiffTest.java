package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TargetDiffTest {

    private static TargetDiff diff() {
        return new TargetDiff(TargetDiff.Normaliser.defaults(), 2, Collections.emptyList());
    }

    @Test
    void anOpenedDoorALitLampAndAJoinedFenceAreNotDifferences() {
        TargetDiff diff = diff();

        assertEquals(TargetDiff.Kind.MATCH, diff.add(0, 0, 0, "minecraft:oak_door[facing=north,half=lower,open=false]",
            false, "minecraft:oak_door[facing=north,half=lower,open=true]"));
        // 1.12 lights a lamp by swapping the block, so the property list alone would miss it.
        assertEquals(TargetDiff.Kind.MATCH, diff.add(1, 0, 0, "minecraft:redstone_lamp", false,
            "minecraft:lit_redstone_lamp"));
        assertEquals(TargetDiff.Kind.MATCH, diff.add(3, 0, 0, "minecraft:air", false, "csm:lightupair"));
    }

    @Test
    void storedEdgeAndShapePropertiesAreComparedForEveryBlock() {
        // The game side drops what a block does not store (fence connections) before this sees it; what
        // reaches here is stored, and a railing's edges or a rail's shape being wrong is a real error.
        TargetDiff diff = diff();

        assertEquals(TargetDiff.Kind.WRONG_STATE, diff.add(0, 0, 0, "csm:railing_steel[east=true,north=true]", false,
            "csm:railing_steel[east=false,north=true]"));
        assertEquals(TargetDiff.Kind.WRONG_STATE, diff.add(1, 0, 0, "minecraft:rail[shape=north_south]", false,
            "minecraft:rail[shape=east_west]"));
    }

    @Test
    void aPropertyCanBeIgnoredForSomeBlocksOnly() {
        // CSM bright lights flip a stored 'state' with redstone; a global ignore would be too broad.
        Map<String, Set<String>> forCsm = Collections.singletonMap("csm:*", Collections.singleton("state"));
        TargetDiff diff = new TargetDiff(TargetDiff.Normaliser.defaults().with(Collections.<String>emptySet(),
            Collections.<String, String>emptyMap(), forCsm), 2, Collections.<int[]>emptyList());

        assertEquals(TargetDiff.Kind.MATCH, diff.add(0, 0, 0, "csm:cehalo[facing=north,state=0]", false,
            "csm:cehalo[facing=north,state=1]"));
        assertEquals(TargetDiff.Kind.WRONG_STATE, diff.add(1, 0, 0, "othermod:thing[state=0]", false,
            "othermod:thing[state=1]"));
    }

    @Test
    void aBlockSwappedForItsLitFormStillComparesItsFacing() {
        TargetDiff diff = diff();

        assertEquals(TargetDiff.Kind.MATCH, diff.add(0, 0, 0, "minecraft:furnace[facing=north]", false,
            "minecraft:lit_furnace[facing=north]"));
        assertEquals(TargetDiff.Kind.WRONG_STATE, diff.add(1, 0, 0, "minecraft:furnace[facing=north]", false,
            "minecraft:lit_furnace[facing=south]"));
    }

    @Test
    void anIdPatternMatchesRunsOfCharacters() {
        assertTrue(TargetDiff.Normaliser.matches("csm:*", "csm:cehalo"));
        assertTrue(TargetDiff.Normaliser.matches("*:elevator_door_*", "movingelevators:elevator_door_block"));
        assertTrue(TargetDiff.Normaliser.matches("csm:railing_*_steel", "csm:railing_tall_steel"));
        assertFalse(TargetDiff.Normaliser.matches("csm:*", "minecraft:stone"));
        assertFalse(TargetDiff.Normaliser.matches("csm:rail", "csm:railing"));
    }

    @Test
    void eachRealDifferenceIsSortedByWhatKindItIs() {
        TargetDiff diff = diff();

        assertEquals(TargetDiff.Kind.WRONG_STATE, diff.add(0, 0, 0, "minecraft:stone_stairs[facing=east,half=bottom]",
            false, "minecraft:stone_stairs[facing=west,half=bottom]"));
        assertEquals(TargetDiff.Kind.MISSING, diff.add(1, 0, 0, "csm:brick", false, "minecraft:air"));
        assertEquals(TargetDiff.Kind.EXTRA, diff.add(2, 0, 0, "minecraft:air", false, "minecraft:cobblestone"));
        assertEquals(TargetDiff.Kind.WRONG_BLOCK, diff.add(3, 0, 0, "csm:brick", false, "minecraft:cobblestone"));
        assertEquals(TargetDiff.Kind.UNLOADED, diff.add(4, 0, 0, "csm:brick", false, null));
        assertEquals(4, diff.repairable());
    }

    @Test
    void aChangedProtectedCellIsReportedApartAndNeverCountedForRepair() {
        TargetDiff diff = diff();

        assertEquals(TargetDiff.Kind.PROTECTED_CHANGED, diff.add(0, 0, 0, "csm:sign", true, "minecraft:air"));
        assertEquals(TargetDiff.Kind.MATCH, diff.add(1, 0, 0, "csm:sign", true, "csm:sign"));
        assertEquals(0, diff.repairable());
    }

    @Test
    void aSkippedBoxIsNotCheckedAtAll() {
        // A lift shaft: the cab moves through it, so nothing in it is a difference.
        List<int[]> shaft = Collections.singletonList(new int[] {10, 0, 10, 12, 100, 12});
        TargetDiff diff = new TargetDiff(TargetDiff.Normaliser.defaults(), 2, shaft);

        assertNull(diff.add(11, 50, 11, "minecraft:air", false, "ldme:cab"));
        assertEquals(0, diff.repairable());
        assertEquals(1, diff.toJson().get("skipped").getAsLong());
    }

    @Test
    void theReportIsCountsAndAFewSamplesPerKind() {
        TargetDiff diff = diff();
        for (int x = 0; x < 50; x++) {
            diff.add(x, 0, 0, "csm:brick", false, "minecraft:air");
        }
        diff.add(99, 0, 0, "csm:brick", false, "csm:brick");

        JsonObject json = diff.toJson();

        assertEquals(1, json.get("matching").getAsLong());
        assertEquals(50, json.get("differ").getAsLong());
        JsonObject missing = json.getAsJsonObject("byKind").getAsJsonObject("missing");
        assertEquals(50, missing.get("count").getAsLong());
        assertEquals(2, missing.getAsJsonArray("samples").size(), "samples are capped");
    }

    @Test
    void anIgnoredPropertyCanBeComparedAfterAllForOneCheck() {
        TargetDiff.Normaliser strict = TargetDiff.Normaliser.defaults().without(Collections.singleton("open"));

        assertEquals("minecraft:oak_door[open=true]", strict.normalise("minecraft:oak_door[powered=false,open=true]"));
        assertEquals("minecraft:oak_door", TargetDiff.Normaliser.defaults().normalise("minecraft:oak_door[open=true]"));
    }
}
