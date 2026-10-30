package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.building.BuildProgram;
import com.micatechnologies.minecraft.mcmcp.building.DoorPlan;
import com.micatechnologies.minecraft.mcmcp.building.RailEdges;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.TargetDiff;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Building components a builder's generators re-derived in every building: door openings with their hinges
 * right, and railings whose edges face the drop (#138 plan, Phase 2b; specs and fixtures from the UIA
 * builder). Generic: blocks are parameters and their states are set by property name, only where the
 * block has the property. The arithmetic is pure ({@link DoorPlan}, {@link RailEdges}); the write goes
 * through {@code server_apply_ops}, with its undo points, budget, two-part pairing, dryRun and saveTarget.
 */
public final class ServerComponentTools {

    static final int MAX_OPENINGS = 500;
    static final int MAX_RAIL_CELLS = 20_000;

    private ServerComponentTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_place_doors")
            .title("Place doors and door pairs")
            .description("Place single doors and double-door pairs in openings, hinges right: each opening is "
                + "{x, y, z (the lower cell), block, facing (N/E/S/W: the way you walk in), width 1|2, axis (x|z, a "
                + "pair's direction; default across the facing), closer (default true, where the block has one), "
                + "hinge (left|right, singles), header (fills the cell above a 2-high door in a 3-high opening: "
                + "default when the wall stands on both sides of it there, 'auto' with the wall on either side, a "
                + "block id, or 'none')}. A pair hinges on its outer edges. Each door is written "
                + "lower half then upper, through server_apply_ops (undo point, dryRun, saveTarget).")
            .schema(componentSchema(JsonSchema.object())
                .property("openings", Json.obj("type", "array"))
                .required("openings")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerComponentTools::doors)
            .build());

        McpRegistry.registerTool(McpTool.named("server_place_railing")
            .title("Place railings facing the drop")
            .description("Place a railing block whose north/east/south/west properties are the edges a guard runs "
                + "along (CSM railings, handrails), at 'cells' [[x, y, z]...] or in a box (the cells there holding a "
                + "block matching 'replace', or the style itself). mode 'auto' (default) sets each edge whose "
                + "neighbouring column has nothing solid in the dropDepth - 1 cells below (default 4): a terrace's "
                + "outside, an atrium's void; a ring corner takes both edges toward a dropping diagonal; a cell with "
                + "no drop (a short step) is skipped and listed. mode 'edges' sets 'edges' ('NE'...) everywhere. "
                + "'replace' (id patterns, '*' any run) names the guards to swap out, e.g. ['minecraft:glass_pane']. "
                + "Written through server_apply_ops (undo point, dryRun, saveTarget).")
            .schema(componentSchema(JsonSchema.object())
                .string("style", "The railing block, e.g. 'csm:railing_pipe_galvanized'.")
                .property("cells", Json.obj("type", "array"))
                .integer("x", "Box instead of cells: X of one corner.")
                .integer("y", "Y of one corner.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner.")
                .integer("toY", "Y of the opposite corner.")
                .integer("toZ", "Z of the opposite corner.")
                .enumeration("mode", "auto (default) or edges.", "auto", "edges")
                .string("edges", "mode edges: the edges to set, e.g. 'NE'.")
                .array("replace", "Guard blocks to swap for the style, as id patterns.", Json.obj("type", "string"))
                .integer("dropDepth", "How deep a fall counts as a drop. Default 4.", 2, 16)
                .required("style")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerComponentTools::railings)
            .build());
    }

    /** Registered apart so its long description sits with its handler. */
    public static void registerBuild() {
        McpRegistry.registerTool(McpTool.named("server_build")
            .title("Build from a spec")
            .description("Build from a short declarative spec instead of a generator script: the server expands it to "
                + "fill/setblock ops and writes only what differs (server_apply_ops: undo point, dryRun, saveTarget). "
                + "origin [x, y, z]; every other coordinate is relative to it. palette {char: block}, a block being "
                + "'id', 'id:meta' or 'id[prop=v,...]'. parts, later ones overwriting earlier: "
                + "{box: [x1,y1,z1,x2,y2,z2], fill, mode?: hollow|outline|keep|replace}; "
                + "{shell: box, wall, floor?, floorsEvery?, roof?, clear? (default true)}; "
                + "{line: [x1,y1,z1,x2,y2,z2], fill}; {columns: box, every: [dx,dz], size?: [sx,sz], fill}; "
                + "{plan: [\"WW.GG\", ...], at?, axis?: xz (floor plan; rows along z, height extrudes up) | xy | zy "
                + "(facades, rows top down), height?} ('.' or ' ' leaves a cell); "
                + "{repeat: part, step: [dx,dy,dz], count}; {mirror: part, axis: x|z, about: n} (the part and its "
                + "reflection); {stamp: '<build target>', at} (a saved target as a component). Up to 50,000 ops a "
                + "call; larger as server_job_submit kind 'build' with the same arguments.")
            .schema(componentSchema(JsonSchema.object())
                .array("origin", "[x, y, z] every other coordinate is relative to.", Json.obj("type", "integer"))
                .property("palette", Json.obj("type", "object"))
                .property("parts", Json.obj("type", "array"))
                .required("origin", "parts")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerComponentTools::build)
            .build());
    }

    /** The default rail and sconce, the first of each this game knows. */
    static final String[] STAIR_RAILS = {"csm:railing_pipe_galvanized", "minecraft:iron_bars"};
    static final String[] STAIR_SCONCES = {"csm:sconcehalfshellnickel", "minecraft:torch"};

    private static String firstKnown(String[] ids) {
        for (String id : ids) {
            if (BlockIds.resolve(id) != null) {
                return id;
            }
        }
        return ids[ids.length - 1];
    }

    /**
     * A vanilla torch for a sconce: the planner gives sconces CSM's meta (facing x 4 + 3, facing as the
     * horizontal index S0 W1 N2 E3, the way it faces off the wall); a torch's meta for the same facing is
     * east 1, west 2, south 3, north 4.
     */
    static String torchFor(String cell) {
        int colon = cell.lastIndexOf(':');
        int meta = Integer.parseInt(cell.substring(colon + 1));
        int[] torch = {3, 2, 4, 1};
        return "minecraft:torch:" + torch[(meta >> 2) & 3];
    }

    /** Patterns a stair never overwrites by default: what is fitted into a core after it is built. */
    static final List<String> STAIR_KEEP = java.util.Arrays.asList("*door*", "*sign*", "*panel*", "*sconce*",
        "*pull*", "*standpipe*");

    public static void registerStair() {
        McpRegistry.registerTool(McpTool.named("server_build_stair")
            .title("Build a switchback stair core")
            .description("Plan and build a compliant switchback stair inside an enclosure: box [x0, z0, x1, z1] (the "
                + "walls, inclusive), floors (the stand y of each floor's door, bottom up; the last is the roof "
                + "landing), doors {\"y\": [[x, z]...]} (door cells in the wall per floor), width (3, the minimum, "
                + "or 4), axis (x|z; default the longer interior), start (lo|hi: where the first flight leaves), "
                + "inset {\"y\": n}, top, walls (default true: the ring and bulkhead roof; 'fill': ring cells only "
                + "where the live cell is open, so a shared facade stays), skipFaces, doorHeight, palette {floor, wall, "
                + "tread, rail, sconce}. Rules: +1 steps, 3 headroom over every tread and landing, landings at "
                + "least the width deep, uneven rises split shorter first so each storey lands at its door's end, a "
                + "divider between the lanes, guards beside every drop, a light near every landing. A box that "
                + "cannot hold a compliant stair is refused with the fix ('core needs 2 more cells in x...'). "
                + "Cells holding doors, signs, panels, sconces, pulls or standpipes are kept ('keep' to change), and "
                + "a cell the plan wants as air is left when it holds what verify counts as air (csm:lightupair). "
                + "Returns the flights, the plan's own audit by rule (0 = compliant) and the write "
                + "(server_apply_ops: undo point, dryRun, saveTarget); a dryRun adds byChange, [want, is, count].")
            .schema(componentSchema(JsonSchema.object())
                .array("box", "[x0, z0, x1, z1]: the enclosure walls, inclusive.", Json.obj("type", "integer"))
                .array("floors", "Stand y of each floor's door, bottom up; the last is the roof landing.",
                    Json.obj("type", "integer"))
                .property("doors", Json.obj("type", "object"))
                .integer("width", "Flight width, 3 or more. Default 3.", 3, 8)
                .enumeration("axis", "The long axis flights run along. Default the longer interior.", "x", "z")
                .enumeration("start", "The end the first flight leaves from. Default lo.", "lo", "hi")
                .property("inset", Json.obj("type", "object"))
                .integer("top", "Last interior air row, to cap under an existing roof.", 1, 255)
                .property("walls", described(new String[] {"boolean", "string"}, "Build the enclosure ring and "
                    + "the bulkhead roof: true (default), false, or 'fill' to write ring cells only where the live cell "
                    + "is air or has no collision box, keeping a facade or neighbour's wall the core shares."))
                .array("skipFaces", "Ring faces never written, of N, E, S, W (z min, x max, z max, x min): a "
                    + "shared exterior wall.", Json.obj("type", "string"))
                .integer("doorHeight", "Door openings' height. Default 2: the cell over each door is the wall "
                    + "(kept if it holds a block, else the palette's wall). 3 cuts it open.", 2, 3)
                .property("palette", Json.obj("type", "object"))
                .array("keep", "Block patterns never overwritten. Default doors, signs, panels, sconces, pulls, "
                    + "standpipes; [] overwrites everything.", Json.obj("type", "string"))
                .required("box", "floors")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerComponentTools::stair)
            .build());
    }

    private static ToolResult stair(final ToolContext context) throws Exception {
        JsonObject cfg = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : context.getArguments().entrySet()) {
            cfg.add(entry.getKey(), entry.getValue());
        }
        final boolean fillWalls = cfg.has("walls") && cfg.get("walls").isJsonPrimitive()
            && "fill".equalsIgnoreCase(cfg.get("walls").getAsString());
        if (!cfg.has("walls") || fillWalls) {
            cfg.addProperty("walls", true);
        }
        final Set<Character> skipFaces = new java.util.HashSet<>();
        for (String face : Json.getStringList(context.getArguments(), "skipFaces")) {
            if (face.trim().length() != 1 || "NESW".indexOf(Character.toUpperCase(face.trim().charAt(0))) < 0) {
                return ToolResult.error("skipFaces takes N, E, S and W. Nothing was written.");
            }
            skipFaces.add(Character.toUpperCase(face.trim().charAt(0)));
        }
        final int doorHeight = context.getBoundedInt("doorHeight", 2, 2, 3);
        // Generic defaults that still pass a builder's rules where its blocks exist: a guard rail rather
        // than a bare pane, a sconce rather than nothing. The first of each list this game has is used.
        JsonObject palette = Json.getObject(cfg, "palette");
        if (palette == null) {
            palette = new JsonObject();
            cfg.add("palette", palette);
        }
        if (!palette.has("rail")) {
            palette.addProperty("rail", firstKnown(STAIR_RAILS));
        }
        if (!palette.has("sconce")) {
            palette.addProperty("sconce", firstKnown(STAIR_SCONCES));
        }
        final com.micatechnologies.minecraft.mcmcp.building.StairDesign.Input input;
        final com.micatechnologies.minecraft.mcmcp.building.StairDesign.Plan plan;
        try {
            input = com.micatechnologies.minecraft.mcmcp.building.StairDesign.Input.fromJson(cfg);
            plan = com.micatechnologies.minecraft.mcmcp.building.StairDesign.plan(input);
        }
        catch (com.micatechnologies.minecraft.mcmcp.building.StairDesign.DesignException e) {
            return ToolResult.error("Refused: " + e.getMessage() + ". Nothing was written.");
        }
        final int dimension = context.getInt("dimension", 0);
        final List<String> keep = context.has("keep") ? Json.getStringList(context.getArguments(), "keep") : STAIR_KEEP;
        final List<Map.Entry<com.micatechnologies.minecraft.mcmcp.building.Pos, String>> cells =
            new ArrayList<>(plan.cells.entrySet());
        final String rail = input.palette == null ? null : input.palette.rail;
        // A rail whose edges are stored (a CSM railing, not a pane) faces the drop: the plan is the world
        // it stands in, and a deck rail stands on a parapet over the stair well.
        final java.util.Set<String> railProps = rail == null ? java.util.Collections.<String>emptySet()
            : storedEdges(rail);
        final RailEdges.Solid planSolid = RailEdges.ofPlan(plan.cells, rail);
        final String wall = input.palette.wall;
        final int[] ring = {input.box[0], input.box[1], input.box[2], input.box[3]};
        final int ringFrom = input.floors.get(0) - 1;
        // The cell over each door, where the plan cuts a 3-high opening.
        final Set<Long> headers = new java.util.HashSet<>();
        if (doorHeight == 2) {
            for (Map.Entry<Integer, List<int[]>> floor : input.doors.entrySet()) {
                for (int[] door : floor.getValue()) {
                    headers.add(new BlockPos(door[0], floor.getKey() + 2, door[1]).toLong());
                }
            }
        }
        final com.micatechnologies.minecraft.mcmcp.regions.TargetDiff.Normaliser same =
            ServerTargetTools.normaliser(context, ServerTargetTools.store());
        final boolean dryRun = context.getBoolean("dryRun", false);
        final Map<String, int[]> byChange = new java.util.TreeMap<>();
        Set<Long> chunks = new LinkedHashSet<>();
        for (Map.Entry<com.micatechnologies.minecraft.mcmcp.building.Pos, String> cell : cells) {
            chunks.add(ChunkKeys.ofBlock(cell.getKey().x, cell.getKey().z));
        }
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final List<String> ops = new ArrayList<>();
            final int[] kept = {0};
            final int[] wallsKept = {0};
            final int[] headersKept = {0};
            final int[] airKept = {0};
            final int[] cursor = {0};
            while (cursor[0] < cells.size()) {
                context.onGameThread(() -> {
                    WorldServer world = ServerWorldTools.requireWorld(dimension);
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    long started = System.nanoTime();
                    while (cursor[0] < cells.size() && System.nanoTime() - started < 25_000_000L) {
                        Map.Entry<com.micatechnologies.minecraft.mcmcp.building.Pos, String> cell = cells.get(cursor[0]++);
                        com.micatechnologies.minecraft.mcmcp.building.Pos p = cell.getKey();
                        pos.setPos(p.x, p.y, p.z);
                        IBlockState live = world.isBlockLoaded(pos) ? world.getBlockState(pos) : null;
                        String block = cell.getValue();
                        if (!keep.isEmpty()) {
                            String id = live == null ? null : idOf(live);
                            if (id != null && !world.isAirBlock(pos) && matchesAny(keep, id)) {
                                kept[0]++;
                                continue;
                            }
                        }
                        boolean onRing = p.x == ring[0] || p.x == ring[2] || p.z == ring[1] || p.z == ring[3];
                        if (block.equals(wall) && onRing && p.y >= ringFrom && p.y <= plan.top + 1) {
                            char face = p.z == ring[1] ? 'N' : p.x == ring[2] ? 'E' : p.z == ring[3] ? 'S' : 'W';
                            if (skipFaces.contains(face) || fillWalls && live != null && !world.isAirBlock(pos)
                                && live.getCollisionBoundingBox(world, pos) != Block.NULL_AABB) {
                                wallsKept[0]++;
                                continue;
                            }
                        }
                        boolean wantsAir = block.equals("minecraft:air") || block.startsWith("minecraft:air:");
                        if (wantsAir && headers.contains(new BlockPos(p.x, p.y, p.z).toLong())) {
                            // A 2-high door's header: whatever wall is there stays, a hole gets the wall.
                            if (live != null && !world.isAirBlock(pos)) {
                                headersKept[0]++;
                                continue;
                            }
                            block = wall;
                            wantsAir = false;
                        }
                        if (wantsAir && live != null && !world.isAirBlock(pos)
                            && "minecraft:air".equals(same.normalise(ServerTargetTools.canonical(live)))) {
                            // A light helper is air to verify; clearing it would put the lights out (#146).
                            airKept[0]++;
                            continue;
                        }
                        if (dryRun && live != null && !(rail != null && block.equals(rail))) {
                            String want = wantsAir ? "minecraft:air"
                                : block.startsWith("minecraft:torch:") ? torchFor(block) : block;
                            if (!sameBlock(want, live)) {
                                byChange.computeIfAbsent(idOfText(want) + "\t" + idOf(live), k -> new int[1])[0]++;
                            }
                        }
                        if (block.startsWith("minecraft:torch:")) {
                            block = torchFor(block);
                        }
                        if (rail != null && block.equals(rail) && !railProps.isEmpty()) {
                            int mask = RailEdges.edges(planSolid, p.x, p.y, p.z, RailEdges.PARAPET_DEPTH);
                            if (mask != 0) {
                                Map<String, String> edges = new LinkedHashMap<>();
                                edges.put("north", String.valueOf((mask & RailEdges.NORTH) != 0));
                                edges.put("east", String.valueOf((mask & RailEdges.EAST) != 0));
                                edges.put("south", String.valueOf((mask & RailEdges.SOUTH) != 0));
                                edges.put("west", String.valueOf((mask & RailEdges.WEST) != 0));
                                ops.add(setblock(p.x, p.y, p.z, rail, edges, railProps));
                                continue;
                            }
                        }
                        ops.add(BuildProgram.setblockLine(p.x, p.y, p.z, block));
                    }
                    return null;
                });
            }
            JsonObject json = new JsonObject();
            json.addProperty("axis", plan.axis);
            json.addProperty("run", plan.run);
            json.addProperty("top", plan.top);
            json.addProperty("cells", cells.size());
            JsonArray flights = new JsonArray();
            for (com.micatechnologies.minecraft.mcmcp.building.StairDesign.Flight flight : plan.flights) {
                JsonArray row = new JsonArray();
                row.add(flight.storey);
                row.add(flight.ys);
                row.add(flight.n);
                row.add(flight.lane);
                flights.add(row);
            }
            json.add("flights", flights);
            json.addProperty("flightsAre", "[storey, start y, treads, lane]");
            JsonObject audit = new JsonObject();
            int failures = 0;
            for (Map.Entry<String, List<com.micatechnologies.minecraft.mcmcp.building.StairAudit.Failure>> rule
                : com.micatechnologies.minecraft.mcmcp.building.StairAudit.selfAudit(input, plan).byRule().entrySet()) {
                audit.addProperty(rule.getKey(), rule.getValue().size());
                failures += rule.getValue().size();
            }
            json.addProperty("auditFailures", failures);
            if (failures > 0) {
                json.add("audit", audit);
            }
            if (kept[0] > 0) {
                json.addProperty("kept", kept[0]);
            }
            if (wallsKept[0] > 0) {
                json.addProperty("wallsKept", wallsKept[0]);
            }
            if (headersKept[0] > 0) {
                json.addProperty("headersKept", headersKept[0]);
            }
            if (airKept[0] > 0) {
                json.addProperty("airKept", airKept[0]);
            }
            if (dryRun && !byChange.isEmpty()) {
                List<Map.Entry<String, int[]>> rows = new ArrayList<>(byChange.entrySet());
                rows.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
                JsonArray changes = new JsonArray();
                for (int i = 0; i < rows.size() && i < 50; i++) {
                    String[] ids = rows.get(i).getKey().split("\t", 2);
                    JsonArray row = new JsonArray();
                    row.add(ids[0]);
                    row.add(ids[1]);
                    row.add(rows.get(i).getValue()[0]);
                    changes.add(row);
                }
                json.add("byChange", changes);
            }
            write(context, dimension, ops, json);
            return ToolResult.structured(json);
        });
    }

    /**
     * The edge properties a block stores, of north/east/south/west: those that survive its metadata round
     * trip. Empty for a pane, bars or a fence, whose connections are computed from neighbours.
     */
    static java.util.Set<String> storedEdges(String id) {
        java.util.Set<String> stored = new java.util.LinkedHashSet<>();
        Block block = BlockIds.resolve(id);
        if (block == null) {
            return stored;
        }
        IBlockState state = block.getDefaultState();
        for (IProperty<?> property : state.getPropertyKeys()) {
            String name = property.getName();
            if (!("north".equals(name) || "east".equals(name) || "south".equals(name) || "west".equals(name))
                || property.getValueClass() != Boolean.class) {
                continue;
            }
            @SuppressWarnings("unchecked")
            IProperty<Boolean> edge = (IProperty<Boolean>) property;
            try {
                IBlockState on = state.withProperty(edge, !state.getValue(edge));
                IBlockState back = block.getStateFromMeta(block.getMetaFromState(on));
                if (back.getValue(edge).equals(on.getValue(edge))) {
                    stored.add(name);
                }
            }
            catch (RuntimeException e) {
                // A block whose meta mapping throws stores nothing we can rely on.
            }
        }
        return stored.size() == 4 ? stored : java.util.Collections.<String>emptySet();
    }

    /** A spec expanded to ops, components resolved from this world's build targets. */
    public static List<String> expand(JsonObject spec) {
        final com.micatechnologies.minecraft.mcmcp.regions.TargetStore store = ServerTargetTools.store();
        return BuildProgram.expand(spec, name -> {
            try {
                return store.union(name);
            }
            catch (java.io.IOException e) {
                return null;
            }
        });
    }

    private static ToolResult build(ToolContext context) throws Exception {
        List<String> ops;
        try {
            ops = expand(context.getArguments());
        }
        catch (BuildProgram.SpecException e) {
            return ToolResult.error(e.getMessage() + " Nothing was written.");
        }
        JsonObject json = new JsonObject();
        json.addProperty("ops", ops.size());
        write(context, context.getInt("dimension", 0), ops, json);
        return ToolResult.structured(json);
    }

    private static JsonSchema componentSchema(JsonSchema schema) {
        return ServerChunkTools.loadArgument(schema
            .integer("dimension", "Dimension id. Default 0.")
            .bool("dryRun", "Count what would be written; write nothing.")
            .string("saveTarget", "Save what this writes as a build target layer (see server_target).")
            .bool("replaceTarget", "With saveTarget: start the layer over."));
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /** The block's property names. Game thread. */
    private static Set<String> propertyNames(Block block) {
        Set<String> names = new LinkedHashSet<>();
        for (IProperty<?> property : block.getDefaultState().getPropertyKeys()) {
            names.add(property.getName());
        }
        return names;
    }

    /** {@code setblock x y z id k=v,...} with only the properties the block has. */
    static String setblock(int x, int y, int z, String id, Map<String, String> wanted, Set<String> has) {
        StringBuilder props = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(wanted).entrySet()) {
            if (has.contains(entry.getKey())) {
                props.append(props.length() == 0 ? "" : ",").append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return "setblock " + x + " " + y + " " + z + " " + id + (props.length() == 0 ? "" : " " + props);
    }

    /** Writes the ops through server_apply_ops with this call's write options, and adds its reply. */
    private static void write(ToolContext context, int dimension, List<String> ops, JsonObject into) throws Exception {
        if (ops.isEmpty()) {
            into.addProperty("written", 0);
            return;
        }
        McpTool apply = McpRegistry.tool("server_apply_ops", McmcpSide.SERVER);
        JsonObject args = new JsonObject();
        args.add("ops", Json.arrayOfStrings(ops));
        args.addProperty("dimension", dimension);
        for (String key : new String[] {"dryRun", "saveTarget", "replaceTarget", "load"}) {
            if (context.getArguments().has(key)) {
                args.add(key, context.getArguments().get(key));
            }
        }
        ToolResult result = apply.call(context.forPart(args, 0, 1, "write"));
        if (result.isError()) {
            into.addProperty("error", String.valueOf(result.getText()));
            return;
        }
        JsonObject reply = result.getStructuredContent();
        for (String key : new String[] {"written", "wouldWrite", "unchanged", "refused", "refusedAt", "undoPoint",
            "target", "sample"}) {
            if (reply != null && reply.has(key)) {
                into.add(key, reply.get(key));
            }
        }
    }

    /** The id of a plan block written {@code id} or {@code id:meta}. */
    private static String idOfText(String block) {
        int first = block.indexOf(':');
        int second = first < 0 ? -1 : block.indexOf(':', first + 1);
        return second < 0 ? block : block.substring(0, second);
    }

    /** Whether the live state is the plan block {@code id} or {@code id:meta}, as stored. Game thread. */
    private static boolean sameBlock(String block, IBlockState live) {
        String id = idOfText(block);
        if (!id.equals(idOf(live))) {
            return false;
        }
        if (id.length() == block.length()) {
            return true;
        }
        try {
            return live.getBlock().getMetaFromState(live) == Integer.parseInt(block.substring(id.length() + 1));
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    private static JsonObject described(String[] types, String description) {
        JsonObject property = new JsonObject();
        JsonArray type = new JsonArray();
        for (String t : types) {
            type.add(t);
        }
        property.add("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject rejected(JsonObject opening, String why) {
        JsonObject row = new JsonObject();
        row.add("opening", opening);
        row.addProperty("why", why);
        return row;
    }

    // ------------------------------------------------------------------
    // Doors
    // ------------------------------------------------------------------

    private static ToolResult doors(final ToolContext context) throws Exception {
        JsonElement raw = context.getArguments().get("openings");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() == 0) {
            return ToolResult.error("'openings' is a list of {x, y, z, block, facing, width}.");
        }
        if (raw.getAsJsonArray().size() > MAX_OPENINGS) {
            return ToolResult.error("At most " + MAX_OPENINGS + " openings a call.");
        }
        final int dimension = context.getInt("dimension", 0);
        final JsonArray rejected = new JsonArray();
        final List<JsonObject> rows = new ArrayList<>();
        final List<DoorPlan.Opening> openings = new ArrayList<>();
        for (JsonElement item : raw.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                rejected.add(rejected(new JsonObject(), "an opening is an object"));
                continue;
            }
            JsonObject o = item.getAsJsonObject();
            try {
                String axis = Json.getString(o, "axis", null);
                openings.add(new DoorPlan.Opening(Json.getInt(o, "x", 0), Json.getInt(o, "y", 0), Json.getInt(o, "z", 0),
                    Json.getString(o, "block", ""), DoorPlan.Facing.parse(Json.getString(o, "facing", "")),
                    Json.getInt(o, "width", 1), axis == null ? null : axis.charAt(0), Json.getBoolean(o, "closer", true),
                    "right".equalsIgnoreCase(Json.getString(o, "hinge", "left"))));
                rows.add(o);
            }
            catch (IllegalArgumentException e) {
                rejected.add(rejected(o, e.getMessage()));
            }
        }
        final Set<Long> chunks = new LinkedHashSet<>();
        for (DoorPlan.Opening opening : openings) {
            chunks.addAll(ChunkKeys.box(opening.x - 1, opening.z - 1, opening.x + 2, opening.z + 2));
        }
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final List<String> ops = new ArrayList<>();
            final int[] counts = new int[3];  // pairs, singles, headers
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                for (int i = 0; i < openings.size(); i++) {
                    DoorPlan.Opening opening = openings.get(i);
                    Block block = BlockIds.resolve(opening.block);
                    if (block == null) {
                        rejected.add(rejected(rows.get(i), BlockIds.describeUnknown(opening.block, "place")));
                        continue;
                    }
                    Set<String> has = propertyNames(block);
                    if (!has.contains("half") || !has.contains("facing")) {
                        rejected.add(rejected(rows.get(i), opening.block + " is not a door: it has no half and facing."));
                        continue;
                    }
                    for (DoorPlan.Leaf leaf : DoorPlan.leaves(opening)) {
                        Map<String, String> lower = new LinkedHashMap<>();
                        lower.put("half", "lower");
                        lower.put("facing", leaf.facing.stateName());
                        lower.put("open", "false");
                        Map<String, String> upper = new LinkedHashMap<>();
                        upper.put("half", "upper");
                        upper.put("hinge", leaf.hingeRight ? "right" : "left");
                        upper.put("closer", String.valueOf(leaf.closer));
                        // A door's facing and hinge usually live in one half only; the other half is
                        // still given them where it has the property, so the pair reads back whole.
                        upper.put("facing", leaf.facing.stateName());
                        lower.put("hinge", leaf.hingeRight ? "right" : "left");
                        ops.add(setblock(leaf.x, leaf.y, leaf.z, opening.block, lower, has));
                        ops.add(setblock(leaf.x, leaf.y + 1, leaf.z, opening.block, upper, has));
                    }
                    counts[opening.width == 2 ? 0 : 1]++;
                    // By default the hole over a 2-high door in a 3-high opening is closed when it is plainly
                    // a wall's (#146): left open, it showed up as an enclosure leak at roof bulkheads.
                    String header = Json.getString(rows.get(i), "header", "walled");
                    if (!"none".equals(header)) {
                        counts[2] += headers(world, opening, header, ops);
                    }
                }
                return null;
            });
            JsonObject json = new JsonObject();
            json.addProperty("pairs", counts[0]);
            json.addProperty("singles", counts[1]);
            if (counts[2] > 0) {
                json.addProperty("headers", counts[2]);
            }
            if (rejected.size() > 0) {
                json.add("rejected", rejected);
            }
            write(context, dimension, ops, json);
            return ToolResult.structured(json);
        });
    }

    /**
     * Fills the cell over each leaf at y + 2 when it is air: with the given block, or for 'auto' with the
     * wall beside the opening at that height. Game thread. Returns the headers added.
     */
    private static int headers(WorldServer world, DoorPlan.Opening opening, String header, List<String> ops) {
        int added = 0;
        String fill = null;
        boolean walled = "walled".equals(header);
        if (!"auto".equals(header) && !walled) {
            fill = header;
        }
        else {
            int span = opening.width - 1;
            int[][] beside = opening.axis == 'x'
                ? new int[][] {{opening.x - 1, opening.z}, {opening.x + span + 1, opening.z}}
                : new int[][] {{opening.x, opening.z - 1}, {opening.x, opening.z + span + 1}};
            for (int[] cell : beside) {
                IBlockState state = world.getBlockState(new BlockPos(cell[0], opening.y + 2, cell[1]));
                if (!world.isAirBlock(new BlockPos(cell[0], opening.y + 2, cell[1]))) {
                    if (fill == null) {
                        fill = ServerTargetTools.canonical(state);
                    }
                }
                else if (walled) {
                    // Not plainly a wall's hole: an opening that is meant to be taller, or a gap.
                    return 0;
                }
            }
        }
        if (fill == null) {
            return 0;
        }
        for (DoorPlan.Leaf leaf : DoorPlan.leaves(opening)) {
            if (world.isAirBlock(new BlockPos(leaf.x, leaf.y + 2, leaf.z))) {
                ops.add(ServerTargetTools.setblock(leaf.x, leaf.y + 2, leaf.z, fill));
                added++;
            }
        }
        return added;
    }

    // ------------------------------------------------------------------
    // Railings
    // ------------------------------------------------------------------

    private static ToolResult railings(final ToolContext context) throws Exception {
        final String style = context.getString("style", "");
        final int dimension = context.getInt("dimension", 0);
        final boolean auto = !"edges".equals(context.getString("mode", "auto"));
        final int fixed;
        try {
            fixed = auto ? 0 : RailEdges.parse(context.getString("edges", ""));
        }
        catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        if (!auto && fixed == 0) {
            return ToolResult.error("mode 'edges' needs 'edges', e.g. 'NE'.");
        }
        final int depth = context.getBoundedInt("dropDepth", 4, 2, 16);
        final List<String> replace = Json.getStringList(context.getArguments(), "replace");
        final List<int[]> listed = new ArrayList<>();
        JsonElement cells = context.getArguments().get("cells");
        if (cells != null && cells.isJsonArray()) {
            for (JsonElement item : cells.getAsJsonArray()) {
                if (item.isJsonArray() && item.getAsJsonArray().size() == 3) {
                    JsonArray c = item.getAsJsonArray();
                    listed.add(new int[] {c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()});
                }
            }
        }
        final int[] box;
        if (listed.isEmpty()) {
            if (!context.has("x") || !context.has("y") || !context.has("z")) {
                return ToolResult.error("Give 'cells' [[x, y, z]...], or a box (x, y, z, toX, toY, toZ).");
            }
            int x = context.getInt("x", 0);
            int y = context.getInt("y", 0);
            int z = context.getInt("z", 0);
            box = new int[] {Math.min(x, context.getInt("toX", x)), Math.min(y, context.getInt("toY", y)),
                Math.min(z, context.getInt("toZ", z)), Math.max(x, context.getInt("toX", x)),
                Math.max(y, context.getInt("toY", y)), Math.max(z, context.getInt("toZ", z))};
            long volume = (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
            if (volume > 4L * MAX_RAIL_CELLS * 16L) {
                return ToolResult.error("That box is " + volume + " cells; keep it to the guard lines.");
            }
        }
        else if (listed.size() > MAX_RAIL_CELLS) {
            return ToolResult.error("At most " + MAX_RAIL_CELLS + " cells a call.");
        }
        else {
            box = null;
        }
        final Set<Long> chunks = new LinkedHashSet<>();
        if (box != null) {
            chunks.addAll(ChunkKeys.box(box[0] - 1, box[2] - 1, box[3] + 1, box[5] + 1));
        }
        for (int[] c : listed) {
            chunks.addAll(ChunkKeys.box(c[0] - 1, c[2] - 1, c[0] + 1, c[2] + 1));
        }
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final List<String> ops = new ArrayList<>();
            final Map<Integer, Integer> byEdges = new TreeMap<>();
            final JsonArray skipped = new JsonArray();
            final long[] counts = new long[2];  // skipped, replaced
            String problem = context.onGameThread(() -> {
                Block railing = BlockIds.resolve(style);
                if (railing == null) {
                    return BlockIds.describeUnknown(style, "place");
                }
                Set<String> has = propertyNames(railing);
                if (!has.contains("north") || !has.contains("east") || !has.contains("south") || !has.contains("west")) {
                    return style + " has no north/east/south/west properties to set its edges with.";
                }
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                List<int[]> targets = listed;
                if (box != null) {
                    targets = new ArrayList<>();
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    for (int x = box[0]; x <= box[3]; x++) {
                        for (int y = box[1]; y <= box[4]; y++) {
                            for (int z = box[2]; z <= box[5]; z++) {
                                pos.setPos(x, y, z);
                                String id = idOf(world.getBlockState(pos));
                                if (id != null && (id.equals(style) || matchesAny(replace, id))) {
                                    targets.add(new int[] {x, y, z});
                                }
                            }
                        }
                    }
                }
                RailEdges.Solid solid = (x, y, z) -> solidForRails(world, new BlockPos(x, y, z), style, replace);
                for (int[] c : targets) {
                    int mask = auto ? RailEdges.edges(solid, c[0], c[1], c[2], depth) : fixed;
                    if (mask == 0) {
                        counts[0]++;
                        if (skipped.size() < 50) {
                            skipped.add(ServerSpaceTools.SpaceArrays.of(c));
                        }
                        continue;
                    }
                    String id = idOf(world.getBlockState(new BlockPos(c[0], c[1], c[2])));
                    if (id != null && matchesAny(replace, id)) {
                        counts[1]++;
                    }
                    Map<String, String> props = new LinkedHashMap<>();
                    props.put("north", String.valueOf((mask & RailEdges.NORTH) != 0));
                    props.put("east", String.valueOf((mask & RailEdges.EAST) != 0));
                    props.put("south", String.valueOf((mask & RailEdges.SOUTH) != 0));
                    props.put("west", String.valueOf((mask & RailEdges.WEST) != 0));
                    ops.add(setblock(c[0], c[1], c[2], style, props, has));
                    byEdges.merge(mask, 1, Integer::sum);
                }
                return null;
            });
            if (problem != null) {
                return ToolResult.error(problem);
            }
            JsonObject json = new JsonObject();
            json.addProperty("placed", ops.size());
            JsonObject edges = new JsonObject();
            for (Map.Entry<Integer, Integer> entry : byEdges.entrySet()) {
                edges.addProperty(edgeName(entry.getKey()), entry.getValue());
            }
            json.add("byEdges", edges);
            if (counts[1] > 0) {
                json.addProperty("replaced", counts[1]);
            }
            if (counts[0] > 0) {
                json.addProperty("skipped", counts[0]);
                json.add("skippedCells", skipped);
            }
            write(context, dimension, ops, json);
            return ToolResult.structured(json);
        });
    }

    private static String edgeName(int mask) {
        StringBuilder name = new StringBuilder();
        if ((mask & RailEdges.NORTH) != 0) {
            name.append('N');
        }
        if ((mask & RailEdges.EAST) != 0) {
            name.append('E');
        }
        if ((mask & RailEdges.SOUTH) != 0) {
            name.append('S');
        }
        if ((mask & RailEdges.WEST) != 0) {
            name.append('W');
        }
        return name.toString();
    }

    @Nullable
    private static String idOf(IBlockState state) {
        ResourceLocation id = state.getBlock().getRegistryName();
        return id == null ? null : id.toString();
    }

    private static boolean matchesAny(List<String> patterns, String id) {
        for (String pattern : patterns) {
            if (TargetDiff.Normaliser.matches(pattern, id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a person would not fall through, for finding a drop: anything with a collision box, except
     * lighting helpers and other guards (railings, the blocks being replaced). Game thread.
     */
    private static boolean solidForRails(WorldServer world, BlockPos pos, String style, List<String> replace) {
        if (!world.isBlockLoaded(pos)) {
            return true;
        }
        IBlockState state = world.getBlockState(pos);
        String id = idOf(state);
        if (id == null || world.isAirBlock(pos) || id.startsWith("csm:lightupair") || id.contains("railing")
            || id.contains("guardrail") || id.equals(style) || matchesAny(replace, id)) {
            return false;
        }
        try {
            return state.getCollisionBoundingBox(world, pos) != Block.NULL_AABB;
        }
        catch (RuntimeException e) {
            return true;
        }
    }
}
