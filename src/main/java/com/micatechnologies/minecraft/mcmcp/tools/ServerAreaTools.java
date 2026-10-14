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
import com.micatechnologies.minecraft.mcmcp.regions.NbtProjection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Whole-area work done on the server, chunk by chunk: counting what is in a box (#79) and recomputing
 * its light (#75). Both used to mean pulling every cell down to a script, or could not be done at all.
 *
 * <p>Each runs in game-thread slices of about {@link #SLICE_NANOS}, so a district-sized box costs many
 * short stalls with ticks between them rather than one long one.
 */
public final class ServerAreaTools {

    /** How long one game-thread slice may run. */
    private static final long SLICE_NANOS = 25_000_000L;

    /** The largest box either tool takes, in chunks: about 1,000 x 1,000 blocks. */
    private static final int MAX_CHUNKS = 4096;

    /** The most cells one relight takes; light is far dearer per cell than counting. */
    private static final long MAX_RELIGHT_CELLS = 4_194_304L;

    private ServerAreaTools() {
    }

    public static void register() {
        registerCount();
        registerRelight();
    }

    /** A box from the usual x, y, z, toX, toY, toZ arguments, y clamped to the world. */
    private static final class Box {
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;

        Box(ToolContext context, int defaultMinY, int defaultMaxY) {
            int x = context.requireInt("x");
            int z = context.requireInt("z");
            int y = context.getInt("y", defaultMinY);
            int toY = context.getInt("toY", context.has("y") ? y : defaultMaxY);
            minX = Math.min(x, context.getInt("toX", x));
            maxX = Math.max(x, context.getInt("toX", x));
            minZ = Math.min(z, context.getInt("toZ", z));
            maxZ = Math.max(z, context.getInt("toZ", z));
            minY = Math.max(0, Math.min(y, toY));
            maxY = Math.min(255, Math.max(y, toY));
        }

        long cells() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        Set<Long> chunks() {
            return ChunkKeys.box(minX, minZ, maxX, maxZ);
        }
    }

    private static JsonSchema boxSchema(JsonSchema schema) {
        return schema
            .integer("x", "X of one corner.")
            .integer("y", "Y of one corner. Defaults to 0.")
            .integer("z", "Z of one corner.")
            .integer("toX", "X of the opposite corner. Defaults to x.")
            .integer("toY", "Y of the opposite corner. Defaults to 255, or y if y is given.")
            .integer("toZ", "Z of the opposite corner. Defaults to z.")
            .integer("dimension", "Dimension id. Defaults to 0.")
            .bool("load", "Load the box's unloaded chunks for this call, and release them afterwards.");
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    private static void registerCount() {
        McpRegistry.registerTool(McpTool.named("server_count_blocks")
            .title("Count blocks in a region")
            .description("Count the blocks in a box by type, on the server, without listing them: 'how many "
                + "X are here' or 'is anything but terrain in this footprint' over a whole district in one "
                + "call. 'include' and 'exclude' take ids, namespaces ending in ':' or prefixes ending in '*'. "
                + "Returns counts per block id, most first, with a few sample positions of each. Air is left "
                + "out unless includeAir. Unloaded chunks are listed and not counted; pass load: true to count "
                + "them.")
            .schema(boxSchema(JsonSchema.object())
                .array("include", "Only count these blocks.", Json.obj("type", "string"))
                .array("exclude", "Never count these blocks, e.g. the natural ones.", Json.obj("type", "string"))
                .bool("includeAir", "Count air too. Default false.")
                .bool("byState", "Count each metadata value apart ('minecraft:wool:14'). Default false.")
                .integer("samples", "Sample positions listed per block. Default 3.", 0, 20)
                .integer("limit", "Block types listed, most first. Default 100.", 1, 1000)
                .required("x", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(ServerAreaTools::count)
            .build());
    }

    private static ToolResult count(final ToolContext context) throws Exception {
        final Box box = new Box(context, 0, 255);
        final Set<Long> chunks = box.chunks();
        if (chunks.size() > MAX_CHUNKS) {
            return ToolResult.error("That box covers " + chunks.size() + " chunks; count at most " + MAX_CHUNKS
                + " (about 1000 x 1000 blocks) at once.");
        }
        final int dimension = context.getInt("dimension", 0);
        final List<String> include = Json.getStringList(context.getArguments(), "include");
        final List<String> exclude = Json.getStringList(context.getArguments(), "exclude");
        final boolean includeAir = context.getBoolean("includeAir", false);
        final boolean byState = context.getBoolean("byState", false);
        final int samples = context.getBoundedInt("samples", 3, 0, 20);
        final int limit = context.getBoundedInt("limit", 100, 1, 1000);

        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            // State id -> whether it is counted, worked out once per state rather than once per cell.
            final Map<Integer, String> keys = new HashMap<>();
            final Map<String, long[]> counts = new HashMap<>();
            final Map<String, JsonArray> sampled = new HashMap<>();
            final long[] counted = {0L};
            final List<Long> pending = new ArrayList<>(chunks);
            int done = 0;
            while (done < pending.size()) {
                final int from = done;
                done = context.onGameThread(() -> {
                    WorldServer world = ServerWorldTools.requireWorld(dimension);
                    long started = System.nanoTime();
                    int at = from;
                    while (at < pending.size() && (at == from || System.nanoTime() - started < SLICE_NANOS)) {
                        long key = pending.get(at++);
                        Chunk chunk = world.getChunkProvider().getLoadedChunk(ChunkKeys.x(key), ChunkKeys.z(key));
                        if (chunk != null) {
                            countChunk(chunk, box, keys, include, exclude, includeAir, byState, counts, sampled,
                                samples, counted);
                        }
                    }
                    return at;
                });
                if (pending.size() > 64) {
                    context.reportProgress(done, pending.size(), "Counted " + done + " of " + pending.size() + " chunks");
                }
            }
            List<Map.Entry<String, long[]>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            JsonArray blocks = new JsonArray();
            for (int i = 0; i < sorted.size() && i < limit; i++) {
                JsonObject entry = new JsonObject();
                entry.addProperty("block", sorted.get(i).getKey());
                entry.addProperty("count", sorted.get(i).getValue()[0]);
                JsonArray positions = sampled.get(sorted.get(i).getKey());
                if (positions != null && positions.size() > 0) {
                    entry.add("samples", positions);
                }
                blocks.add(entry);
            }
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("cells", box.cells());
            json.addProperty("counted", counted[0]);
            json.addProperty("types", counts.size());
            if (sorted.size() > limit) {
                json.addProperty("typesNotListed", sorted.size() - limit);
            }
            json.add("blocks", blocks);
            json.add("chunks", context.onGameThread(() -> {
                ChunkLoadGovernor.touch(dimension, chunks);
                return outcome != null ? outcome.toJson()
                    : ChunkLoadGovernor.describeUnloaded(ServerWorldTools.requireWorld(dimension), chunks);
            }));
            return ToolResult.structured(json);
        });
    }

    /** Game thread: one chunk's share of the box, read straight from its sections. */
    private static void countChunk(Chunk chunk, Box box, Map<Integer, String> keys, List<String> include,
                                   List<String> exclude, boolean includeAir, boolean byState,
                                   Map<String, long[]> counts, Map<String, JsonArray> sampled, int samples,
                                   long[] counted) {
        int baseX = chunk.x << 4;
        int baseZ = chunk.z << 4;
        int fromX = Math.max(box.minX, baseX) - baseX;
        int toX = Math.min(box.maxX, baseX + 15) - baseX;
        int fromZ = Math.max(box.minZ, baseZ) - baseZ;
        int toZ = Math.min(box.maxZ, baseZ + 15) - baseZ;
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        for (int y = box.minY; y <= box.maxY; y++) {
            ExtendedBlockStorage section = sections[y >> 4];
            if (section == Chunk.NULL_BLOCK_STORAGE || section.isEmpty()) {
                if (!includeAir) {
                    // An empty section is all air: skip to the next one.
                    y = ((y >> 4) << 4) + 15;
                    continue;
                }
            }
            for (int z = fromZ; z <= toZ; z++) {
                for (int x = fromX; x <= toX; x++) {
                    IBlockState state = section == Chunk.NULL_BLOCK_STORAGE
                        ? Blocks.AIR.getDefaultState() : section.get(x, y & 15, z);
                    int stateId = Block.getStateId(state);
                    String key;
                    if (keys.containsKey(stateId)) {
                        key = keys.get(stateId);
                    }
                    else {
                        key = keyFor(state, include, exclude, includeAir, byState);
                        keys.put(stateId, key);
                    }
                    if (key == null) {
                        continue;
                    }
                    counted[0]++;
                    long[] count = counts.get(key);
                    if (count == null) {
                        count = new long[1];
                        counts.put(key, count);
                    }
                    count[0]++;
                    if (samples > 0) {
                        JsonArray positions = sampled.get(key);
                        if (positions == null) {
                            positions = new JsonArray();
                            sampled.put(key, positions);
                        }
                        if (positions.size() < samples) {
                            JsonArray pos = new JsonArray();
                            pos.add(baseX + x);
                            pos.add(y);
                            pos.add(baseZ + z);
                            positions.add(pos);
                        }
                    }
                }
            }
        }
    }

    /** What a state is counted as, or null if it is not counted at all. */
    private static String keyFor(IBlockState state, List<String> include, List<String> exclude, boolean includeAir,
                                 boolean byState) {
        Block block = state.getBlock();
        if (!includeAir && block == Blocks.AIR) {
            return null;
        }
        ResourceLocation name = block.getRegistryName();
        String id = name == null ? "unknown" : name.toString();
        if (!include.isEmpty() && !NbtProjection.matches(id, include)) {
            return null;
        }
        if (!exclude.isEmpty() && NbtProjection.matches(id, exclude)) {
            return null;
        }
        return byState ? id + ":" + block.getMetaFromState(state) : id;
    }

    // ------------------------------------------------------------------
    // Relighting
    // ------------------------------------------------------------------

    private static void registerRelight() {
        McpRegistry.registerTool(McpTool.named("server_relight")
            .title("Recompute light in a region")
            .description("Recompute block light and sky light over a box, then resend its chunks to players. "
                + "Use after bulk writes without neighbour updates, which leave stale dark patches on new "
                + "facades and plazas until something else relights them. Changes no blocks. At most "
                + MAX_RELIGHT_CELLS + " cells a call; light is dear, so keep the box to what was written.")
            .schema(boxSchema(JsonSchema.object())
                .required("x", "z")
                .build())
            .serverOnly()
            .idempotent()
            .handler(ServerAreaTools::relight)
            .build());
    }

    private static ToolResult relight(final ToolContext context) throws Exception {
        final Box box = new Box(context, 0, 255);
        if (box.cells() > MAX_RELIGHT_CELLS) {
            return ToolResult.error("That box is " + box.cells() + " cells; relight at most " + MAX_RELIGHT_CELLS
                + " at once. Limit y to the height that was built, or relight in pieces.");
        }
        final int dimension = context.getInt("dimension", 0);
        final Set<Long> chunks = box.chunks();
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final List<Long> pending = new ArrayList<>(chunks);
            final long[] totals = {0L, 0L, 0L};  // cells checked, cells whose light changed, chunks resent
            long startedAt = System.nanoTime();
            // The cursor: which chunk, and how far through it.
            final int[] cursor = {0, box.minY};
            while (cursor[0] < pending.size()) {
                context.onGameThread(() -> {
                    WorldServer world = ServerWorldTools.requireWorld(dimension);
                    long started = System.nanoTime();
                    while (cursor[0] < pending.size() && System.nanoTime() - started < SLICE_NANOS) {
                        long key = pending.get(cursor[0]);
                        Chunk chunk = world.getChunkProvider().getLoadedChunk(ChunkKeys.x(key), ChunkKeys.z(key));
                        if (chunk == null) {
                            cursor[0]++;
                            cursor[1] = box.minY;
                            continue;
                        }
                        relightLayer(world, chunk, box, cursor[1], totals);
                        if (++cursor[1] > box.maxY) {
                            chunk.markDirty();
                            resend(world, chunk);
                            totals[2]++;
                            cursor[0]++;
                            cursor[1] = box.minY;
                        }
                    }
                    return null;
                });
                if (pending.size() > 4) {
                    context.reportProgress(cursor[0], pending.size(), "Relit " + cursor[0] + " of " + pending.size()
                        + " chunks");
                }
            }
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("cells", totals[0]);
            json.addProperty("lightChanged", totals[1]);
            json.addProperty("chunksResent", totals[2]);
            json.addProperty("millis", (System.nanoTime() - startedAt) / 1_000_000L);
            json.add("chunks", context.onGameThread(() -> {
                ChunkLoadGovernor.touch(dimension, chunks);
                return outcome != null ? outcome.toJson()
                    : ChunkLoadGovernor.describeUnloaded(ServerWorldTools.requireWorld(dimension), chunks);
            }));
            return ToolResult.structured(json);
        });
    }

    /** Game thread: relights one y layer of one chunk's share of the box. */
    private static void relightLayer(WorldServer world, Chunk chunk, Box box, int y, long[] totals) {
        int baseX = chunk.x << 4;
        int baseZ = chunk.z << 4;
        int fromX = Math.max(box.minX, baseX);
        int toX = Math.min(box.maxX, baseX + 15);
        int fromZ = Math.max(box.minZ, baseZ);
        int toZ = Math.min(box.maxZ, baseZ + 15);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = fromZ; z <= toZ; z++) {
            for (int x = fromX; x <= toX; x++) {
                pos.setPos(x, y, z);
                int before = world.getLightFromNeighbors(pos);
                world.checkLight(pos.toImmutable());
                totals[0]++;
                if (world.getLightFromNeighbors(pos) != before) {
                    totals[1]++;
                }
            }
        }
    }

    /** Sends a chunk to the players who have it, light included, as a fresh chunk does. */
    private static void resend(WorldServer world, Chunk chunk) {
        PlayerChunkMapEntry entry = world.getPlayerChunkMap().getEntry(chunk.x, chunk.z);
        if (entry != null && entry.isSentToPlayers()) {
            entry.sendPacket(new SPacketChunkData(chunk, 65535));
        }
    }
}
