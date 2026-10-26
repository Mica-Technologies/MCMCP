package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.BuildTarget;
import com.micatechnologies.minecraft.mcmcp.regions.SpaceChecks;
import com.micatechnologies.minecraft.mcmcp.regions.VoxelGrid;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Space checks on a live building: is the inside sealed, and can people walk where they should and only
 * there. Every fit-out agent wrote its own walk check and every building got a watertight script; this
 * is one call each, with counts and clusters back (#138 plan, Phase 1).
 *
 * <p>The world is read once into a {@link VoxelGrid} on the game thread, in slices, and checked off it.
 * A cell is open when it has no collision box (air, lighting helpers, torches, signs), a door when it is
 * a door, trapdoor or gate, and solid otherwise: glass, panes, fences and slabs close an enclosure,
 * which is what makes them designed openings rather than holes.
 */
public final class ServerSpaceTools {

    /** Most cells one check reads. */
    static final long MAX_CELLS = 16_000_000L;
    private static final long SLICE_NANOS = 25_000_000L;

    private ServerSpaceTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_check_enclosure")
            .title("Check a building is sealed")
            .description("Flood the air inside a building from one or more inside cells and report leaks: interior "
                + "air touching air that is obviously outside (open sky straight up, or a clear line to the box "
                + "edge along x or z). Doors, glass, panes, fences and slabs are closed, so only real holes "
                + "leak. Leaks come back as clusters (count, bbox, one sample), largest first. Give the box, or a "
                + "target (server_target) whose bounds plus 'margin' are used. openZones are designed open-air "
                + "spaces (roof decks, terraces, atria) that count as outside. separate: true floods each seed "
                + "on its own and says which other seeds it reaches: suites that open into each other.")
            .schema(spaceSchema(JsonSchema.object())
                .property("openZones", described("array", "Boxes [x1, y1, z1, x2, y2, z2] of designed open-air space, "
                    + "counted as outside."))
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> check(context, true))
            .build());

        McpRegistry.registerTool(McpTool.named("server_check_walk")
            .title("Check where people can walk")
            .description("Flood where a person can walk from seeds (a lift lobby, front doors): a cell is standable "
                + "when it and the headroom above are clear and the cell below is solid, and a step moves to a "
                + "neighbour up to 'step' blocks higher or lower. Reports how many cells each door mode reaches "
                + "(doors 'open', 'closed' or 'both'), and the standable places nobody can reach, as clusters "
                + "(a room with no way in). separate: true floods each seed alone with doors closed and says "
                + "which other seeds it reaches: homes or suites that open into each other.")
            .schema(spaceSchema(JsonSchema.object())
                .integer("headroom", "Clear cells needed to stand. Default 2.", 1, 4)
                .integer("step", "Most blocks up or down in one move. Default 1.", 0, 2)
                .enumeration("doors", "Doors open, closed, or both (two floods). Default both.", "open", "closed", "both")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> check(context, false))
            .build());
    }

    private static JsonSchema spaceSchema(JsonSchema schema) {
        return ServerChunkTools.loadArgument(schema
            .array("box", "The box [x1, y1, z1, x2, y2, z2] to check. Or give 'target'.", Json.obj("type", "integer"))
            .string("target", "Instead of box: a build target or pattern whose bounds, plus margin, are the box.")
            .integer("margin", "With target: blocks added round its bounds. Default 2.", 0, 32)
            .integer("dimension", "Dimension id, with box. Default 0.")
            .property("seeds", described("array", "Where to start: [x, y, z] each, or {name, at: [x, y, z]} to have "
                + "results named."))
            .bool("separate", "Flood each seed on its own and report which other seeds it reaches.")
            .array("passable", "More block ids that count as open (a lighting helper with a collision box).",
                Json.obj("type", "string"))
            .integer("clusters", "Clusters listed. Default 10.", 0, 100));
    }

    private static final class Seed {
        final String name;
        final int[] at;

        Seed(String name, int[] at) {
            this.name = name;
            this.at = at;
        }
    }

    @Nullable
    private static List<Seed> seeds(ToolContext context) {
        List<Seed> seeds = new ArrayList<>();
        JsonElement raw = context.getArguments().get("seeds");
        if (raw == null || !raw.isJsonArray()) {
            return null;
        }
        for (JsonElement item : raw.getAsJsonArray()) {
            JsonArray at;
            String name;
            if (item.isJsonArray()) {
                at = item.getAsJsonArray();
                name = null;
            }
            else if (item.isJsonObject() && item.getAsJsonObject().has("at")) {
                at = item.getAsJsonObject().getAsJsonArray("at");
                name = Json.getString(item.getAsJsonObject(), "name", null);
            }
            else {
                return null;
            }
            if (at == null || at.size() != 3) {
                return null;
            }
            int[] cell = {at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt()};
            seeds.add(new Seed(name == null ? cell[0] + "," + cell[1] + "," + cell[2] : name, cell));
        }
        return seeds.isEmpty() ? null : seeds;
    }

    private static ToolResult check(final ToolContext context, final boolean enclosure) throws Exception {
        final List<Seed> seeds = seeds(context);
        if (seeds == null) {
            return ToolResult.error("'seeds' is a list of [x, y, z] cells, or {name, at: [x, y, z]}, inside the "
                + "building.");
        }
        final int[] box;
        final int dimension;
        if (context.has("target")) {
            String name = context.getString("target", "");
            BuildTarget target = ServerTargetTools.store().union(name);
            int[] bounds = target == null ? null : target.bounds();
            if (bounds == null) {
                return ToolResult.error("No target '" + name + "' with any cells in this world.");
            }
            int margin = context.getBoundedInt("margin", 2, 0, 32);
            box = new int[] {bounds[0] - margin, Math.max(0, bounds[1] - margin), bounds[2] - margin,
                bounds[3] + margin, Math.min(255, bounds[4] + margin), bounds[5] + margin};
            dimension = target.dimension();
        }
        else {
            List<Integer> raw = new ArrayList<>();
            JsonElement array = context.getArguments().get("box");
            if (array != null && array.isJsonArray()) {
                for (JsonElement v : array.getAsJsonArray()) {
                    raw.add(v.getAsInt());
                }
            }
            if (raw.size() != 6) {
                return ToolResult.error("Give 'box' as [x1, y1, z1, x2, y2, z2], or a 'target'.");
            }
            box = new int[] {Math.min(raw.get(0), raw.get(3)), Math.max(0, Math.min(raw.get(1), raw.get(4))),
                Math.min(raw.get(2), raw.get(5)), Math.max(raw.get(0), raw.get(3)), Math.min(255, Math.max(raw.get(1), raw.get(4))),
                Math.max(raw.get(2), raw.get(5))};
            dimension = context.getInt("dimension", 0);
        }
        long volume = VoxelGrid.volume(box[0], box[1], box[2], box[3], box[4], box[5]);
        if (volume > MAX_CELLS) {
            return ToolResult.error("That box is " + volume + " cells; a check reads at most " + MAX_CELLS + ".");
        }
        final Set<String> passable = new HashSet<>(Json.getStringList(context.getArguments(), "passable"));
        passable.add("csm:lightupair");
        final int clusterLimit = context.getBoundedInt("clusters", 10, 0, 100);
        final boolean separate = context.getBoolean("separate", false);

        return ServerChunkTools.withChunks(context, dimension, ChunkKeys.box(box[0], box[2], box[3], box[5]), outcome -> {
            VoxelGrid grid = new VoxelGrid(box[0], box[1], box[2], box[3], box[4], box[5]);
            long unloaded = read(context, dimension, grid, passable);

            JsonObject json = new JsonObject();
            json.add("box", SpaceArrays.of(box));
            if (unloaded > 0) {
                json.addProperty("unloaded", unloaded);
                json.addProperty("note", unloaded + " cells were in unloaded chunks and count as solid. Pass load: true.");
            }
            List<int[]> cells = new ArrayList<>();
            for (Seed seed : seeds) {
                cells.add(seed.at);
            }
            if (enclosure) {
                List<int[]> zones = boxes(context.getArguments().get("openZones"));
                SpaceChecks.Enclosure result = SpaceChecks.enclosure(grid, cells, zones);
                json.addProperty("interiorCells", result.interior.cardinality());
                json.addProperty("leakCells", result.leaks.cardinality());
                List<SpaceChecks.Cluster> clusters = SpaceChecks.clusters(grid, result.leaks);
                json.addProperty("leakClusters", clusters.size());
                if (!clusters.isEmpty()) {
                    json.add("clusters", SpaceChecks.clustersJson(clusters, clusterLimit));
                }
                if (!result.seedsNotOpen.isEmpty()) {
                    JsonArray bad = new JsonArray();
                    for (int[] seed : result.seedsNotOpen) {
                        bad.add(SpaceArrays.of(seed));
                    }
                    json.add("seedsNotInAir", bad);
                }
                if (separate) {
                    json.add("separation", separation(grid, seeds, (g, s) ->
                        SpaceChecks.enclosure(g, s, zones).interior));
                }
            }
            else {
                int headroom = context.getBoundedInt("headroom", 2, 1, 4);
                int step = context.getBoundedInt("step", 1, 0, 2);
                String doors = context.getString("doors", "both");
                for (String mode : "both".equals(doors) ? new String[] {"open", "closed"} : new String[] {doors}) {
                    boolean open = "open".equals(mode);
                    BitSet reached = SpaceChecks.walk(grid, cells, headroom, step, open);
                    JsonObject result = new JsonObject();
                    result.addProperty("reached", reached.cardinality());
                    BitSet unreached = SpaceChecks.standableCells(grid, headroom, open);
                    unreached.andNot(reached);
                    List<SpaceChecks.Cluster> clusters = SpaceChecks.clusters(grid, unreached);
                    result.addProperty("unreachedCells", unreached.cardinality());
                    result.addProperty("unreachedAreas", clusters.size());
                    if (!clusters.isEmpty()) {
                        result.add("unreached", SpaceChecks.clustersJson(clusters, clusterLimit));
                    }
                    json.add("doors" + Character.toUpperCase(mode.charAt(0)) + mode.substring(1), result);
                }
                JsonArray notStanding = new JsonArray();
                for (Seed seed : seeds) {
                    if (!SpaceChecks.standable(grid, seed.at[0], seed.at[1], seed.at[2], headroom, true)) {
                        notStanding.add(seed.name);
                    }
                }
                if (notStanding.size() > 0) {
                    json.add("seedsNotStandable", notStanding);
                }
                if (separate) {
                    json.add("separation", separation(grid, seeds, (g, s) ->
                        SpaceChecks.walk(g, s, headroom, step, false)));
                }
            }
            return ToolResult.structured(json);
        });
    }

    private interface Flood {
        BitSet run(VoxelGrid grid, List<int[]> seeds);
    }

    /** Each seed alone: how far it gets, and which other seeds it reaches. */
    private static JsonArray separation(VoxelGrid grid, List<Seed> seeds, Flood flood) {
        JsonArray rows = new JsonArray();
        for (Seed seed : seeds) {
            List<int[]> one = new ArrayList<>();
            one.add(seed.at);
            BitSet reached = flood.run(grid, one);
            JsonArray reaches = new JsonArray();
            for (Seed other : seeds) {
                if (other != seed && grid.contains(other.at[0], other.at[1], other.at[2])
                    && SpaceArrays.reached(grid, reached, other.at)) {
                    reaches.add(other.name);
                }
            }
            JsonObject row = new JsonObject();
            row.addProperty("seed", seed.name);
            row.addProperty("cells", reached.cardinality());
            row.add("reaches", reaches);
            rows.add(row);
        }
        return rows;
    }

    private static List<int[]> boxes(@Nullable JsonElement raw) {
        List<int[]> out = new ArrayList<>();
        if (raw == null || !raw.isJsonArray()) {
            return out;
        }
        for (JsonElement item : raw.getAsJsonArray()) {
            if (item.isJsonArray() && item.getAsJsonArray().size() == 6) {
                int[] v = new int[6];
                for (int i = 0; i < 6; i++) {
                    v[i] = item.getAsJsonArray().get(i).getAsInt();
                }
                out.add(v);
            }
        }
        return out;
    }

    /** Reads the box into the grid, a slice of x columns at a time. Returns cells left unknown. */
    private static long read(ToolContext context, int dimension, VoxelGrid grid, Set<String> passable) {
        final int[] x = {grid.minX};
        final long[] unloaded = {0L};
        while (x[0] <= grid.maxX) {
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                long started = System.nanoTime();
                while (x[0] <= grid.maxX && System.nanoTime() - started < SLICE_NANOS) {
                    for (int z = grid.minZ; z <= grid.maxZ; z++) {
                        pos.setPos(x[0], 0, z);
                        if (!world.isBlockLoaded(pos)) {
                            unloaded[0] += grid.maxY - grid.minY + 1;
                            continue;  // already SOLID (0)
                        }
                        for (int y = grid.minY; y <= grid.maxY; y++) {
                            pos.setPos(x[0], y, z);
                            grid.set(x[0], y, z, classify(world, pos, world.getBlockState(pos), passable));
                        }
                    }
                    x[0]++;
                }
                return null;
            });
            context.reportProgress(x[0] - grid.minX, grid.maxX - grid.minX + 1, "Reading the box");
        }
        return unloaded[0];
    }

    static byte classify(WorldServer world, BlockPos pos, IBlockState state, Set<String> passable) {
        Block block = state.getBlock();
        if (state.getMaterial() == net.minecraft.block.material.Material.AIR) {
            return VoxelGrid.OPEN;
        }
        ResourceLocation id = block.getRegistryName();
        if (id != null && passable.contains(id.toString())) {
            return VoxelGrid.OPEN;
        }
        if (block instanceof BlockDoor || block instanceof BlockTrapDoor || block instanceof BlockFenceGate
            || (id != null && id.getPath().contains("door"))) {
            return VoxelGrid.DOOR;
        }
        try {
            return state.getCollisionBoundingBox(world, pos) == Block.NULL_AABB ? VoxelGrid.OPEN : VoxelGrid.SOLID;
        }
        catch (RuntimeException e) {
            return VoxelGrid.SOLID;
        }
    }

    private static JsonObject described(String type, String description) {
        JsonObject property = Json.obj("type", type);
        property.addProperty("description", description);
        return property;
    }

    /** Small helpers kept apart so the checks above read as what they do. */
    static final class SpaceArrays {
        private SpaceArrays() {
        }

        static JsonArray of(int[] values) {
            JsonArray array = new JsonArray();
            for (int value : values) {
                array.add(value);
            }
            return array;
        }

        static boolean reached(VoxelGrid grid, BitSet reached, int[] at) {
            for (int dy = 0; dy <= 1; dy++) {
                if (grid.contains(at[0], at[1] + dy, at[2])
                    && reached.get(indexOf(grid, at[0], at[1] + dy, at[2]))) {
                    return true;
                }
            }
            return false;
        }

        private static int indexOf(VoxelGrid grid, int x, int y, int z) {
            int sizeY = grid.maxY - grid.minY + 1;
            int sizeZ = grid.maxZ - grid.minZ + 1;
            return ((x - grid.minX) * sizeY + (y - grid.minY)) * sizeZ + (z - grid.minZ);
        }
    }
}
