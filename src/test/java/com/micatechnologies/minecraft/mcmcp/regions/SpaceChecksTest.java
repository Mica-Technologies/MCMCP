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
}
