package com.micatechnologies.minecraft.mcmcp.client.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Where a find looks. Getting the chunk range wrong does not fail: it searches the wrong chunks and
 * reports "0 found", which reads exactly like a correct answer.
 */
class ClientSurveyToolsTest {

    @Test
    void aBoxInsideTheWindowKeepsItsOwnChunkRange() {
        assertArrayEquals(new int[]{0, 1, 2, 3},
            ClientSurveyTools.chunkWindow(0, 31, 32, 63, 0, 0, 32));
    }

    @Test
    void negativeCoordinatesFloorToTheChunkHoldingThem() {
        // Block -1 is in chunk -1, and -17 in chunk -2: division would put both in the wrong one.
        assertArrayEquals(new int[]{-2, -1, -1, 0},
            ClientSurveyTools.chunkWindow(-17, -1, -1, 15, 0, 0, 32));
    }

    @Test
    void aCorridorLongerThanTheWindowIsClippedToItAroundThePlayer() {
        // x 40..520, z -200..1700 with the player in chunk (10, 50).
        assertArrayEquals(new int[]{2, 32, 18, 82},
            ClientSurveyTools.chunkWindow(40, 520, -200, 1700, 10, 50, 32));
    }

    @Test
    void aBoxOutsideTheWindowSearchesNothing() {
        assertNull(ClientSurveyTools.chunkWindow(10_000, 10_100, 0, 15, 0, 0, 32));
    }

    @Test
    void chunkColumnsCountsEveryChunkTheBoxTouchesEvenPartly() {
        assertEquals(1, ClientSurveyTools.chunkColumns(0, 15, 0, 15));
        assertEquals(4, ClientSurveyTools.chunkColumns(15, 16, -1, 0));
        assertEquals(31L * 120L, ClientSurveyTools.chunkColumns(40, 520, -200, 1700));
    }

    @Test
    void chunkColumnsDoesNotOverflowForAContinentSizedBox() {
        assertEquals(3_750_001L * 3_750_001L,
            ClientSurveyTools.chunkColumns(-30_000_000, 30_000_000, -30_000_000, 30_000_000));
    }
}
