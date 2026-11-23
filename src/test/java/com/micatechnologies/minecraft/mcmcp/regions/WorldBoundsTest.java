package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldBoundsTest {

    @Test
    void aPositionInsideTheWorldIsAccepted() {
        assertNull(WorldBounds.refusal("Cell 0", 440, 78, -2294));
        assertNull(WorldBounds.refusal("Cell 0", WorldBounds.MAX_HORIZONTAL, 0, -WorldBounds.MAX_HORIZONTAL));
        assertNull(WorldBounds.refusal("Cell 0", 0, 255, 0));
    }

    @Test
    void aPositionPastTheWorldsEdgeIsRefusedAndNamed() {
        String refusal = WorldBounds.refusal("Cell 7", WorldBounds.MAX_HORIZONTAL + 1L, 64, 0);
        assertNotNull(refusal);
        assertTrue(refusal.startsWith("Cell 7 "), refusal);
        assertTrue(refusal.contains("x=30000001"), refusal);
        assertNotNull(WorldBounds.refusal("Cell 7", 0, 64, -WorldBounds.MAX_HORIZONTAL - 1L));
    }

    @Test
    void theSentinelsOfABoxNobodySetAreRefused() {
        // What a box drawn round no cells at all holds: the #219 box.
        assertNotNull(WorldBounds.horizontalRefusal("The box", Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertNotNull(WorldBounds.horizontalRefusal("The box", Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertNotNull(WorldBounds.boxRefusal("The box", Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void anOriginPlusAnOffsetThatWouldWrapIsRefusedRatherThanWrapped() {
        // In int arithmetic this sum is -2147483639, a number that looks like a coordinate.
        long x = (long) Integer.MAX_VALUE + 10L;
        assertFalse(WorldBounds.isHorizontal(x));
        assertNotNull(WorldBounds.refusal("Cell 0", x, 64, 0));
    }

    @Test
    void yOutsideTheWorldsHeightIsRefused() {
        assertNotNull(WorldBounds.refusal("Cell 3", 0, -1, 0));
        assertNotNull(WorldBounds.refusal("Cell 3", 0, 256, 0));
        assertTrue(WorldBounds.refusal("Cell 3", 0, 256, 0).contains("y=256"));
    }

    @Test
    void aBoxWiderThanTheSpanIsRefusedWithItsSize() {
        assertNull(WorldBounds.boxRefusal("The box", 0, 0, 4095, 4095));
        String refusal = WorldBounds.boxRefusal("The box", 0, 0, 4096, 10);
        assertNotNull(refusal);
        assertTrue(refusal.contains("4097 x 11"), refusal);
        // Corners either way round.
        assertNotNull(WorldBounds.boxRefusal("The box", 4096, 10, 0, 0));
        assertNull(WorldBounds.boxRefusal("The box", -30_000_000, 0, -29_999_000, 0));
    }

    @Test
    void theSpanOfTwoCornersAtOppositeEdgesDoesNotOverflow() {
        String refusal = WorldBounds.boxRefusal("The box", -WorldBounds.MAX_HORIZONTAL, 0, WorldBounds.MAX_HORIZONTAL, 0,
            Integer.MAX_VALUE);
        assertNull(refusal);
        assertEquals(60_000_001L, Math.abs((long) WorldBounds.MAX_HORIZONTAL + WorldBounds.MAX_HORIZONTAL) + 1L);
    }
}
