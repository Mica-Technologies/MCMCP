package com.micatechnologies.minecraft.mcmcp.client.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewHopsTest {

    private static final double[] PLAYER = {-600, 98, -2240};

    @Test
    void aNearViewWhoseChunkTheClientHoldsIsDrawnFromWhereThePlayerStands() {
        assertFalse(ViewHops.needsHop(null, new double[] {-563, 88, -2300}, PLAYER, 160, true));
    }

    @Test
    void aNearViewWhoseChunkHasNotArrivedMovesThePlayer() {
        // The #168 case: 165 blocks away, inside the render distance, but not yet sent to a slow client.
        assertTrue(ViewHops.needsHop(null, new double[] {-563, 88, -2400}, PLAYER, 288, false));
    }

    @Test
    void aViewPastTheReachMovesThePlayerEvenWhenItsChunkIsHeld() {
        assertTrue(ViewHops.needsHop(null, new double[] {-320, 100, -2460}, PLAYER, 160, true));
    }

    @Test
    void aViewThatSaysBringPlayerFalseNeverMovesThePlayer() {
        assertFalse(ViewHops.needsHop(false, new double[] {-320, 100, -2460}, PLAYER, 160, false));
    }

    @Test
    void aViewThatSaysBringPlayerTrueAlwaysMovesThePlayer() {
        assertTrue(ViewHops.needsHop(true, new double[] {-601, 98, -2241}, PLAYER, 160, true));
    }

    @Test
    void aViewWithNoCameraOfItsOwnNeverMovesThePlayer() {
        assertFalse(ViewHops.needsHop(true, null, PLAYER, 160, false));
    }
}
