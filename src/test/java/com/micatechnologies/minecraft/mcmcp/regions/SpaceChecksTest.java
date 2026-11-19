package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * The space checks against the UIA builder's own fixtures (its MCMCP-UIA-KB tests/mcmcp_fixtures,
 * generated 2026-10-26 from the scripts these replace), so the counts are its checks' counts.
 */
class SpaceChecksTest {

    /** A 5 x 5 room: solid floor at y 0, open above to y 4, a one-block wardrobe at (2, 1, 2). */
    private static VoxelGrid furnishedRoom() {
        VoxelGrid grid = new VoxelGrid(0, 0, 0, 4, 4, 4);
        for (int x = 0; x <= 4; x++) {
            for (int y = 1; y <= 4; y++) {
                for (int z = 0; z <= 4; z++) {
                    grid.set(x, y, z, VoxelGrid.OPEN);
                }
            }
        }
        grid.set(2, 1, 2, VoxelGrid.SOLID);
        return grid;
    }

    @Test
    void aSeedInsideFurnitureSnapsToTheFloorBesideItNotOnTop() {
        VoxelGrid grid = furnishedRoom();
        int[] to = SpaceChecks.nearest(grid, new int[] {2, 1, 2}, 3,
            (x, y, z) -> SpaceChecks.standable(grid, x, y, z, 2, true));

        assertEquals(1, to[1], "same layer first: the floor, not the wardrobe top");
        assertEquals(1, Math.abs(to[0] - 2) + Math.abs(to[2] - 2));
    }

    @Test
    void aStandableSeedStaysWhereItIs() {
        VoxelGrid grid = furnishedRoom();
        int[] at = {0, 1, 0};

        assertTrue(SpaceChecks.nearest(grid, at, 3, (x, y, z) -> SpaceChecks.standable(grid, x, y, z, 2, true)) == at);
    }

    @Test
    void aFurnitureTopIsASmallAreaAndLeftOutByFloorLevelAndSize() {
        VoxelGrid grid = furnishedRoom();
        BitSet standable = SpaceChecks.standableCells(grid, 2, true);
        List<SpaceChecks.Cluster> clusters = SpaceChecks.clusters(grid, standable);

        // The floor (24 cells) and the wardrobe top (1 cell) touch diagonally, so they are one cluster;
        // by layer they come apart.
        BitSet floor = SpaceChecks.onLayers(grid, standable, Collections.singleton(1));
        assertEquals(24, floor.cardinality());
        BitSet top = SpaceChecks.onLayers(grid, standable, Collections.singleton(2));
        assertEquals(1, SpaceChecks.atLeast(SpaceChecks.clusters(grid, top), 1).size());
        assertEquals(0, SpaceChecks.atLeast(SpaceChecks.clusters(grid, top), 6).size());
        assertEquals(clusters.size(), SpaceChecks.atLeast(clusters, 1).size());
    }

    private static JsonObject fixture(String name) throws IOException {
        try (InputStream in = SpaceChecksTest.class.getResourceAsStream("/fixtures/uia/" + name);
             Reader reader = new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[65536];
            for (int n; (n = reader.read(buffer)) > 0; ) {
                text.append(buffer, 0, n);
            }
            return Json.parse(text.toString()).getAsJsonObject();
        }
    }

