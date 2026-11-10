package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Blocks that collide only along a cell's edges: railings, wall handrails, ladders (#186). */
class EdgeCellsTest {

    private static double[] box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** A railing's edge-only cell, blocking the given sides. */
    private static byte railing(int edges) {
        return (byte) (VoxelGrid.EDGE | edges << 4);
    }

    @Test
    void aRailAlongOneSideLeavesTheCellStoodInAndMarksOnlyThatSide() {
        byte north = VoxelGrid.fromCollisionBoxes(Collections.singletonList(box(0, 0, 0, 1, 1, 0.125)));
        assertEquals(VoxelGrid.EDGE, VoxelGrid.kind(north));
        assertEquals(VoxelGrid.NORTH, VoxelGrid.edges(north));

        byte corner = VoxelGrid.fromCollisionBoxes(Arrays.asList(box(0.875, 0, 0, 1, 1, 1), box(0, 0, 0.875, 1, 1, 1)));
        assertEquals(VoxelGrid.EAST | VoxelGrid.SOUTH, VoxelGrid.edges(corner), "an L of two rails");
    }

    @Test
    void aBlockThatReachesIntoTheMiddleOfItsCellIsSolidWhateverElseItHas() {
        // A fence post, a pane running north to south, a slab, and a rail with a baluster in the middle.
        assertEquals(VoxelGrid.SOLID, VoxelGrid.fromCollisionBoxes(
            Collections.singletonList(box(0.375, 0, 0.375, 0.625, 1, 0.625))));
        assertEquals(VoxelGrid.SOLID, VoxelGrid.fromCollisionBoxes(
            Collections.singletonList(box(0.4375, 0, 0, 0.5625, 1, 1))));
        assertEquals(VoxelGrid.SOLID, VoxelGrid.fromCollisionBoxes(Collections.singletonList(box(0, 0, 0, 1, 0.5, 1))));
        assertEquals(VoxelGrid.SOLID, VoxelGrid.fromCollisionBoxes(
            Arrays.asList(box(0, 0, 0, 1, 1, 0.125), box(0.4, 0, 0.4, 0.6, 1, 0.6))));
    }

    @Test
    void nothingToCollideWithIsOpenAndACornerPostBlocksNoSide() {
        assertEquals(VoxelGrid.OPEN, VoxelGrid.fromCollisionBoxes(Collections.<double[]>emptyList()));
        byte post = VoxelGrid.fromCollisionBoxes(Collections.singletonList(box(0, 0, 0, 0.125, 1, 0.125)));
        assertEquals(VoxelGrid.EDGE, VoxelGrid.kind(post));
        assertEquals(0, VoxelGrid.edges(post), "a post in the corner stops nobody crossing either side");
        assertEquals(0, VoxelGrid.edges(VoxelGrid.OPEN));
        assertEquals(0, VoxelGrid.edges(VoxelGrid.SOLID));
    }

    /**
     * A flight of three stairs climbing east from a floor at y 0 to a landing at y 3, every tread with
     * a guard in the cell above it along its north side: the stair the issue's townhouses have.
     */
    private static VoxelGrid guardedStair(int guardEdges) {
        VoxelGrid grid = new VoxelGrid(0, 0, 0, 6, 7, 2);
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = 1; y <= 7; y++) {
                    grid.set(x, y, z, VoxelGrid.OPEN);
                }
            }
        }
        for (int step = 1; step <= 3; step++) {
            for (int z = 0; z <= 2; z++) {
                // The tread, and everything under it.
                for (int y = 1; y <= step; y++) {
                    grid.set(1 + step, y, z, VoxelGrid.SOLID);
                }
            }
            grid.set(1 + step, step + 1, 0, railing(guardEdges));
        }
        for (int x = 5; x <= 6; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = 1; y <= 3; y++) {
                    grid.set(x, y, z, VoxelGrid.SOLID);
                }
            }
        }
        return grid;
    }

    private static final List<int[]> FLOOR = Collections.singletonList(new int[] {0, 1, 1});

    @Test
    void aStairWithAGuardAboveEachTreadIsClimbedThroughTheGuardsCells() {
        VoxelGrid grid = guardedStair(VoxelGrid.NORTH);
        BitSet reached = SpaceChecks.walk(grid, FLOOR, 2, 1, true);
        assertTrue(reached.get(grid.index(5, 4, 1)), "the landing is reached up the middle of the flight");
        assertTrue(reached.get(grid.index(2, 2, 0)), "and the tread under the guard is stood on");
        assertTrue(SpaceChecks.standable(grid, 3, 3, 0, 2, true));
    }

    @Test
    void aGuardAcrossTheFlightStopsTheStepOverItAndOnlyThat() {
        // The same guards turned to run across the flight's north lane, on the side the climber leaves by.
        VoxelGrid grid = guardedStair(VoxelGrid.EAST);
        assertTrue(SpaceChecks.edgeBlocks(grid, 2, 2, 0, 3, 3, 0, 1, 0, 2), "east out of the guarded cell");
        assertTrue(SpaceChecks.edgeBlocks(grid, 3, 3, 0, 2, 2, 0, -1, 0, 2), "and back west into it");
        assertFalse(SpaceChecks.edgeBlocks(grid, 2, 2, 0, 2, 2, 1, 0, 1, 2), "south, along the guard, is free");
        BitSet reached = SpaceChecks.walk(grid, FLOOR, 2, 1, true);
        assertTrue(reached.get(grid.index(5, 4, 1)), "the other lanes still climb");
        assertTrue(reached.get(grid.index(2, 2, 0)), "and the guarded tread is reached from beside it");
    }

    @Test
    void aGuardRightAcrossALandingLeavesWhatIsBehindItUnreached() {
        VoxelGrid grid = new VoxelGrid(0, 0, 0, 4, 4, 0);
        for (int x = 0; x <= 4; x++) {
            for (int y = 1; y <= 4; y++) {
                grid.set(x, y, 0, VoxelGrid.OPEN);
            }
        }
        grid.set(2, 1, 0, railing(VoxelGrid.EAST));
        BitSet reached = SpaceChecks.walk(grid, Collections.singletonList(new int[] {0, 1, 0}), 2, 1, true);
        assertTrue(reached.get(grid.index(2, 1, 0)), "up to the guard");
        assertFalse(reached.get(grid.index(3, 1, 0)), "not past it");
        assertEquals(2, SpaceChecks.clusters(grid, andNot(SpaceChecks.standableCells(grid, 2, true), reached))
            .get(0).count, "the two cells behind are an unreached area");
    }

    @Test
    void anEdgeCellStillClosesAnEnclosureAndIsNotCountedAsOpenOrSolid() {
        VoxelGrid grid = new VoxelGrid(0, 0, 0, 2, 2, 2);
        grid.set(1, 1, 1, railing(VoxelGrid.WEST));
        grid.set(1, 2, 1, VoxelGrid.OPEN);
        assertEquals(1, grid.count(VoxelGrid.EDGE));
        assertEquals(1, grid.count(VoxelGrid.OPEN));
        assertFalse(grid.get(1, 1, 1) == VoxelGrid.OPEN, "a flood of air does not pass a railing's cell");
        assertFalse(SpaceChecks.standable(grid, 1, 2, 1, 1, true), "and nobody stands on top of one");
    }

    private static BitSet andNot(BitSet all, BitSet reached) {
        BitSet rest = (BitSet) all.clone();
        rest.andNot(reached);
        return rest;
    }
}
