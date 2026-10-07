package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
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
import javax.annotation.Nullable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
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

    /** find: most tile entities whose NBT one reply carries; a modded one can be kilobytes. */
    private static final int MAX_NBT = 64;

    /**
     * find: how far from the player's chunk a chunk can be and still be held. The server's view
     * distance tops out at 32, and clipping the box to this window keeps a continent-sized box from
     * costing a lookup per chunk column it names.
     */
    private static final int CHUNK_WINDOW = 32;

    /** find: how long one game-thread hop may scan before handing the thread back for a frame. */
    private static final long HOP_NANOS = 8_000_000L;

    /** find: unloaded chunks are listed only when there are this few; otherwise just counted. */
    private static final int MAX_LISTED_UNLOADED = 64;

    private static final List<String> AIR = Collections.singletonList("minecraft:air");

    private static final List<String> MODES =
        Arrays.asList("summary", "positions", "find", "heightmap", "surface");

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
                + "capped at 'limit'. 'find': the same answer for a box of any size, for locating "
                + "rare blocks: it needs 'blocks' or 'tile_entities', searches only the loaded "
                + "chunks inside the box a few at a time so the game keeps running, and counts the "
                + "rest in chunksUnloaded; 'nbt' adds each listed match's tile-entity NBT. "
                + "'heightmap': the y of the highest matching block in every "
                + "column, as rows of z holding x from the low corner; -1 where there is none. "
                + "'surface': the same, plus which block is on top, as indices into a palette — "
                + "what is on the ground, not only how high. surface is limited by columns, not "
                + "volume, so it can cover the whole height range.\n\n"
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
                    "summary", "positions", "find", "heightmap", "surface")
                .stringArray("blocks", "Only count blocks matching one of these patterns.")
                .stringArray("exclude", "Leave out blocks matching any of these patterns.")
                .bool("tile_entities", "Only count blocks that have a tile entity on this client.")
                .bool("per_layer", "summary: also break the counts down by y. Default false.")
                .integer("limit", "positions, find: most positions returned; the counts stay exact. "
                    + "Default 256.", 1, MAX_POSITIONS)
                .bool("nbt", "find: also return the tile-entity NBT of the first " + MAX_NBT
                    + " listed matches, keyed \"x,y,z\".")
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
                if (!MODES.contains(mode)) {
                    return ToolResult.error("Unknown mode '" + mode + "'; use summary, positions, "
                        + "find, heightmap or surface.");
                }
                final BlockPatterns include = BlockPatterns.of(
                    Json.getStringList(context.getArguments(), "blocks"));
                final BlockPatterns exclude = BlockPatterns.of(context.has("exclude")
                    ? Json.getStringList(context.getArguments(), "exclude")
                    : "summary".equals(mode) ? Collections.<String>emptyList() : AIR);
                final boolean tileEntitiesOnly = context.getBoolean("tile_entities", false);
                final boolean perLayer = context.getBoolean("per_layer", false);
                final int limit = context.getBoundedInt("limit", 256, 1, MAX_POSITIONS);
                if ("find".equals(mode)) {
                    if (include.isEmpty() && !tileEntitiesOnly) {
                        return ToolResult.error("mode 'find' searches for something: pass 'blocks', "
                            + "'tile_entities': true, or both.");
                    }
                    return find(context, new int[]{x1, y1, z1, x2, y2, z2}, relative, include,
                        exclude, tileEntitiesOnly, limit, context.getBoolean("nbt", false));
                }

                final long maxVolume = (long) McmcpConfig.getMaxBlockVolume() * VOLUME_FACTOR;
                long volume = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1)
                    * (Math.min(255, Math.max(y1, y2)) - Math.max(0, Math.min(y1, y2)) + 1);
                // surface stops at the first match down each column, so its cost is set by the
                // column count below, and charging it for the whole height range would make the
                // one question it answers — what is on top of a district — need dozens of reads.
                if (volume > maxVolume && !"surface".equals(mode)) {
                    return ToolResult.error("That region is " + volume + " blocks; one read covers at "
                        + "most " + maxVolume + " (limits.maxBlockVolume x " + VOLUME_FACTOR + "). "
                        + ("positions".equals(mode)
                            ? "Split it, or use mode 'find', which takes a box of any size."
                            : "Split it."));
                }
                long columns = (long) (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1);
                if (("heightmap".equals(mode) || "surface".equals(mode)) && columns > MAX_COLUMNS) {
                    return ToolResult.error("A " + mode + " covers at most " + MAX_COLUMNS + " columns "
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
                        if ("heightmap".equals(mode) || "surface".equals(mode)) {
                            return survey.heightmap(region, "surface".equals(mode));
                        }
                        return survey.scan(region, mode, perLayer, limit);
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    /**
     * mode 'find': a search over a box of any size, answered in a size set by what it finds.
     *
     * <p>Searching a road corridor for one rare block (issue #48) took about 400 reads at the
     * per-read volume cap, each with a teleport, for an answer of a handful of positions. The cap
     * bounds how long a read holds the client thread; here that is bounded instead by splitting the
     * work into hops of a few milliseconds, between which the game renders a frame. Only loaded
     * chunks can be searched, and the client holds few of them, so the real cost is set by those,
     * not by the box: empty sections are skipped outright.
     */
    private static ToolResult find(ToolContext context, int[] corners, boolean relative,
        BlockPatterns include, BlockPatterns exclude, boolean tileEntitiesOnly, int limit,
        boolean nbt) {
        final FindPlan plan = context.onGameThread(() -> {
            Minecraft mc = ClientStateTools.requireInWorld();
            BlockPos origin = relative ? GameJson.blockPosOf(mc.player) : BlockPos.ORIGIN;
            Region region = new Region(
                origin.getX() + corners[0], origin.getY() + corners[1], origin.getZ() + corners[2],
                origin.getX() + corners[3], origin.getY() + corners[4], origin.getZ() + corners[5]);
            BlockPos player = GameJson.blockPosOf(mc.player);
            int[] window = chunkWindow(region.minX, region.maxX, region.minZ, region.maxZ,
                player.getX() >> 4, player.getZ() >> 4, CHUNK_WINDOW);
            List<long[]> loaded = new ArrayList<>();
            if (window != null) {
                for (int cx = window[0]; cx <= window[1]; cx++) {
                    for (int cz = window[2]; cz <= window[3]; cz++) {
                        if (GameJson.isLoaded(mc.world, new BlockPos(cx << 4, 0, cz << 4))) {
                            loaded.add(new long[]{cx, cz});
                        }
                    }
                }
            }
            long columns = chunkColumns(region.minX, region.maxX, region.minZ, region.maxZ);
            JsonArray unloaded = new JsonArray();
            // Listing the unloaded chunks is only worth it when there are few; then the box is small
            // enough to walk. A corridor's thousands of them are a count, not a list.
            if (columns - loaded.size() <= MAX_LISTED_UNLOADED) {
                for (int cx = region.minX >> 4; cx <= region.maxX >> 4; cx++) {
                    for (int cz = region.minZ >> 4; cz <= region.maxZ >> 4; cz++) {
                        if (!GameJson.isLoaded(mc.world, new BlockPos(cx << 4, 0, cz << 4))) {
                            JsonArray pair = new JsonArray();
                            pair.add(cx);
                            pair.add(cz);
                            unloaded.add(pair);
                        }
                    }
                }
            }
            return new FindPlan(region, loaded, columns, unloaded,
                System.identityHashCode(mc.world));
        });

        final Found found = new Found(limit, nbt ? Math.min(limit, MAX_NBT) : 0);
        int next = 0;
        while (next < plan.chunks.size()) {
            final int start = next;
            next = context.onGameThread(() -> {
                Minecraft mc = ClientStateTools.requireInWorld();
                if (System.identityHashCode(mc.world) != plan.worldIdentity) {
                    throw new IllegalStateException("The client changed world part way through the "
                        + "search; run it again.");
                }
                Survey survey = new Survey(mc.world, include, exclude, tileEntitiesOnly);
                long deadline = System.nanoTime() + HOP_NANOS;
                int i = start;
                // At least one chunk per hop, so a slow chunk cannot stall the search.
                while (i < plan.chunks.size() && (i == start || System.nanoTime() < deadline)) {
                    long[] chunk = plan.chunks.get(i++);
                    int cx = (int) chunk[0];
                    int cz = (int) chunk[1];
                    if (GameJson.isLoaded(mc.world, new BlockPos(cx << 4, 0, cz << 4))) {
                        survey.find(mc.world.getChunk(cx, cz), plan.region, found);
                        found.chunksSearched++;
                    }
                    else {
                        found.unloadedSince++;
                    }
                }
                return i;
            });
            context.reportProgress(next, plan.chunks.size(), "Searched " + next + " of "
                + plan.chunks.size() + " loaded chunks; " + found.matched + " found");
        }

        JsonObject json = plan.region.describe();
        json.addProperty("matched", found.matched);
        json.add("counts", Survey.sortedCounts(found.counts));
        JsonObject grouped = new JsonObject();
        for (Map.Entry<String, JsonArray> entry : found.positions.entrySet()) {
            grouped.add(entry.getKey(), entry.getValue());
        }
        json.add("positions", grouped);
        if (found.matched > found.listed) {
            json.addProperty("truncated", true);
            json.addProperty("listed", found.listed);
        }
        if (nbt) {
            json.add("blockEntities", found.blockEntities);
        }
        json.addProperty("chunksSearched", found.chunksSearched);
        long unloadedCount = plan.columns - plan.chunks.size() + found.unloadedSince;
        if (unloadedCount > 0) {
            json.addProperty("chunksUnloaded", unloadedCount);
            if (plan.unloaded.size() > 0 && found.unloadedSince == 0) {
                json.add("unloadedChunks", plan.unloaded);
            }
        }
        return ToolResult.structured(json);
    }

    /**
     * The chunk range of a block box, clipped to {@code radius} chunks around the player's chunk,
     * as {minChunkX, maxChunkX, minChunkZ, maxChunkZ}; null when the two do not overlap.
     */
    @Nullable
    static int[] chunkWindow(int minX, int maxX, int minZ, int maxZ, int playerChunkX,
        int playerChunkZ, int radius) {
        int minCX = Math.max(minX >> 4, playerChunkX - radius);
        int maxCX = Math.min(maxX >> 4, playerChunkX + radius);
        int minCZ = Math.max(minZ >> 4, playerChunkZ - radius);
        int maxCZ = Math.min(maxZ >> 4, playerChunkZ + radius);
        if (minCX > maxCX || minCZ > maxCZ) {
            return null;
        }
        return new int[]{minCX, maxCX, minCZ, maxCZ};
    }

    /** How many chunk columns a block box touches. */
    static long chunkColumns(int minX, int maxX, int minZ, int maxZ) {
        return ((long) (maxX >> 4) - (minX >> 4) + 1) * ((long) (maxZ >> 4) - (minZ >> 4) + 1);
    }

    /** What the first hop of a find learns: where to look. Plain values only. */
    private static final class FindPlan {

        final Region region;
        final List<long[]> chunks;
        final long columns;
        final JsonArray unloaded;
        final int worldIdentity;

        FindPlan(Region region, List<long[]> chunks, long columns, JsonArray unloaded,
            int worldIdentity) {
            this.region = region;
            this.chunks = chunks;
            this.columns = columns;
            this.unloaded = unloaded;
            this.worldIdentity = worldIdentity;
        }
    }

    /** A find's running answer, written on the client thread one hop at a time. */
    private static final class Found {

        final int limit;
        final int nbtLimit;
        final Map<String, int[]> counts = new LinkedHashMap<>();
        final Map<String, JsonArray> positions = new LinkedHashMap<>();
        final JsonObject blockEntities = new JsonObject();
        long matched;
        int listed;
        int nbtListed;
        int chunksSearched;
        int unloadedSince;

        Found(int limit, int nbtLimit) {
            this.limit = limit;
            this.nbtLimit = nbtLimit;
        }
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

        /** find's cache: the id of a state that passes the filters, or {@link #NO_MATCH}. */
        private final Map<IBlockState, String> verdicts = new IdentityHashMap<>();

        @SuppressWarnings("RedundantStringConstructorCall")
        private static final String NO_MATCH = new String("");
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

        /** The state's id if it passes the block filters, else null; once per state, not per block. */
        @Nullable
        private String matchingState(IBlockState state) {
            String verdict = verdicts.get(state);
            if (verdict == null) {
                ResourceLocation name = state.getBlock().getRegistryName();
                String id = name == null ? "unknown" : name.toString();
                int metadata = state.getBlock().getMetaFromState(state);
                if (metadata != 0) {
                    id = id + ":" + metadata;
                }
                boolean passes = (include.isEmpty() || include.matches(id)) && !exclude.matches(id);
                verdict = passes ? id : NO_MATCH;
                verdicts.put(state, verdict);
            }
            return verdict == NO_MATCH ? null : verdict;
        }

        /**
         * Adds one chunk's matches inside {@code region} to {@code found}, a section at a time. An
         * empty section is all air, and air is never what a find is looking for, so it is skipped
         * without reading a block.
         */
        void find(Chunk chunk, Region region, Found found) {
            int minX = Math.max(region.minX, chunk.x << 4);
            int maxX = Math.min(region.maxX, (chunk.x << 4) + 15);
            int minZ = Math.max(region.minZ, chunk.z << 4);
            int maxZ = Math.min(region.maxZ, (chunk.z << 4) + 15);
            ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int sectionY = region.minY >> 4; sectionY <= region.maxY >> 4; sectionY++) {
                ExtendedBlockStorage section = sections[sectionY];
                if (section == Chunk.NULL_BLOCK_STORAGE || section.isEmpty()) {
                    continue;
                }
                int minY = Math.max(region.minY, sectionY << 4);
                int maxY = Math.min(region.maxY, (sectionY << 4) + 15);
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int x = minX; x <= maxX; x++) {
                            String id = matchingState(section.get(x & 15, y & 15, z & 15));
                            if (id == null) {
                                continue;
                            }
                            pos.setPos(x, y, z);
                            if (tileEntitiesOnly
                                && chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK) == null) {
                                continue;
                            }
                            record(found, id, pos);
                        }
                    }
                }
            }
        }

        private void record(Found found, String id, BlockPos pos) {
            found.matched++;
            increment(found.counts, id);
            if (found.listed >= found.limit) {
                return;
            }
            JsonArray list = found.positions.get(id);
            if (list == null) {
                list = new JsonArray();
                found.positions.put(id, list);
            }
            JsonArray xyz = new JsonArray();
            xyz.add(pos.getX());
            xyz.add(pos.getY());
            xyz.add(pos.getZ());
            list.add(xyz);
            found.listed++;
            if (found.nbtListed < found.nbtLimit) {
                found.blockEntities.add(pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                    GameJson.blockEntity(world, pos.toImmutable(), false));
                found.nbtListed++;
            }
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

        /**
         * The highest matching block per column; with {@code withBlocks}, also which block it is,
         * as an index into a palette so a repeated id costs a few bytes rather than its full name.
         */
        JsonObject heightmap(Region region, boolean withBlocks) {
            Map<Long, Chunk> chunks = new LinkedHashMap<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            JsonArray rows = new JsonArray();
            JsonArray blockRows = new JsonArray();
            Map<String, Integer> paletteIndex = new LinkedHashMap<>();
            int highest = -1;
            int lowest = Integer.MAX_VALUE;

            for (int z = region.minZ; z <= region.maxZ; z++) {
                JsonArray row = new JsonArray();
                JsonArray blockRow = new JsonArray();
                for (int x = region.minX; x <= region.maxX; x++) {
                    Chunk chunk = chunkAt(x, z, chunks);
                    if (chunk == null) {
                        row.add(JsonNull.INSTANCE);
                        blockRow.add(JsonNull.INSTANCE);
                        continue;
                    }
                    int top = -1;
                    String topId = null;
                    for (int y = region.maxY; y >= region.minY; y--) {
                        pos.setPos(x, y, z);
                        String id = matching(chunk, pos);
                        if (id != null) {
                            top = y;
                            topId = id;
                            break;
                        }
                    }
                    row.add(top);
                    if (topId == null) {
                        blockRow.add(-1);
                    }
                    else {
                        Integer index = paletteIndex.get(topId);
                        if (index == null) {
                            index = paletteIndex.size();
                            paletteIndex.put(topId, index);
                        }
                        blockRow.add(index);
                    }
                    if (top >= 0) {
                        highest = Math.max(highest, top);
                        lowest = Math.min(lowest, top);
                    }
                }
                rows.add(row);
                blockRows.add(blockRow);
            }

            JsonObject json = region.describe();
            json.addProperty("layout", withBlocks
                ? "heights[z - from.z][x - from.x] and blocks[...] (an index into palette) for the "
                    + "same column; -1 = nothing matching, null = chunk not loaded"
                : "heights[z - from.z][x - from.x]; -1 = nothing matching, null = chunk not loaded");
            if (highest >= 0) {
                json.addProperty("highest", highest);
                json.addProperty("lowest", lowest);
            }
            if (withBlocks) {
                JsonArray palette = new JsonArray();
                for (String id : paletteIndex.keySet()) {
                    palette.add(id);
                }
                json.add("palette", palette);
                json.add("blocks", blockRows);
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
