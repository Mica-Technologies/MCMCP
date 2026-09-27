package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.BlockPatterns;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Reading a region of the world as the client holds it.
 *
 * <p>{@code server_get_blocks} needs MCMCP on the server, and on someone else's server it is not
 * there — the client's only view of the world was {@code client_get_block}, one position per call.
 * Surveying one city parcel before clearing it took about 22,000 of them (#30). The client already
 * holds every chunk inside its view distance; this reads them in one pass.
 *
 * <p>Built for questions, not for copying a region: how much of what is here ({@code summary}),
 * where exactly are the blocks I care about ({@code positions}), how tall is what stands here
 * ({@code heightmap}). Each answers in a size set by the answer rather than by the volume read.
 */
@SideOnly(Side.CLIENT)
public final class ClientSurveyTools {

    /**
     * How many times {@code limits.maxBlockVolume} one read may cover. That limit exists to bound how
     * long a server write holds the tick; a client read of chunks already in memory is about 50ns a
     * block, so eight times the default (64³) holds the client thread for a few milliseconds, and
     * the answer's size is bounded separately by the mode.
     */
    private static final int VOLUME_FACTOR = 8;

    /** Largest heightmap, in columns: 128 x 128, which is already a large answer. */
    private static final int MAX_COLUMNS = 16384;

    private static final int MAX_POSITIONS = 4096;

    private static final List<String> AIR = Collections.singletonList("minecraft:air");

    private ClientSurveyTools() {
    }

    public static void register() {
        registerGetBlocks();
    }

    private static void registerGetBlocks() {
        McpRegistry.registerTool(McpTool.named("client_get_blocks")
            .title("Survey a region")
            .description("Read a cuboid region as this client sees it, in one call, on any server. "
                + "Use this instead of many client_get_block calls to survey an area.\n\n"
                + "mode 'summary' (default): how many of each block, optionally per layer. "
                + "'positions': where each matching block is, as [x,y,z], grouped by block and "
                + "capped at 'limit'. 'heightmap': the y of the highest matching block in every "
                + "column, as rows of z holding x from the low corner; -1 where there is none.\n\n"
                + "Filter with 'blocks' and 'exclude'. A pattern is a whole id ('minecraft:wool' "
                + "matches every colour, 'minecraft:wool:14' one), a bare path ('barrier' in any "
                + "namespace), or use * for a substring ('*alarm*'). 'positions' and 'heightmap' "
                + "exclude air unless you pass 'exclude' yourself.\n\n"
                + "Only chunks inside the client's view distance can be read; the others are listed "
                + "in 'unloadedChunks' and left out, never reported as air.")
            .schema(JsonSchema.object()
                .integer("x", "X of one corner.")
                .integer("y", "Y of one corner, 0-255.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Defaults to x.")
                .integer("toY", "Y of the opposite corner. Defaults to y.")
                .integer("toZ", "Z of the opposite corner. Defaults to z.")
                .bool("relative", "Treat all six coordinates as offsets from the player's block "
                    + "position.")
                .enumeration("mode", "What to return. Defaults to 'summary'.",
                    "summary", "positions", "heightmap")
                .stringArray("blocks", "Only count blocks matching one of these patterns.")
                .stringArray("exclude", "Leave out blocks matching any of these patterns.")
                .bool("tile_entities", "Only count blocks that have a tile entity on this client.")
                .bool("per_layer", "summary: also break the counts down by y. Default false.")
                .integer("limit", "positions: most positions returned; the counts stay exact. "
                    + "Default 256.", 1, MAX_POSITIONS)
                .required("x", "y", "z")
                .build())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                final int x1 = context.requireInt("x");
                final int y1 = context.requireInt("y");
                final int z1 = context.requireInt("z");
                final int x2 = context.getInt("toX", x1);
                final int y2 = context.getInt("toY", y1);
                final int z2 = context.getInt("toZ", z1);
                final boolean relative = context.getBoolean("relative", false);
                final String mode = context.getString("mode", "summary");
                if (!Arrays.asList("summary", "positions", "heightmap").contains(mode)) {
                    return ToolResult.error("Unknown mode '" + mode + "'; use summary, positions or "
                        + "heightmap.");
                }
                final BlockPatterns include = BlockPatterns.of(
                    Json.getStringList(context.getArguments(), "blocks"));
                final BlockPatterns exclude = BlockPatterns.of(context.has("exclude")
                    ? Json.getStringList(context.getArguments(), "exclude")
                    : "summary".equals(mode) ? Collections.<String>emptyList() : AIR);
                final boolean tileEntitiesOnly = context.getBoolean("tile_entities", false);
                final boolean perLayer = context.getBoolean("per_layer", false);
                final int limit = context.getBoundedInt("limit", 256, 1, MAX_POSITIONS);

                final long maxVolume = (long) McmcpConfig.getMaxBlockVolume() * VOLUME_FACTOR;
                long volume = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1)
                    * (Math.min(255, Math.max(y1, y2)) - Math.max(0, Math.min(y1, y2)) + 1);
                if (volume > maxVolume) {
                    return ToolResult.error("That region is " + volume + " blocks; one read covers at "
                        + "most " + maxVolume + " (limits.maxBlockVolume x " + VOLUME_FACTOR + "). "
                        + "Split it.");
                }
                long columns = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
                if ("heightmap".equals(mode) && columns > MAX_COLUMNS) {
                    return ToolResult.error("A heightmap covers at most " + MAX_COLUMNS + " columns "
                        + "(128 x 128); that one is " + columns + ". Split it.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        BlockPos origin = relative ? GameJson.blockPosOf(mc.player) : BlockPos.ORIGIN;
                        Region region = new Region(
                            origin.getX() + x1, origin.getY() + y1, origin.getZ() + z1,
                            origin.getX() + x2, origin.getY() + y2, origin.getZ() + z2);
                        Survey survey = new Survey(mc.world, include, exclude, tileEntitiesOnly);
                        if ("heightmap".equals(mode)) {
                            return survey.heightmap(region);
                        }
                        return survey.scan(region, mode, perLayer, limit);
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static final class Region {

        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;

        Region(int x1, int y1, int z1, int x2, int y2, int z2) {
            minX = Math.min(x1, x2);
            minY = Math.max(0, Math.min(y1, y2));
            minZ = Math.min(z1, z2);
            maxX = Math.max(x1, x2);
            maxY = Math.min(255, Math.max(y1, y2));
            maxZ = Math.max(z1, z2);
        }

        JsonObject describe() {
            JsonObject json = new JsonObject();
            json.add("from", GameJson.blockPos(new BlockPos(minX, minY, minZ)));
            json.add("to", GameJson.blockPos(new BlockPos(maxX, maxY, maxZ)));
            return json;
        }
    }

    /** One read of one region. Client thread only; holds world references for that long. */
    private static final class Survey {

        private final WorldClient world;
        private final BlockPatterns include;
        private final BlockPatterns exclude;
        private final boolean tileEntitiesOnly;

        /** Building an id string per block is most of the cost of a read; there are few states. */
        private final Map<IBlockState, String> ids = new IdentityHashMap<>();
        private final JsonArray unloadedChunks = new JsonArray();

        Survey(WorldClient world, BlockPatterns include, BlockPatterns exclude,
            boolean tileEntitiesOnly) {
            this.world = world;
            this.include = include;
            this.exclude = exclude;
            this.tileEntitiesOnly = tileEntitiesOnly;
        }

        /** The loaded chunk holding column (x, z), or null — once per chunk, recording the misses. */
        private Chunk chunkAt(int x, int z, Map<Long, Chunk> seen) {
            long key = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xFFFFFFFFL);
            if (seen.containsKey(key)) {
                return seen.get(key);
            }
            Chunk chunk = GameJson.isLoaded(world, new BlockPos(x, 0, z))
                ? world.getChunk(x >> 4, z >> 4) : null;
            if (chunk == null) {
                JsonArray pair = new JsonArray();
                pair.add(x >> 4);
                pair.add(z >> 4);
                unloadedChunks.add(pair);
            }
            seen.put(key, chunk);
            return chunk;
        }

        /** The block's id if it passes the filters, else null. */
        private String matching(Chunk chunk, BlockPos pos) {
            IBlockState state = chunk.getBlockState(pos);
            String id = ids.get(state);
            if (id == null) {
                ResourceLocation name = state.getBlock().getRegistryName();
                id = name == null ? "unknown" : name.toString();
                int metadata = state.getBlock().getMetaFromState(state);
                if (metadata != 0) {
                    id = id + ":" + metadata;
                }
                ids.put(state, id);
            }
            if (!include.isEmpty() && !include.matches(id)) {
                return null;
            }
            if (exclude.matches(id)) {
                return null;
            }
            if (tileEntitiesOnly
                && chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK) == null) {
                return null;
            }
            return id;
        }

        JsonObject scan(Region region, String mode, boolean perLayer, int limit) {
            Map<Long, Chunk> chunks = new LinkedHashMap<>();
            Map<String, int[]> counts = new LinkedHashMap<>();
            Map<Integer, Map<String, int[]>> layers = new TreeMap<>();
            Map<String, JsonArray> positions = new LinkedHashMap<>();
            int listed = 0;
            long matched = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            for (int x = region.minX; x <= region.maxX; x++) {
                for (int z = region.minZ; z <= region.maxZ; z++) {
                    Chunk chunk = chunkAt(x, z, chunks);
                    if (chunk == null) {
                        continue;
                    }
                    for (int y = region.minY; y <= region.maxY; y++) {
                        pos.setPos(x, y, z);
                        String id = matching(chunk, pos);
                        if (id == null) {
                            continue;
                        }
                        matched++;
                        increment(counts, id);
                        if (perLayer) {
                            Map<String, int[]> layer = layers.get(y);
                            if (layer == null) {
                                layer = new LinkedHashMap<>();
                                layers.put(y, layer);
                            }
                            increment(layer, id);
                        }
                        if ("positions".equals(mode) && listed < limit) {
                            JsonArray list = positions.get(id);
                            if (list == null) {
                                list = new JsonArray();
                                positions.put(id, list);
                            }
                            JsonArray xyz = new JsonArray();
                            xyz.add(x);
                            xyz.add(y);
                            xyz.add(z);
                            list.add(xyz);
                            listed++;
                        }
                    }
                }
            }

            JsonObject json = region.describe();
            json.addProperty("matched", matched);
            json.add("counts", sortedCounts(counts));
            if (perLayer) {
                JsonArray layerArray = new JsonArray();
                for (Map.Entry<Integer, Map<String, int[]>> layer : layers.entrySet()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("y", layer.getKey());
                    entry.add("counts", sortedCounts(layer.getValue()));
                    layerArray.add(entry);
                }
                json.add("layers", layerArray);
            }
            if ("positions".equals(mode)) {
                JsonObject grouped = new JsonObject();
                for (Map.Entry<String, JsonArray> entry : positions.entrySet()) {
                    grouped.add(entry.getKey(), entry.getValue());
                }
                json.add("positions", grouped);
                if (matched > listed) {
                    json.addProperty("truncated", true);
                    json.addProperty("listed", listed);
                }
            }
            if (unloadedChunks.size() > 0) {
                json.add("unloadedChunks", unloadedChunks);
            }
            return json;
        }

        JsonObject heightmap(Region region) {
            Map<Long, Chunk> chunks = new LinkedHashMap<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            JsonArray rows = new JsonArray();
            int highest = -1;
            int lowest = Integer.MAX_VALUE;

            for (int z = region.minZ; z <= region.maxZ; z++) {
                JsonArray row = new JsonArray();
                for (int x = region.minX; x <= region.maxX; x++) {
                    Chunk chunk = chunkAt(x, z, chunks);
                    if (chunk == null) {
                        row.add(JsonNull.INSTANCE);
                        continue;
                    }
                    int top = -1;
                    for (int y = region.maxY; y >= region.minY; y--) {
                        pos.setPos(x, y, z);
                        if (matching(chunk, pos) != null) {
                            top = y;
                            break;
                        }
                    }
                    row.add(top);
                    if (top >= 0) {
                        highest = Math.max(highest, top);
                        lowest = Math.min(lowest, top);
                    }
                }
                rows.add(row);
            }

            JsonObject json = region.describe();
            json.addProperty("layout", "heights[z - from.z][x - from.x]; -1 = nothing matching, "
                + "null = chunk not loaded");
            if (highest >= 0) {
                json.addProperty("highest", highest);
                json.addProperty("lowest", lowest);
            }
            json.add("heights", rows);
            if (unloadedChunks.size() > 0) {
                json.add("unloadedChunks", unloadedChunks);
            }
            return json;
        }

        private static void increment(Map<String, int[]> counts, String id) {
            int[] count = counts.get(id);
            if (count == null) {
                counts.put(id, new int[]{1});
            } else {
                count[0]++;
            }
        }

        /** Most common first, so a truncated reading of the answer still sees what dominates. */
        private static JsonObject sortedCounts(Map<String, int[]> counts) {
            List<Map.Entry<String, int[]>> entries = new ArrayList<>(counts.entrySet());
            Collections.sort(entries, (a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            JsonObject json = new JsonObject();
            for (Map.Entry<String, int[]> entry : entries) {
                json.addProperty(entry.getKey(), entry.getValue()[0]);
            }
            return json;
        }
    }
}
