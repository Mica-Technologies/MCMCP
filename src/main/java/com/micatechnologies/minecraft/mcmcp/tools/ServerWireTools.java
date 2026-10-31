package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.WireEnds;
import com.micatechnologies.minecraft.mcmcp.regions.WireGraph;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.common.Loader;

/**
 * Immersive Engineering's wire connections in a box (#160): what each connector is wired to, with
 * which wire, and which connected piece (a feeder) it belongs to.
 *
 * <p>IE keeps the graph in {@code ImmersiveNetHandler.INSTANCE}, not in tile-entity NBT, so
 * {@code server_get_tile_entities} shows each end's wire count and never where the wires go. A soft
 * dependency: IE's classes are looked up by name, and the tool is registered only when IE is loaded,
 * so it is never listed where it cannot work. Its fields and methods are not obfuscated: they are
 * IE's own names in every build.
 *
 * <p>{@code ends} (#162) reads what sits at each end and what each connector is mounted on, so "which
 * pieces are live, and what do they feed" is one call. {@code whole} lists the dimension's every piece:
 * IE already holds the whole graph, so the box cap does not apply. Ends are read only where loaded,
 * unless {@code load} asks the chunk governor for them.
 */
public final class ServerWireTools {

    private static final String HANDLER = "blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler";

    /** Most wires listed in one reply. */
    static final int MAX_WIRES = 2_000;

    /** Most cells a box may span, as for the other box reads. */
    static final long MAX_BOX_CELLS = 64L * 1024L * 1024L;

    /** Most wire ends read in one call: a whole city's grid was 13,274. */
    static final int MAX_END_READS = 30_000;

    private ServerWireTools() {
    }

