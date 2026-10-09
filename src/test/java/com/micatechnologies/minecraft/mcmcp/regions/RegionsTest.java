package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Packed region reads, and the record of what changed. */
class RegionsTest {

    @Test
    void terrainLikeIndicesPackSmallAndComeBackIdentical() {
        int[] values = new int[80 * 80 * 20];
        for (int i = 0; i < values.length; i++) {
            values[i] = i < values.length / 2 ? 1 : (i % 97 == 0 ? 3 : 0);
        }
        String packed = PackedIndices.encode(values);
        assertTrue(packed.length() < 2000, "128,000 mostly repeating cells pack to " + packed.length() + " chars");
        assertArrayEquals(values, PackedIndices.decode(packed, values.length));
    }

    @Test
    void noisyValuesIncludingNegativesSurviveTheRoundTrip() {
        int[] values = new int[5000];
        Random random = new Random(7);
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextInt(300) - 2;
        }
        assertArrayEquals(values, PackedIndices.decode(PackedIndices.encode(values), values.length));
        assertArrayEquals(new int[0], PackedIndices.decode(PackedIndices.encode(new int[0]), 0));
    }

    @Test
    void aTokenReturnsOnlyWhatChangedAfterItInsideTheBox() {
        ChangeRing ring = new ChangeRing(100);
        ring.add(0, 1, 64, 1, 5, "other");
        long token = ring.token();
        ring.add(0, 2, 64, 2, 6, "player:Alex");
        ring.add(0, 500, 64, 500, 6, "other");
        ring.add(-1, 2, 64, 2, 6, "other");
        ChangeRing.Result result = ring.since(token, 0, 0, 0, 0, 10, 255, 10, 100, null);
        assertEquals(1, result.changes.size());
        assertEquals("player:Alex", result.changes.get(0).source);
        assertFalse(result.overflow);
        assertEquals(ring.token(), result.token);
        assertTrue(ring.since(result.token, 0, 0, 0, 0, 10, 255, 10, 100, null).changes.isEmpty());
    }

    @Test
    void aTokenOlderThanTheRingSaysSomeChangesAreGone() {
        ChangeRing ring = new ChangeRing(10);
        long token = ring.token();
        for (int i = 0; i < 25; i++) {
            ring.add(0, i, 64, 0, 1, "other");
        }
        ChangeRing.Result result = ring.since(token, 0, -1000, 0, -1000, 1000, 255, 1000, 100, null);
        assertTrue(result.overflow, "15 changes were overwritten");
        assertEquals(10, result.changes.size());
        assertFalse(ring.since(ring.token(), 0, 0, 0, 0, 0, 0, 0, 10, null).overflow, "a current token never overflows");
    }

    @Test
    void aLimitTruncatesAndTheTokenPicksUpWhereItStopped() {
        ChangeRing ring = new ChangeRing(100);
        long token = ring.token();
        for (int i = 0; i < 7; i++) {
            ring.add(0, i, 64, 0, 1, "other");
        }
        ChangeRing.Result first = ring.since(token, 0, 0, 0, 0, 100, 255, 100, 5, null);
        assertTrue(first.truncated);
        assertEquals(5, first.changes.size());
        ChangeRing.Result rest = ring.since(first.token, 0, 0, 0, 0, 100, 255, 100, 5, null);
        assertEquals(2, rest.changes.size());
        assertFalse(rest.truncated);
        assertEquals(5, rest.changes.get(0).x);
    }
}
