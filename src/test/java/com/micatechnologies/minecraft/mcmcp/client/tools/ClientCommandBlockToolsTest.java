package com.micatechnologies.minecraft.mcmcp.client.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * Drawing on a side of a chiseled block. A mirrored side is the mistake that fails without a
 * sound: the letters arrive, backwards.
 */
class ClientCommandBlockToolsTest {

    @Test
    void theTopLeftOfTheNorthSideIsItsTopEastCornerSeenFromOutside() {
        // Standing north of the block and facing south, east is on your left.
        assertArrayEquals(new int[]{15, 15, 0}, ClientCommandBlockTools.faceToBlock("north", 0, 0, 0));
        assertArrayEquals(new int[]{0, 0, 0}, ClientCommandBlockTools.faceToBlock("north", 15, 15, 0));
    }

    @Test
    void theTopLeftOfTheSouthSideIsItsTopWestCorner() {
        assertArrayEquals(new int[]{0, 15, 15}, ClientCommandBlockTools.faceToBlock("south", 0, 0, 0));
    }

    @Test
    void theWestAndEastSidesPutNorthAndSouthOnTheLeft() {
        assertArrayEquals(new int[]{0, 15, 0}, ClientCommandBlockTools.faceToBlock("west", 0, 0, 0));
        assertArrayEquals(new int[]{15, 15, 15}, ClientCommandBlockTools.faceToBlock("east", 0, 0, 0));
    }

    @Test
    void depthGoesInwardFromTheSideDrawnOn() {
        assertArrayEquals(new int[]{15, 15, 3}, ClientCommandBlockTools.faceToBlock("north", 0, 0, 3));
        assertArrayEquals(new int[]{0, 15, 12}, ClientCommandBlockTools.faceToBlock("south", 0, 0, 3));
        assertArrayEquals(new int[]{12, 15, 15}, ClientCommandBlockTools.faceToBlock("east", 0, 0, 3));
        assertArrayEquals(new int[]{5, 13, 2}, ClientCommandBlockTools.faceToBlock("up", 2, 5, 2));
        assertArrayEquals(new int[]{5, 2, 2}, ClientCommandBlockTools.faceToBlock("down", 2, 5, 2));
    }
}
