package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class BlockPatternsTest {

    private static BlockPatterns of(String... patterns) {
        return BlockPatterns.of(Arrays.asList(patterns));
    }

    @Test
    void aBarePathDoesNotMatchIdsThatMerelyContainIt() {
        BlockPatterns air = of("air");
        assertTrue(air.matches("minecraft:air"));
        assertFalse(air.matches("minecraft:oak_stairs"));
    }

    @Test
    void anIdWithoutMetadataMatchesEveryMetadataOfIt() {
        BlockPatterns wool = of("minecraft:wool");
        assertTrue(wool.matches("minecraft:wool"));
        assertTrue(wool.matches("minecraft:wool:14"));
        assertFalse(wool.matches("minecraft:wool_slab"));
    }

    @Test
    void anIdWithMetadataMatchesOnlyThatMetadata() {
        BlockPatterns red = of("minecraft:wool:14");
        assertTrue(red.matches("minecraft:wool:14"));
        assertFalse(red.matches("minecraft:wool:1"));
        assertFalse(red.matches("minecraft:wool"));
    }

    @Test
    void aBarePathMatchesThatPathInAnyNamespace() {
        BlockPatterns barrier = of("barrier");
        assertTrue(barrier.matches("minecraft:barrier"));
        assertTrue(barrier.matches("othermod:barrier:3"));
    }

    @Test
    void anAsteriskAsksForASubstring() {
        BlockPatterns alarm = of("*alarm*");
        assertTrue(alarm.matches("csm:firealarm_strobe"));
        assertTrue(alarm.matches("csm:alarm"));
        assertFalse(alarm.matches("minecraft:stone"));

        BlockPatterns csm = of("csm:*");
        assertTrue(csm.matches("csm:anything:2"));
        assertFalse(csm.matches("minecraft:csm"));
    }

    @Test
    void matchingIgnoresCase() {
        assertTrue(of("MINECRAFT:Stone").matches("minecraft:stone"));
    }

    @Test
    void anEmptyListMatchesNothingAndSaysItIsEmpty() {
        BlockPatterns none = BlockPatterns.of(Collections.<String>emptyList());
        assertTrue(none.isEmpty());
        assertFalse(none.matches("minecraft:air"));
    }
}
