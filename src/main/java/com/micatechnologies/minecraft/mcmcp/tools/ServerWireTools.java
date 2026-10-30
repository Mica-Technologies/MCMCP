package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.WireGraph;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
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
 */
public final class ServerWireTools {

    private static final String HANDLER = "blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler";

    /** Most wires listed in one reply. */
    static final int MAX_WIRES = 2_000;

    /** Most cells a box may span, as for the other box reads. */
    static final long MAX_BOX_CELLS = 64L * 1024L * 1024L;

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
                + "included. Read-only.")
            .schema(JsonSchema.object()
                .integer("x", "X of one corner.")
                .integer("y", "Y of one corner. Default 0.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Default x.")
                .integer("toY", "Y of the opposite corner. Default 255.")
                .integer("toZ", "Z of the opposite corner. Default z.")
                .integer("dimension", "Dimension id. Default 0.")
                .enumeration("groupBy", "'component': connected pieces instead of wires.", "component")
                .integer("limit", "Wires or pieces listed. Default 500.", 1, MAX_WIRES)
                .required("x", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(ServerWireTools::wires)
            .build());
    }

    private static ToolResult wires(ToolContext context) throws Exception {
        final int x1 = context.requireInt("x");
        final int z1 = context.requireInt("z");
        final int y1 = context.getInt("y", 0);
        final int x2 = context.getInt("toX", x1);
        final int y2 = context.getInt("toY", 255);
        final int z2 = context.getInt("toZ", z1);
        final int[] box = {Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2),
            Math.max(z1, z2)};
        long cells = (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
        if (cells > MAX_BOX_CELLS) {
            return ToolResult.error("That box is " + cells + " cells; one read spans at most " + MAX_BOX_CELLS + ".");
        }
        final int dimension = context.getInt("dimension", 0);
        final boolean components = "component".equals(context.getString("groupBy", null));
        final int limit = context.getBoundedInt("limit", 500, 1, MAX_WIRES);

        Object result = context.onGameThread(() -> {
            WorldServer world = ServerWorldTools.requireWorld(dimension);
            WireGraph graph = new WireGraph();
            String problem = read(dimension, graph);
            if (problem != null) {
                return problem;
            }
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            if (components) {
                List<WireGraph.Component> pieces = graph.components(box);
                json.addProperty("components", pieces.size());
                JsonArray rows = new JsonArray();
                for (int i = 0; i < pieces.size() && i < limit; i++) {
                    WireGraph.Component piece = pieces.get(i);
                    JsonObject row = new JsonObject();
                    row.addProperty("nodes", piece.nodes.size());
                    row.addProperty("wires", piece.wires.size());
                    row.add("types", Json.arrayOfStrings(new java.util.ArrayList<>(piece.types)));
                    row.add("min", ints(piece.min));
                    row.add("max", ints(piece.max));
                    int inBox = 0;
                    for (int[] node : piece.nodes) {
                        if (WireGraph.inside(box, node)) {
                            inBox++;
                        }
                    }
                    row.addProperty("nodesInBox", inBox);
                    rows.add(row);
                }
                json.add("pieces", rows);
                return json;
            }
            List<WireGraph.Wire> touching = graph.touching(box);
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
        });
        if (result instanceof String) {
            return ToolResult.error((String) result);
        }
        return ToolResult.structured((JsonObject) result);
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
