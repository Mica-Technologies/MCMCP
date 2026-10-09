package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** How palette and expectation entries name a block and its metadata. */
class ServerBulkToolsTest {

    @Test
    void anEntryMayGiveMetadataAfterAColonOrASpaceOrNotAtAll() {
        Object[] colon = ServerBulkTools.splitId("minecraft:wool:14");
        assertEquals("minecraft:wool", colon[0]);
        assertEquals(14, colon[1]);

        Object[] space = ServerBulkTools.splitId("furenikusroads:road_block_fine 15");
        assertEquals("furenikusroads:road_block_fine", space[0]);
        assertEquals(15, space[1]);

        Object[] none = ServerBulkTools.splitId(" minecraft:stone ");
        assertEquals("minecraft:stone", none[0]);
        assertEquals(-1, none[1], "no metadata given means any, for an expectation");
    }

    @Test
    void aBlockNameWithAColonInItsPathIsNotMistakenForMetadata() {
        Object[] split = ServerBulkTools.splitId("modid:some_block:variant");
        assertEquals("modid:some_block:variant", split[0]);
        assertEquals(-1, split[1]);
    }
}
