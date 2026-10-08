package com.micatechnologies.minecraft.mcmcp.client.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;

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

    @Test
    void aHeightmapIsLimitedByColumnsSoASeaBedTileOfTheWholeDepthFits() {
        // #50: 128 x 128 columns over y 0..61 is 1,015,808 blocks, four times the volume cap.
        assertNull(ClientSurveyTools.sizeRefusal("heightmap", 1_015_808L, 16_384L, 262_144L));
        assertNull(ClientSurveyTools.sizeRefusal("underside", 128L * 128L * 256L, 16_384L, 262_144L));
    }

    @Test
    void aColumnModePastTheColumnCapIsRefusedInColumns() {
        JsonObject refusal = ClientSurveyTools.sizeRefusal("surface", 129L * 128L, 129L * 128L, 262_144L);
        assertNotNull(refusal);
        assertEquals(129L * 128L, refusal.get("requested").getAsLong());
        assertEquals(16_384L, refusal.get("limit").getAsLong());
        assertEquals("columns", refusal.get("unit").getAsString());
    }

    @Test
    void aPositionsReadPastTheVolumeCapIsRefusedWithTheNumbersAScriptNeedsToSplitIt() {
        JsonObject refusal = ClientSurveyTools.sizeRefusal("positions", 262_145L, 1L, 262_144L);
        assertNotNull(refusal);
        assertEquals(262_145L, refusal.get("requested").getAsLong());
        assertEquals(262_144L, refusal.get("limit").getAsLong());
        assertEquals("blocks", refusal.get("unit").getAsString());
        assertNull(ClientSurveyTools.sizeRefusal("positions", 262_144L, 1L, 262_144L));
    }
}
