package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.ChangeRing;
import com.micatechnologies.minecraft.mcmcp.regions.ChangeTracker;
import com.micatechnologies.minecraft.mcmcp.regions.PackedIndices;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Region reads sized for whole districts, and change tracking (#56).
 *
 * <p>{@code server_get_blocks} with {@code encoding: "packed"} or {@code mode: "surface"} lands here.
 * Both return a palette and a packed index array ({@link PackedIndices}), have no listing cap, read in
 * game-thread batches so a large read never holds a tick, and carry the {@code chunks} block.
 */
public final class ServerRegionTools {

    /** Palette entry for a cell in a chunk that is not loaded. Never air. */
    static final String UNLOADED = "mcmcp:unloaded";

    /** Columns read per game-thread task in surface mode. */
    private static final int SURFACE_COLUMNS_PER_TASK = 4096;

    private ServerRegionTools() {
    }

    public static void register() {
        registerChangesSince();
    }

    /** Whether {@code server_get_blocks} should hand this call over. */
    static boolean handles(ToolContext context) {
        return "surface".equals(context.getString("mode", "blocks"))
            || "packed".equals(context.getString("encoding", "json"));
    }

    /** {@code server_get_blocks} in packed or surface form. */
    static ToolResult read(final ToolContext context) throws Exception {
        final int x1 = context.requireInt("x");
        final int y1 = context.requireInt("y");
        final int z1 = context.requireInt("z");
        final int minX = Math.min(x1, context.getInt("toX", x1));
        final int maxX = Math.max(x1, context.getInt("toX", x1));
        final int minZ = Math.min(z1, context.getInt("toZ", z1));
        final int maxZ = Math.max(z1, context.getInt("toZ", z1));
        final int minY = Math.max(0, Math.min(y1, context.getInt("toY", y1)));
        final int maxY = Math.min(255, Math.max(y1, context.getInt("toY", y1)));
        final int dimension = context.getInt("dimension", 0);
        final int sizeX = maxX - minX + 1;
        final int sizeZ = maxZ - minZ + 1;
        final int sizeY = maxY - minY + 1;
        final boolean surface = "surface".equals(context.getString("mode", "blocks"));
        final Set<Long> chunks = ChunkKeys.box(minX, minZ, maxX, maxZ);

        long cells = surface ? (long) sizeX * sizeZ : (long) sizeX * sizeZ * sizeY;
        long cap = surface ? McmcpConfig.getMaxSurfaceColumns() : McmcpConfig.getMaxPackedReadCells();
        if (cells > cap) {
            return ToolResult.error("That is " + cells + (surface ? " columns" : " cells") + ", over the "
                + cap + " one call reads (limits." + (surface ? "maxSurfaceColumns" : "maxPackedReadCells")
                + "). Read it in tiles.");
        }

        final Set<Block> ignore = new HashSet<>();
        if (surface) {
            List<String> ignored = Json.getStringList(context.getArguments(), "ignore");
            if (ignored.isEmpty()) {
                ignored = java.util.Collections.singletonList("minecraft:air");
            }
            for (String id : ignored) {
                Block block = BlockIds.resolve(id);
                if (block == null) {
                    return ToolResult.error(BlockIds.describeUnknown(id, "ignore"));
                }
                ignore.add(block);
            }
            ignore.add(net.minecraft.init.Blocks.AIR);
        }

        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final int[] states = new int[(int) cells];
            final int[] heights = surface ? new int[(int) cells] : null;
            if (surface) {
                for (int from = 0; from < cells; from += SURFACE_COLUMNS_PER_TASK) {
                    final int start = from;
                    final int end = (int) Math.min(cells, from + SURFACE_COLUMNS_PER_TASK);
                    context.onGameThread(() -> {
                        readSurface(ServerWorldTools.requireWorld(dimension), minX, minZ, sizeX, minY, maxY, ignore,
                            start, end, states, heights);
                        return null;
                    });
                    context.reportProgress(end, cells, "Read " + end + " of " + cells + " columns");
                }
            }
            else {
                int layer = sizeX * sizeZ;
                int layersPerTask = Math.max(1, McmcpConfig.getMaxBlockVolume() / Math.max(1, layer));
                for (int y = minY; y <= maxY; y += layersPerTask) {
                    final int fromY = y;
                    final int toY = Math.min(maxY, y + layersPerTask - 1);
                    context.onGameThread(() -> {
                        readCells(ServerWorldTools.requireWorld(dimension), minX, minZ, sizeX, sizeZ, minY, fromY, toY,
                            states);
                        return null;
                    });
                    if (sizeY > layersPerTask) {
                        context.reportProgress(toY - minY + 1, sizeY, "Read " + (toY - minY + 1) + " of " + sizeY + " layers");
                    }
                }
            }

            // Off the game thread: the palette. State ids resolve through the block registry, which is
            // fixed once the server is running.
            Map<Integer, Integer> paletteIndex = new LinkedHashMap<>();
            Map<Integer, int[]> counts = new LinkedHashMap<>();
            int[] indices = new int[states.length];
            for (int i = 0; i < states.length; i++) {
                Integer index = paletteIndex.get(states[i]);
                if (index == null) {
                    index = paletteIndex.size();
                    paletteIndex.put(states[i], index);
                    counts.put(states[i], new int[1]);
                }
                counts.get(states[i])[0]++;
                indices[i] = index;
            }
            JsonArray palette = new JsonArray();
            for (Map.Entry<Integer, Integer> entry : paletteIndex.entrySet()) {
                JsonObject item = new JsonObject();
                item.addProperty("index", entry.getValue());
                item.addProperty("block", describe(entry.getKey()));
                item.addProperty("count", counts.get(entry.getKey())[0]);
                palette.add(item);
            }

            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("mode", surface ? "surface" : "blocks");
            JsonObject origin = new JsonObject();
            origin.addProperty("x", minX);
            origin.addProperty("y", minY);
            origin.addProperty("z", minZ);
            json.add("origin", origin);
            json.addProperty("sizeX", sizeX);
            json.addProperty("sizeZ", sizeZ);
            if (!surface) {
                json.addProperty("sizeY", sizeY);
            }
            json.add("palette", palette);
            json.addProperty("encoding", PackedIndices.ENCODING);
            json.addProperty("count", states.length);
            if (surface) {
                json.addProperty("indexOrder", "index = dx + sizeX * dz; one entry per column");
                json.addProperty("indices", PackedIndices.encode(indices));
                json.addProperty("heights", PackedIndices.encode(heights));
                json.addProperty("heightsNote", "the y of each column's top block; -1 when the column has none "
                    + "in range, -2 when its chunk is not loaded");
            }
            else {
                json.addProperty("indexOrder", "index = dx + sizeX * (dz + sizeZ * dy), relative to origin");
                json.addProperty("indices", PackedIndices.encode(indices));
            }
            json.add("chunks", context.onGameThread(() -> {
                ChunkLoadGovernor.touch(dimension, chunks);
                return outcome != null ? outcome.toJson()
                    : ChunkLoadGovernor.describeUnloaded(ServerWorldTools.requireWorld(dimension), chunks);
            }));
            return ToolResult.structured(json);
        });
    }

    /** Game thread. Unloaded chunks read as -1, never as air, and are never loaded. */
    private static void readCells(WorldServer world, int minX, int minZ, int sizeX, int sizeZ, int minY, int fromY,
                                  int toY, int[] states) {
        ChunkProviderServer provider = world.getChunkProvider();
        Chunk cached = null;
        for (int y = fromY; y <= toY; y++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int dx = 0; dx < sizeX; dx++) {
                    int x = minX + dx;
                    int z = minZ + dz;
                    if (cached == null || cached.x != x >> 4 || cached.z != z >> 4) {
                        cached = provider.getLoadedChunk(x >> 4, z >> 4);
                    }
                    int index = dx + sizeX * (dz + sizeZ * (y - minY));
                    states[index] = cached == null ? -1 : Block.getStateId(cached.getBlockState(x, y, z));
                }
            }
        }
    }

    /** Game thread. Each column's top block below maxY that is not ignored. */
    private static void readSurface(WorldServer world, int minX, int minZ, int sizeX, int minY, int maxY,
                                    Set<Block> ignore, int start, int end, int[] states, int[] heights) {
        ChunkProviderServer provider = world.getChunkProvider();
        for (int i = start; i < end; i++) {
            int x = minX + i % sizeX;
            int z = minZ + i / sizeX;
            Chunk chunk = provider.getLoadedChunk(x >> 4, z >> 4);
            if (chunk == null) {
                states[i] = -1;
                heights[i] = -2;
                continue;
            }
            int top = Math.min(maxY, chunk.getTopFilledSegment() + 15);
            states[i] = Block.getStateId(net.minecraft.init.Blocks.AIR.getDefaultState());
            heights[i] = -1;
            for (int y = top; y >= minY; y--) {
                IBlockState state = chunk.getBlockState(x & 15, y, z & 15);
                if (!ignore.contains(state.getBlock())) {
                    states[i] = Block.getStateId(state);
                    heights[i] = y;
                    break;
                }
            }
        }
    }

    /** A state id as a palette name: {@code mod:block} or {@code mod:block:meta}. */
    static String describe(int stateId) {
        if (stateId < 0) {
            return UNLOADED;
        }
        IBlockState state = Block.getStateById(stateId);
        ResourceLocation name = state.getBlock().getRegistryName();
        String id = name == null ? "unknown" : name.toString();
        int meta = state.getBlock().getMetaFromState(state);
        return meta == 0 ? id : id + ":" + meta;
    }

    // ------------------------------------------------------------------
    // Change tracking
    // ------------------------------------------------------------------

    private static void registerChangesSince() {
        McpRegistry.registerTool(McpTool.named("server_changes_since")
            .title("What changed in a region")
            .description("Block changes inside a box since a token, so you can find exactly which cells "
                + "someone else changed since you last read them, instead of re-reading everything. Call "
                + "without 'since' to get a token for now; keep the 'token' each call returns and pass it next "
                + "time.\n\n"
                + "Each change is [x, y, z, paletteIndex, sourceIndex]: the block it became, and who made it — "
                + "'player:<name>' for a player placing or breaking, 'other' for anything else (mobs, "
                + "pistons, mods). MCMCP's own writes are not listed. 'overflow' true means the record no longer "
                + "reaches back to your token (limits.changeLogEntries): re-read the region. 'truncated' true "
                + "means there were more than 'limit'; ask again from the returned token. Writes a mod makes "
                + "without telling clients, and terrain generation, are not seen.")
            .schema(JsonSchema.object()
                .integer("x", "X of one corner.")
                .integer("y", "Y of one corner. Defaults to 0.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Defaults to x.")
                .integer("toY", "Y of the opposite corner. Defaults to 255.")
                .integer("toZ", "Z of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .string("since", "The token from your last call. Omit to get a token for now.")
                .integer("limit", "Most changes to return. Default 5000.", 1, 20000)
                .required("x", "z")
                .build())
            .serverOnly()
            .readOnly()
            .offGameThread()
            .handler(context -> {
                ChangeRing ring = ChangeTracker.ring();
                if (ring == null) {
                    return ToolResult.error("Change tracking starts with the server; no server is running.");
                }
                if (!context.has("since")) {
                    JsonObject json = new JsonObject();
                    json.addProperty("token", String.valueOf(ring.token()));
                    json.addProperty("note", "Pass this as 'since' next time to get what changed after now.");
                    return ToolResult.structured(json);
                }
                long since;
                try {
                    since = Long.parseLong(context.getString("since", "0").trim());
                }
                catch (NumberFormatException e) {
                    return ToolResult.error("'since' is a token from an earlier call, a whole number.");
                }
                int x1 = context.requireInt("x");
                int z1 = context.requireInt("z");
                int x2 = context.getInt("toX", x1);
                int z2 = context.getInt("toZ", z1);
                int y1 = context.getInt("y", 0);
                int y2 = context.getInt("toY", 255);
                ChangeRing.Result result = ring.since(since, context.getInt("dimension", 0), Math.min(x1, x2),
                    Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                    context.getBoundedInt("limit", 5000, 1, 20000), null);

                Map<Integer, Integer> palette = new LinkedHashMap<>();
                Map<String, Integer> sources = new LinkedHashMap<>();
                JsonArray changes = new JsonArray();
                for (ChangeRing.Change change : result.changes) {
                    Integer block = palette.computeIfAbsent(change.stateId, key -> palette.size());
                    Integer source = sources.computeIfAbsent(change.source, key -> sources.size());
                    JsonArray row = new JsonArray();
                    row.add(change.x);
                    row.add(change.y);
                    row.add(change.z);
                    row.add(block);
                    row.add(source);
                    changes.add(row);
                }
                JsonArray paletteJson = new JsonArray();
                for (Integer stateId : palette.keySet()) {
                    paletteJson.add(describe(stateId));
                }
                JsonObject json = new JsonObject();
                json.addProperty("token", String.valueOf(result.token));
                json.addProperty("overflow", result.overflow);
                json.addProperty("truncated", result.truncated);
                json.addProperty("count", result.changes.size());
                json.add("palette", paletteJson);
                json.add("sources", Json.arrayOfStrings(new java.util.ArrayList<>(sources.keySet())));
                json.add("changes", changes);
                return ToolResult.structured(json);
            })
            .build());
    }
}
