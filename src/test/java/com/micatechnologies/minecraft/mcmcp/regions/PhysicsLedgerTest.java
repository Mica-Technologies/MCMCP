package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhysicsLedgerTest {

    @Test
    void packed_positions_come_back_as_they_went_in_including_negative_coordinates() {
        int[][] cells = {{0, 0, 0}, {-9, 61, 130}, {29_999_999, 255, -29_999_999}, {-1, 1, -1}};
        for (int[] cell : cells) {
            long packed = PhysicsLedger.pack(cell[0], cell[1], cell[2]);
            assertEquals(cell[0], PhysicsLedger.x(packed));
            assertEquals(cell[1], PhysicsLedger.y(packed));
            assertEquals(cell[2], PhysicsLedger.z(packed));
        }
    }

    @Test
    void positions_pack_the_way_block_pos_does_so_both_halves_name_the_same_cell() {
        // BlockPos.toLong of (-9, 61, 130): x in the top 26 bits, then y in 12, then z in 26.
        long expected = ((long) -9 & ((1L << 26) - 1)) << 38 | 61L << 26 | 130L;
        assertEquals(expected, PhysicsLedger.pack(-9, 61, 130));
    }

    @Test
    void the_hold_covers_the_write_and_one_cell_around_it_within_the_world_height() {
        PhysicsLedger<String> ledger = new PhysicsLedger<>(0, 0, 0, 9, 255, 9);

        assertArrayEquals(new int[] {-1, 0, -1, 10, 255, 10}, ledger.box());
        assertTrue(ledger.inBox(-1, 0, 10));
        assertFalse(ledger.inBox(-2, 5, 5));
        assertFalse(ledger.inBox(5, 5, 11));
    }

    @Test
    void a_held_tick_goes_back_with_what_was_left_of_its_delay_and_one_already_due_runs_next_tick() {
        assertEquals(3, PhysicsLedger.delayFrom(1003L, 1000L));
        assertEquals(0, PhysicsLedger.delayFrom(990L, 1000L));
        assertEquals(Integer.MAX_VALUE, PhysicsLedger.delayFrom(Long.MAX_VALUE, 0L));
    }

    @Test
    void a_second_tick_for_the_same_cell_and_block_keeps_the_first_as_the_world_queue_does() {
        PhysicsLedger<String> ledger = new PhysicsLedger<>(0, 60, 0, 4, 64, 4);
        ledger.hold(1, 62, 1, "minecraft:sand", 1002L, 0);
        ledger.hold(1, 62, 1, "minecraft:sand", 1010L, 0);
        ledger.hold(1, 62, 1, "minecraft:flowing_water", 1005L, 0);

        List<PhysicsLedger.Held<String>> held = ledger.held();

        assertEquals(2, ledger.heldCount());
        assertEquals(1002L, held.get(0).scheduledTime);
        assertEquals("minecraft:flowing_water", held.get(1).block);
    }

    @Test
    void the_cells_looked_at_afterwards_are_every_changed_cell_and_held_tick_each_once_in_order() {
        PhysicsLedger<String> ledger = new PhysicsLedger<>(0, 60, 0, 4, 64, 4);
        for (int i = 0; i < 1000; i++) {
            ledger.changed(i % 5, 61, 0);
        }
        ledger.hold(2, 61, 0, "minecraft:sand", 1L, 0);
        ledger.hold(3, 62, 0, "minecraft:gravel", 1L, 0);

        long[] candidates = ledger.candidates();

        assertEquals(6, candidates.length);
        for (int i = 1; i < candidates.length; i++) {
            assertTrue(candidates[i - 1] < candidates[i]);
        }
    }

    @Test
    void a_cell_found_moving_from_several_neighbours_is_counted_and_listed_once() {
        PhysicsLedger<String> ledger = new PhysicsLedger<>(0, 60, 0, 4, 64, 4);
        assertTrue(ledger.mover(PhysicsLedger.Kind.GRAVITY, 1, 63, 1, "minecraft:sand"));
        assertFalse(ledger.mover(PhysicsLedger.Kind.GRAVITY, 1, 63, 1, "minecraft:sand"));
        ledger.mover(PhysicsLedger.Kind.FLUID, 0, 61, 2, "minecraft:water");

        JsonObject json = ledger.toJson(0, 0, true);

        assertEquals(1, json.get("gravityUnsupported").getAsInt());
        assertEquals(1, json.get("fluidsExposed").getAsInt());
        assertEquals("[[1,63,1,\"minecraft:sand\"],[0,61,2,\"minecraft:water\"]]", Json.write(json.get("sample")));
        assertEquals(2, ledger.movers().length);
    }

    @Test
    void the_sample_is_capped_while_the_counts_keep_every_cell() {
        PhysicsLedger<String> ledger = new PhysicsLedger<>(0, 60, 0, 100, 64, 0);
        for (int x = 0; x < 50; x++) {
            ledger.mover(PhysicsLedger.Kind.GRAVITY, x, 62, 0, "minecraft:sand");
        }

        JsonObject json = ledger.toJson(50, 0, true);

        assertEquals(50, json.get("gravityUnsupported").getAsInt());
        assertEquals(PhysicsLedger.SAMPLED, json.getAsJsonArray("sample").size());
    }

    @Test
    void a_write_that_leaves_nothing_moving_reports_counts_without_a_sample_or_note() {
        PhysicsLedger<String> ledger = new PhysicsLedger<>(0, 60, 0, 4, 64, 4);
        ledger.hold(1, 61, 1, "minecraft:sand", 1L, 0);

        JsonObject json = ledger.toJson(1, 0, false);

        assertEquals("{\"ticksHeld\":1,\"ticksRestored\":1,\"gravityUnsupported\":0,\"fluidsExposed\":0}",
            Json.write(json));
    }
}