    public static void register() {
        if (!Loader.isModLoaded("immersiveengineering")) {
            return;
        }
        McpRegistry.registerTool(McpTool.named("server_wire_connections")
            .title("Read Immersive Engineering wires")
            .description("Immersive Engineering's wire connections with an end in a box: each wire's start and end "
                + "[x, y, z], wire type and length in blocks, and whether the far end's chunk is loaded. IE keeps "
                + "these out of tile-entity NBT, so this is the only way to see what a connector is wired to. "
                + "groupBy 'component' returns the connected pieces with a node in the box instead (one feeder "
                + "is one piece): node and wire counts, wire types and bounding box, nodes outside the box "
                + "included. whole: true reads the whole dimension instead of a box (no x/z needed). ends: true "
                + "adds what is at each end: block, tile entity id, the block the connector is mounted on and "
                + "its energy; with groupBy 'component' each piece also gets its sources (mounted on something "
                + "that only gives energy), loads (only takes it) and stores. Ends in unloaded chunks are marked, "
                + "not read, unless load: true. Read-only.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .integer("x", "X of one corner. Required unless whole.")
                .integer("y", "Y of one corner. Default 0.")
                .integer("z", "Z of one corner. Required unless whole.")
                .integer("toX", "X of the opposite corner. Default x.")
                .integer("toY", "Y of the opposite corner. Default 255.")
                .integer("toZ", "Z of the opposite corner. Default z.")
                .integer("dimension", "Dimension id. Default 0.")
                .bool("whole", "Read the whole dimension; the box is ignored.")
                .enumeration("groupBy", "'component': connected pieces instead of wires.", "component")
                .bool("ends", "Read the block at each listed end and what its connector is mounted on, with "
                    + "energy; pieces get sources/loads/stores. At most " + MAX_END_READS + " ends a call.")
                .integer("limit", "Wires or pieces listed. Default 500.", 1, MAX_WIRES))
                .build())
            .serverOnly()
            .readOnly()
            .handler(ServerWireTools::wires)
            .build());
    }

    private static ToolResult wires(final ToolContext context) throws Exception {
        final boolean whole = context.getBoolean("whole", false);
        final int[] box;
        if (whole) {
            box = WireGraph.EVERYWHERE;
        }
        else {
            if (!context.has("x") || !context.has("z")) {
                return ToolResult.error("Pass x and z (and toX/toZ for a box), or whole: true for the whole "
                    + "dimension.");
            }
            final int x1 = context.requireInt("x");
            final int z1 = context.requireInt("z");
            final int y1 = context.getInt("y", 0);
            final int x2 = context.getInt("toX", x1);
            final int y2 = context.getInt("toY", 255);
            final int z2 = context.getInt("toZ", z1);
            box = new int[] {Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2),
                Math.max(y1, y2), Math.max(z1, z2)};
            long cells = (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
            if (cells > MAX_BOX_CELLS) {
                return ToolResult.error("That box is " + cells + " cells; one read spans at most " + MAX_BOX_CELLS
                    + ". Pass whole: true to read the whole dimension.");
            }
        }
        final int dimension = context.getInt("dimension", 0);
        final boolean components = "component".equals(context.getString("groupBy", null));
        final boolean ends = context.getBoolean("ends", false);
        final boolean load = context.getBoolean("load", false);
        final int limit = context.getBoundedInt("limit", 500, 1, MAX_WIRES);
        if (load && !ends) {
            return ToolResult.error("load only matters for ends: the wires themselves are read from IE whether "
                + "loaded or not. Pass ends: true, or leave load out.");
        }

        Object read = context.onGameThread(() -> {
            ServerWorldTools.requireWorld(dimension);
            WireGraph graph = new WireGraph();
            String problem = read(dimension, graph);
            return problem != null ? problem : graph;
        });
        if (read instanceof String) {
            return ToolResult.error((String) read);
        }
        WireGraph graph = (WireGraph) read;

        final List<WireGraph.Component> pieces = components ? graph.components(box) : null;
        final List<WireGraph.Wire> touching = components ? null : graph.touching(box);
        // The ends to read: every node of each listed piece, or both ends of each listed wire.
        final Map<String, int[]> nodes = new LinkedHashMap<>();
        if (ends) {
            if (pieces != null) {
                for (int i = 0; i < pieces.size() && i < limit; i++) {
                    for (int[] node : pieces.get(i).nodes) {
                        nodes.put(WireEnds.key(node), node);
                    }
                }
            }
            else {
                for (int i = 0; i < touching.size() && i < limit; i++) {
                    nodes.put(WireEnds.key(touching.get(i).start), touching.get(i).start);
                    nodes.put(WireEnds.key(touching.get(i).end), touching.get(i).end);
                }
            }
            if (nodes.size() > MAX_END_READS) {
                return ToolResult.error("Those " + Math.min(limit, pieces != null ? pieces.size() : touching.size())
                    + " " + (pieces != null ? "pieces" : "wires") + " have " + nodes.size() + " ends; one call reads "
                    + "at most " + MAX_END_READS + ". Lower limit or narrow the box.");
            }
        }
        // A connector's mounted block is one step away, so the chunk beside an end on a chunk edge too.
        final Set<Long> chunks = new LinkedHashSet<>();
        if (load) {
            for (int[] node : nodes.values()) {
                chunks.add(ChunkKeys.ofBlock(node[0], node[2]));
                chunks.add(ChunkKeys.ofBlock(node[0] + 1, node[2]));
                chunks.add(ChunkKeys.ofBlock(node[0] - 1, node[2]));
                chunks.add(ChunkKeys.ofBlock(node[0], node[2] + 1));
                chunks.add(ChunkKeys.ofBlock(node[0], node[2] - 1));
            }
        }

        return ServerChunkTools.withChunks(context, dimension, chunks, load, outcome -> {
            JsonObject json = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                Map<String, WireEnds.End> read2 = new HashMap<>();
                for (Map.Entry<String, int[]> node : nodes.entrySet()) {
                    read2.put(node.getKey(), describe(world, node.getValue()));
                }
                JsonObject out = pieces != null ? piecesJson(pieces, box, limit, ends, read2)
                    : wiresJson(world, touching, box, limit);
                out.addProperty("dimension", dimension);
                if (ends && pieces == null) {
                    JsonObject endsJson = new JsonObject();
                    for (Map.Entry<String, WireEnds.End> end : read2.entrySet()) {
                        endsJson.add(end.getKey(), WireEnds.toJson(end.getValue()));
                    }
                    out.add("ends", endsJson);
                }
                if (outcome != null) {
                    ChunkLoadGovernor.touch(dimension, chunks);
                    out.add("chunks", outcome.toJson());
                }
                return out;
            });
            return ToolResult.structured(json);
        });
    }

    /** Game thread. */
    private static JsonObject wiresJson(WorldServer world, List<WireGraph.Wire> touching, int[] box, int limit) {
        JsonObject json = new JsonObject();
        json.addProperty("wires", touching.size());
        JsonArray rows = new JsonArray();
        for (int i = 0; i < touching.size() && i < limit; i++) {
            WireGraph.Wire wire = touching.get(i);
            int[] far = WireGraph.inside(box, wire.start) ? wire.end : wire.start;
            JsonArray row = new JsonArray();
            row.add(ints(wire.start));
            row.add(ints(wire.end));
            row.add(wire.type);
            row.add(wire.length);
            row.add(world.isBlockLoaded(new BlockPos(far[0], far[1], far[2])));
            rows.add(row);
        }
        // One row per wire, its fields named once.
        json.addProperty("rowsAre", "[start, end, wireType, length, farEndLoaded]");
        json.add("rows", rows);
        if (touching.size() > limit) {
            json.addProperty("truncated", true);
        }
        return json;
    }

    private static JsonObject piecesJson(List<WireGraph.Component> pieces, int[] box, int limit, boolean ends,
                                         Map<String, WireEnds.End> read) {
        JsonObject json = new JsonObject();
        json.addProperty("components", pieces.size());
        JsonArray rows = new JsonArray();
        int fed = 0;
        int loadsFed = 0;
        int loadsUnfed = 0;
        for (int i = 0; i < pieces.size() && i < limit; i++) {
            WireGraph.Component piece = pieces.get(i);
            JsonObject row = new JsonObject();
            row.addProperty("nodes", piece.nodes.size());
            row.addProperty("wires", piece.wires.size());
            row.add("types", Json.arrayOfStrings(new ArrayList<>(piece.types)));
            row.add("min", ints(piece.min));
            row.add("max", ints(piece.max));
            if (box != WireGraph.EVERYWHERE) {
                int inBox = 0;
                for (int[] node : piece.nodes) {
                    if (WireGraph.inside(box, node)) {
                        inBox++;
                    }
                }
                row.addProperty("nodesInBox", inBox);
            }
            if (ends) {
                WireEnds.Summary summary = WireEnds.summarise(piece, read);
                WireEnds.addTo(row, summary);
                if (summary.fed()) {
                    fed++;
                    loadsFed += summary.loads;
                }
                else {
                    loadsUnfed += summary.loads;
                }
            }
            rows.add(row);
        }
        json.add("pieces", rows);
        if (pieces.size() > limit) {
            json.addProperty("truncated", true);
        }
        if (ends) {
            JsonObject summary = new JsonObject();
            summary.addProperty("piecesWithSource", fed);
            summary.addProperty("piecesWithoutSource", Math.min(limit, pieces.size()) - fed);
            summary.addProperty("loadsOnFedPieces", loadsFed);
            summary.addProperty("loadsOnUnfedPieces", loadsUnfed);
            json.add("summary", summary);
            json.addProperty("rolesAre", "[role, node, mountedTe, energy, maxEnergy]");
        }
        return json;
    }

    /**
     * What sits at one wire end, read only where loaded: {@code isBlockLoaded} first, because
     * {@code getTileEntity} on an unloaded position loads the chunk. Game thread.
     */
    private static WireEnds.End describe(WorldServer world, int[] at) {
        WireEnds.End end = new WireEnds.End();
        BlockPos pos = new BlockPos(at[0], at[1], at[2]);
        if (!world.isBlockLoaded(pos)) {
            end.loaded = false;
            return end;
        }
        try {
            end.block = blockId(world, pos);
            TileEntity tile = world.getTileEntity(pos);
            if (tile == null) {
                return end;
            }
            end.te = tileId(tile);
            EnumFacing facing = facingOf(tile);
            if (facing == null) {
                return end;
            }
            BlockPos mounted = pos.offset(facing);
            end.mounted = new int[] {mounted.getX(), mounted.getY(), mounted.getZ()};
            if (!world.isBlockLoaded(mounted)) {
                return end;
            }
            end.mountedBlock = blockId(world, mounted);
            TileEntity on = world.getTileEntity(mounted);
            if (on == null) {
                return end;
            }
            end.mountedTe = tileId(on);
            IEnergyStorage energy = energyOf(on, facing.getOpposite());
            if (energy != null) {
                end.energy = energy.getEnergyStored();
                end.maxEnergy = energy.getMaxEnergyStored();
                end.canExtract = energy.canExtract();
                end.canReceive = energy.canReceive();
            }
        }
        catch (RuntimeException e) {
            // A mod's tile entity that throws on a read leaves the rest of the end unread, not the call.
        }
        return end;
    }

    @Nullable
    private static IEnergyStorage energyOf(TileEntity tile, EnumFacing side) {
        if (tile.hasCapability(CapabilityEnergy.ENERGY, side)) {
            return tile.getCapability(CapabilityEnergy.ENERGY, side);
        }
        if (tile.hasCapability(CapabilityEnergy.ENERGY, null)) {
            return tile.getCapability(CapabilityEnergy.ENERGY, null);
        }
        return null;
    }

    private static String blockId(WorldServer world, BlockPos pos) {
        ResourceLocation name = world.getBlockState(pos).getBlock().getRegistryName();
        return name == null ? "unknown" : name.toString();
    }

    private static String tileId(TileEntity tile) {
        ResourceLocation key = TileEntity.getKey(tile.getClass());
        return key == null ? tile.getClass().getName() : key.toString();
    }

    /** {@code getFacing()} by class, or a marker for none: IE's connectors take it from IDirectionalTile. */
    private static final Map<Class<?>, Object> FACING = new ConcurrentHashMap<>();
    private static final Object NO_FACING = new Object();

    /** The side the connector is mounted on, from IE's {@code getFacing()}; null for a tile without one. */
    @Nullable
    private static EnumFacing facingOf(TileEntity tile) {
        Object method = FACING.computeIfAbsent(tile.getClass(), type -> {
            try {
                Method found = type.getMethod("getFacing");
                return EnumFacing.class.isAssignableFrom(found.getReturnType()) ? found : NO_FACING;
            }
            catch (NoSuchMethodException | RuntimeException e) {
                return NO_FACING;
            }
        });
        if (method == NO_FACING) {
            return null;
        }
        try {
            return (EnumFacing) ((Method) method).invoke(tile);
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Field start;
    @Nullable
    private static Field end;
    @Nullable
    private static Field cableType;
    @Nullable
    private static Field length;
    @Nullable
    private static Method uniqueName;

    /** Reads the dimension's connections into the graph, or says why it could not. Game thread. */
    @Nullable
    private static String read(int dimension, WireGraph graph) {
        Collection<?> connections;
        try {
            Class<?> handler = Class.forName(HANDLER);
            Object instance = handler.getField("INSTANCE").get(null);
            connections = (Collection<?>) handler.getMethod("getAllConnections", int.class).invoke(instance, dimension);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "Immersive Engineering's wire network could not be read: " + e + ". This IE build may differ "
                + "from the one this tool knows.";
        }
        if (connections == null) {
            return null;
        }
        try {
            for (Object connection : connections) {
                if (start == null) {
                    Class<?> type = connection.getClass();
                    start = type.getField("start");
                    end = type.getField("end");
                    cableType = type.getField("cableType");
                    length = type.getField("length");
                }
                BlockPos a = (BlockPos) start.get(connection);
                BlockPos b = (BlockPos) end.get(connection);
                Object wire = cableType.get(connection);
                if (uniqueName == null && wire != null) {
                    uniqueName = Class.forName("blusunrize.immersiveengineering.api.energy.wires.WireType")
                        .getMethod("getUniqueName");
                }
                String name = wire == null ? "?" : String.valueOf(uniqueName.invoke(wire));
                graph.add(new int[] {a.getX(), a.getY(), a.getZ()}, new int[] {b.getX(), b.getY(), b.getZ()}, name,
                    length.getInt(connection));
            }
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return "Immersive Engineering's connections could not be read: " + e + ".";
        }
        return null;
    }

    private static JsonArray ints(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }
}