    private static int[] ints(JsonArray array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsInt();
        }
        return out;
    }

    private static boolean isOpen(String block) {
        return block.equals("minecraft:air") || block.equals("air") || block.startsWith("minecraft:air:")
            || block.startsWith("csm:lightupair");
    }

    // ------------------------------------------------------------------
    // Walk: ALTO 1873, storey L13, structure model
    // ------------------------------------------------------------------

    private static VoxelGrid walkGrid(JsonObject fixture) {
        int[] box = ints(fixture.getAsJsonArray("box"));
        VoxelGrid grid = new VoxelGrid(box[0], box[1], box[2], box[3], box[4], box[5]);
        for (int x = grid.minX; x <= grid.maxX; x++) {
            for (int y = grid.minY; y <= grid.maxY; y++) {
                for (int z = grid.minZ; z <= grid.maxZ; z++) {
                    grid.set(x, y, z, VoxelGrid.OPEN);
                }
            }
        }
        for (JsonElement cell : fixture.getAsJsonArray("world")) {
            JsonArray c = cell.getAsJsonArray();
            String block = c.get(3).getAsString();
            byte kind = isOpen(block) ? VoxelGrid.OPEN : block.contains("door") ? VoxelGrid.DOOR : VoxelGrid.SOLID;
            grid.set(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt(), kind);
        }
        return grid;
    }

    @Test
    void theLiftLobbyReachesWhatTheBuildersWalkerReachedWithDoorsOpenAndClosed() throws IOException {
        JsonObject fixture = fixture("walk_alto_L13.json.gz");
        VoxelGrid grid = walkGrid(fixture);
        List<int[]> seeds = new ArrayList<>();
        for (JsonElement seed : fixture.getAsJsonObject("input").getAsJsonArray("seeds")) {
            seeds.add(ints(seed.getAsJsonArray()));
        }
        JsonObject expected = fixture.getAsJsonObject("expected");

        assertEquals(expected.get("reached_doors_open").getAsInt(),
            SpaceChecks.walk(grid, seeds, 2, 1, true).cardinality());
        assertEquals(expected.get("reached_doors_closed").getAsInt(),
            SpaceChecks.walk(grid, seeds, 2, 1, false).cardinality());
    }

    @Test
    void eachHomeFloodedWithDoorsClosedStaysInsideItself() throws IOException {
        // The separation check: a suite must not open into another. Seeds from the fixture's generator.
        JsonObject fixture = fixture("walk_alto_L13.json.gz");
        VoxelGrid grid = walkGrid(fixture);
        int y = ints(fixture.getAsJsonArray("box"))[1] + 1;
        int[][] homes = {{388, y, -563}, {388, y, -547}, {431, y, -563}, {431, y, -547}};
        JsonObject expected = fixture.getAsJsonObject("expected").getAsJsonObject("home_floods_doors_closed");

        String[] names = {"NW", "SW", "NE", "SE"};
        for (int i = 0; i < homes.length; i++) {
            BitSet reached = SpaceChecks.walk(grid, Collections.singletonList(homes[i]), 2, 1, false);
            assertEquals(expected.get(names[i]).getAsInt(), reached.cardinality(), names[i]);
        }
    }

    // ------------------------------------------------------------------
    // Enclosure: the basilica, as first designed (leaky) and as fixed
    // ------------------------------------------------------------------

    private static VoxelGrid basilicaGrid(JsonObject fixture) {
        int[] box = ints(fixture.getAsJsonObject("input").getAsJsonArray("box"));
        VoxelGrid grid = new VoxelGrid(box[0], box[1], box[2], box[3], box[4], box[5]);
        // Cells not in the design are air above grade, ground at or below it (y 77).
        for (int x = grid.minX; x <= grid.maxX; x++) {
            for (int y = grid.minY; y <= grid.maxY; y++) {
                for (int z = grid.minZ; z <= grid.maxZ; z++) {
                    grid.set(x, y, z, y <= 77 ? VoxelGrid.SOLID : VoxelGrid.OPEN);
                }
            }
        }
        for (JsonElement cell : fixture.getAsJsonArray("world")) {
            JsonArray c = cell.getAsJsonArray();
            int x = c.get(0).getAsInt();
            int y = c.get(1).getAsInt();
            int z = c.get(2).getAsInt();
            if (grid.contains(x, y, z)) {
                grid.set(x, y, z, isOpen(c.get(3).getAsString()) ? VoxelGrid.OPEN : VoxelGrid.SOLID);
            }
        }
        return grid;
    }

    private static List<int[]> basilicaSeeds(JsonObject fixture) {
        List<int[]> seeds = new ArrayList<>();
        for (JsonElement seed : fixture.getAsJsonObject("input").getAsJsonArray("from")) {
            seeds.add(ints(seed.getAsJsonArray()));
        }
        return seeds;
    }

    @Test
    void theOriginalBasilicaLeaksWhereTheBuildersCheckSaidItDid() throws IOException {
        JsonObject fixture = fixture("enclosure_basilica_leaky_original.json.gz");
        VoxelGrid grid = basilicaGrid(fixture);

        SpaceChecks.Enclosure result = SpaceChecks.enclosure(grid, basilicaSeeds(fixture), Collections.<int[]>emptyList());
        List<SpaceChecks.Cluster> clusters = SpaceChecks.clusters(grid, result.leaks);

        JsonObject expected = fixture.getAsJsonObject("expected");
        assertEquals(expected.get("interior_cells").getAsInt(), result.interior.cardinality());
        assertEquals(expected.get("leak_cells").getAsInt(), result.leaks.cardinality());
        assertEquals(expected.get("leak_clusters").getAsInt(), clusters.size());
        JsonObject largest = expected.getAsJsonArray("clusters").get(0).getAsJsonObject();
        assertEquals(largest.get("count").getAsInt(), clusters.get(0).count);
        assertEquals(Json.write(largest.get("min")), Json.write(SpaceChecks.array(clusters.get(0).min)));
        assertEquals(Json.write(largest.get("max")), Json.write(SpaceChecks.array(clusters.get(0).max)));
    }

    @Test
    void theFixedBasilicaIsSealed() throws IOException {
        JsonObject fixture = fixture("enclosure_basilica_fixed_p4.json.gz");
        VoxelGrid grid = basilicaGrid(fixture);

        SpaceChecks.Enclosure result = SpaceChecks.enclosure(grid, basilicaSeeds(fixture), Collections.<int[]>emptyList());

        JsonObject expected = fixture.getAsJsonObject("expected");
        assertEquals(0, result.leaks.cardinality());
        assertEquals(expected.get("interior_cells").getAsInt(), result.interior.cardinality());
    }

    @Test
    void anOpenZoneCountsAsOutside() {
        // A closed 5x5x5 room with one open-air deck cell inside it: declared open, the deck leaks.
        VoxelGrid grid = new VoxelGrid(0, 0, 0, 6, 6, 6);
        for (int x = 1; x <= 5; x++) {
            for (int y = 1; y <= 5; y++) {
                for (int z = 1; z <= 5; z++) {
                    boolean wall = x == 1 || x == 5 || y == 1 || y == 5 || z == 1 || z == 5;
                    grid.set(x, y, z, wall ? VoxelGrid.SOLID : VoxelGrid.OPEN);
                }
            }
        }
        List<int[]> seed = Collections.singletonList(new int[] {3, 3, 3});

        assertEquals(0, SpaceChecks.enclosure(grid, seed, Collections.<int[]>emptyList()).leaks.cardinality());
        SpaceChecks.Enclosure decked = SpaceChecks.enclosure(grid, seed,
            Collections.singletonList(new int[] {4, 4, 4, 4, 4, 4}));
        assertTrue(decked.leaks.cardinality() > 0);
    }

    /**
     * Ground, then a ramp rising half a block a cell (slab, block, block + slab, two blocks), then a
     * full-block ledge. Columns run the width of the box.
     */
    private static VoxelGrid rampWithLedge() {
        VoxelGrid grid = new VoxelGrid(0, 0, 0, 6, 7, 2);
        // Solid cells per x from y = 1 up, and whether the top one is a bottom slab.
        int[] solidTo = {1, 1, 2, 2, 3, 3, 4};
        boolean[] slabTop = {false, false, true, false, true, false, false};
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = 0; y <= 7; y++) {
                    grid.set(x, y, z, y <= solidTo[x] ? VoxelGrid.SOLID : VoxelGrid.OPEN);
                }
                if (slabTop[x]) {
                    grid.setTop(x, solidTo[x], z, 8);
                }
            }
        }
        return grid;
    }

    @Test
    void aHalfBlockStepWalksAHalfSlabRampButNotAFullBlockLedge() {
        VoxelGrid grid = rampWithLedge();
        List<int[]> seed = Collections.singletonList(new int[] {0, 2, 1});

        BitSet half = SpaceChecks.walk(grid, seed, 2, 0.5D, true);
        assertTrue(half.get(grid.index(5, 4, 1)), "the top of the ramp is reached half a block at a time");
        assertTrue(!half.get(grid.index(6, 5, 1)), "the full-block ledge is not");

        assertTrue(SpaceChecks.walk(grid, seed, 2, 1, true).get(grid.index(6, 5, 1)),
            "a whole step of 1 still climbs it, as before");
        assertTrue(!SpaceChecks.walk(grid, seed, 2, 0, true).get(grid.index(5, 4, 1)),
            "a step of 0 still counts the slab's cell as a rise");
    }

    /** The boxes of an upright stair whose high half is to the east: a slab, and a step on its east half. */
    private static final List<double[]> STAIR_RISING_EAST = java.util.Arrays.asList(
        new double[] {0, 0, 0, 1, 0.5, 1}, new double[] {0.5, 0.5, 0, 1, 1, 1});

    /**
     * Ground to y 1, then three stairs rising east at x 3, 4, 5, then a landing from x 6. With
     * {@code landingTop} 4 the top tread meets the landing; with 5 it ends a full block below it.
     */
    private static VoxelGrid stairFlight(int landingTop) {
        VoxelGrid grid = new VoxelGrid(0, 0, 0, 9, 10, 2);
        int[] quadrants = VoxelGrid.quadrantTopsOf(STAIR_RISING_EAST);
        for (int x = 0; x <= 9; x++) {
            int solidTo = x < 3 ? 1 : x <= 5 ? x - 1 : landingTop;
            for (int z = 0; z <= 2; z++) {
                for (int y = 0; y <= 10; y++) {
                    grid.set(x, y, z, y <= solidTo ? VoxelGrid.SOLID : VoxelGrid.OPEN);
                }
                if (x >= 3 && x <= 5) {
                    grid.setQuadrantTops(x, solidTo, z, quadrants);
                }
            }
        }
        return grid;
    }

    @Test
    void aStairIsAHalfStepOnItsLowSideAndAFullOneOnItsHighSide() {
        assertEquals(java.util.Arrays.toString(new int[] {8, 16, 8, 16}),
            java.util.Arrays.toString(VoxelGrid.quadrantTopsOf(STAIR_RISING_EAST)));
        // Upside down: level at the top, so it is a full block from every side.
        int[] upsideDown = VoxelGrid.quadrantTopsOf(java.util.Arrays.asList(
            new double[] {0, 0.5, 0, 1, 1, 1}, new double[] {0.5, 0, 0, 1, 0.5, 1}));
        assertEquals(java.util.Arrays.toString(new int[] {16, 16, 16, 16}), java.util.Arrays.toString(upsideDown));
    }

    @Test
    void aHalfBlockStepClimbsAStairFlightButNotATopTreadAFullBlockUnderTheLanding() {
        List<int[]> seed = Collections.singletonList(new int[] {1, 2, 1});

        VoxelGrid good = stairFlight(4);
        BitSet walked = SpaceChecks.walk(good, seed, 2, 0.5D, true);
        assertTrue(walked.get(good.index(3, 3, 1)), "the first stair is half a block up from the ground (#209)");
        assertTrue(walked.get(good.index(7, 5, 1)), "and the landing is reached from the top tread");

        VoxelGrid short1 = stairFlight(5);
        BitSet stuck = SpaceChecks.walk(short1, seed, 2, 0.5D, true);
        assertTrue(stuck.get(short1.index(5, 5, 1)), "the flight itself still climbs");
        assertTrue(!stuck.get(short1.index(7, 6, 1)), "a top tread a full block under the landing needs a jump");

        assertTrue(SpaceChecks.walk(short1, seed, 2, 1, true).get(short1.index(7, 6, 1)),
            "a whole step of 1 counts cells as before, and climbs it");
    }

    @Test
    void aStairWalkedDownFromItsHighSideIsAHalfStepToo() {
        VoxelGrid grid = stairFlight(4);
        BitSet down = SpaceChecks.walk(grid, Collections.singletonList(new int[] {8, 5, 1}), 2, 0.5D, true);
        assertTrue(down.get(grid.index(1, 2, 1)), "from the landing back down to the ground");
    }

    @Test
    void aCollisionTopIsRoundedUpToSixteenths() {
        assertEquals(8, VoxelGrid.topOf(Collections.singletonList(new double[] {0, 0, 0, 1, 0.5, 1})));
        assertEquals(1, VoxelGrid.topOf(Collections.singletonList(new double[] {0, 0, 0, 1, 0.0625, 1})));
        assertEquals(16, VoxelGrid.topOf(Collections.singletonList(new double[] {0, 0, 0, 1, 1.0, 1})));
    }
}
