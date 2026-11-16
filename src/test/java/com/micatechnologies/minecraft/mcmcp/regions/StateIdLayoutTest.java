package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StateIdLayoutTest {

    @Test
    void a_vanilla_id_decodes_to_its_block_and_meta() {
        int wool = 35 | 5 << 12;
        assertFalse(StateIdLayout.isExtended(wool));
        assertEquals(35, StateIdLayout.blockId(wool));
        assertEquals(5, StateIdLayout.meta(wool));

        int highest = 4095 | 15 << 12;
        assertFalse(StateIdLayout.isExtended(highest));
        assertEquals(4095, StateIdLayout.blockId(highest));
        assertEquals(15, StateIdLayout.meta(highest));
    }

    @Test
    void an_extended_id_decodes_to_its_own_block_and_not_a_vanilla_one() {
        // An id extender packs a block id of 4096 or more as id << 4 | meta. Read as vanilla, 0x14033
        // is block 51 (minecraft:fire) meta 4: the wrong name #203 and #204 reported for
        // csm:signexceptbus:3.
        int packed = 0x1403 << 4 | 3;
        assertTrue(StateIdLayout.isExtended(packed));
        assertEquals(0x1403, StateIdLayout.blockId(packed));
        assertEquals(3, StateIdLayout.meta(packed));
    }

    @Test
    void the_first_extended_id_and_a_very_large_one_both_decode() {
        int first = 4096 << 4;
        assertTrue(StateIdLayout.isExtended(first));
        assertEquals(4096, StateIdLayout.blockId(first));
        assertEquals(0, StateIdLayout.meta(first));

        int large = 9_000_000 << 4 | 15;
        assertEquals(9_000_000, StateIdLayout.blockId(large));
        assertEquals(15, StateIdLayout.meta(large));
    }

    @Test
    void air_is_block_zero_meta_zero() {
        assertEquals(0, StateIdLayout.blockId(0));
        assertEquals(0, StateIdLayout.meta(0));
    }
}
