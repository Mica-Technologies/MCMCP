package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Keeping the matches that have, or lack, another block within a box distance (#125). */
class NearQueryTest {

    /** A world of nearby-block positions, with an optional unloaded half-space x >= unloadedFromX. */
    private static final class FakeWorld implements NearQuery.Neighbourhood {
        final Set<String> blocks = new HashSet<>();
        int unloadedFromX = Integer.MAX_VALUE;
        int probes;

        FakeWorld with(int x, int y, int z) {
            blocks.add(x + "," + y + "," + z);
            return this;
        }

        @Override
        public int probe(int x, int y, int z) {
            probes++;
            if (x >= unloadedFromX) {
                return NearQuery.UNLOADED;
            }
            return blocks.contains(x + "," + y + "," + z) ? NearQuery.MATCH : NearQuery.NO_MATCH;
        }
    }

    @Test
    void aBlockDiagonallyTouchingIsWithinRadiusOne() {
        NearQuery query = new NearQuery(1, NearQuery.Mode.WITH);
        NearQuery.Outcome outcome = query.check(0, 64, 0, new FakeWorld().with(1, 65, -1));
        assertEquals(NearQuery.Verdict.LISTED, outcome.verdict);
        assertArrayEquals(new int[] {1, 65, -1}, outcome.found.get(0));
    }

    @Test
    void aBlockJustPastTheRadiusDoesNotCount() {
        FakeWorld world = new FakeWorld().with(3, 64, 0);
        assertEquals(NearQuery.Verdict.DROPPED, new NearQuery(2, NearQuery.Mode.WITH).check(0, 64, 0, world).verdict);
        assertEquals(NearQuery.Verdict.LISTED, new NearQuery(2, NearQuery.Mode.WITHOUT).check(0, 64, 0, world).verdict);
        assertEquals(NearQuery.Verdict.LISTED, new NearQuery(3, NearQuery.Mode.WITH).check(0, 64, 0, world).verdict);
    }

    @Test
    void aCandidateIsNeverItsOwnNeighbour() {
        FakeWorld world = new FakeWorld().with(5, 70, 5);
        assertEquals(NearQuery.Verdict.DROPPED, new NearQuery(1, NearQuery.Mode.WITH).check(5, 70, 5, world).verdict);
    }

    @Test
    void modeWithReportsAtMostThreeNeighboursNearestFirst() {
        FakeWorld world = new FakeWorld().with(2, 0, 2).with(0, 1, 0).with(1, 0, 1).with(0, 0, 2).with(2, 2, 2);
        NearQuery.Outcome outcome = new NearQuery(2, NearQuery.Mode.WITH).check(0, 0, 0, world);
        assertEquals(NearQuery.MAX_REPORTED, outcome.found.size());
        assertArrayEquals(new int[] {0, 1, 0}, outcome.found.get(0), "a face neighbour first");
        assertArrayEquals(new int[] {1, 0, 1}, outcome.found.get(1), "then an edge neighbour");
        assertArrayEquals(new int[] {0, 0, 2}, outcome.found.get(2), "then the nearer of the two-away");
    }

    @Test
    void modeWithoutStopsReadingAtTheFirstNeighbourFound() {
        FakeWorld world = new FakeWorld().with(1, 64, 0);
        assertEquals(NearQuery.Verdict.DROPPED, new NearQuery(4, NearQuery.Mode.WITHOUT).check(0, 64, 0, world).verdict);
        assertEquals(true, world.probes <= 6, "a face neighbour is among the first six cells read, not all 728");
    }

    @Test
    void aNeighbourhoodReachingAnUnloadedChunkWithNothingFoundIsUnresolvedEitherWay() {
        FakeWorld world = new FakeWorld();
        world.unloadedFromX = 2;
        assertEquals(NearQuery.Verdict.UNRESOLVED, new NearQuery(2, NearQuery.Mode.WITH).check(0, 64, 0, world).verdict);
        assertEquals(NearQuery.Verdict.UNRESOLVED,
            new NearQuery(2, NearQuery.Mode.WITHOUT).check(0, 64, 0, world).verdict);
    }

    @Test
    void aNeighbourFoundInTheLoadedPartSettlesItDespiteAnUnloadedChunk() {
        FakeWorld world = new FakeWorld().with(-1, 64, 0);
        world.unloadedFromX = 1;
        assertEquals(NearQuery.Verdict.LISTED, new NearQuery(1, NearQuery.Mode.WITH).check(0, 64, 0, world).verdict);
        assertEquals(NearQuery.Verdict.DROPPED, new NearQuery(1, NearQuery.Mode.WITHOUT).check(0, 64, 0, world).verdict);
    }

    @Test
    void cellsAboveAndBelowTheWorldMatchNothingAndAreNeverRead() {
        FakeWorld world = new FakeWorld().with(0, -1, 0).with(0, 256, 0);
        assertEquals(NearQuery.Verdict.DROPPED, new NearQuery(1, NearQuery.Mode.WITH).check(0, 0, 0, world).verdict);
        assertEquals(NearQuery.Verdict.DROPPED, new NearQuery(1, NearQuery.Mode.WITH).check(0, 255, 0, world).verdict);
        world.probes = 0;
        new NearQuery(1, NearQuery.Mode.WITHOUT).check(0, 0, 0, world);
        assertEquals(17, world.probes, "the nine cells below y 0 are skipped");
    }

    @Test
    void theOffsetsCoverTheWholeCubeOnceWithoutTheCentre() {
        int[] offsets = NearQuery.offsets(2);
        assertEquals((5 * 5 * 5 - 1) * 3, offsets.length);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < offsets.length; i += 3) {
            seen.add(offsets[i] + "," + offsets[i + 1] + "," + offsets[i + 2]);
        }
        assertEquals(5 * 5 * 5 - 1, seen.size());
        assertEquals(false, seen.contains("0,0,0"));
    }

    @Test
    void aRadiusOutsideTheBoundsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new NearQuery(0, NearQuery.Mode.WITH));
        assertThrows(IllegalArgumentException.class, () -> new NearQuery(NearQuery.MAX_RADIUS + 1, NearQuery.Mode.WITH));
    }

    @Test
    void theModeReadsWithOrWithoutInAnyCaseAndNothingElse() {
        assertEquals(NearQuery.Mode.WITH, NearQuery.Mode.parse(" With "));
        assertEquals(NearQuery.Mode.WITHOUT, NearQuery.Mode.parse("WITHOUT"));
        assertNull(NearQuery.Mode.parse("near"));
        assertNull(NearQuery.Mode.parse(null));
    }
}
