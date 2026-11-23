package com.micatechnologies.minecraft.mcmcp.chunkload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkKeysBoxTest {

    @Test
    void anOrdinaryBoxListsItsChunks() {
        assertEquals(4, ChunkKeys.box(0, 0, 31, 31).size());
        assertEquals(1, ChunkKeys.box(-1, -1, -16, -16).size());
    }

    @Test
    void aBoxAcrossTheWholeWorldIsRefusedBeforeAnySetIsBuilt() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> ChunkKeys.box(-30_000_000, -30_000_000, 30_000_000, 30_000_000));
        assertTrue(refused.getMessage().contains("chunks"), refused.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> ChunkKeys.box(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
    }

    @Test
    void theLongestFillOneCallMakesStillFits() {
        // A strip a million blocks long: 65,536 chunks.
        assertEquals(65_536, ChunkKeys.box(0, 0, 1_048_575, 0).size());
    }
}
