package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.NbtGroups;
import com.micatechnologies.minecraft.mcmcp.regions.NbtProjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * Every tile entity in a box, in one call (#58): checking a district's signal controllers or alarm
 * devices used to be one teleport and one read per block, and the client's copy of a tile entity can
 * lag the server's by minutes.
 */
public final class ServerTileEntityTools {

    /** Characters of NBT one entity may contribute by default; larger ones show a summary instead. */
    private static final int DEFAULT_ENTITY_CHARS = 8192;

    /** The most a caller may raise that to: a CSM signal controller is 11-15k. */
    private static final int MAX_ENTITY_CHARS = 65536;

    /** Positions one call may name. */
    private static final int MAX_POSITIONS = 1000;

    /** Tile entities one {@code groupBy} call reads; each costs a tag serialised on the server thread. */
    static final int MAX_GROUP_CANDIDATES = 20_000;

    /** About how long one game-thread slice of a {@code groupBy} read runs, as in {@code ServerAreaTools}. */
    private static final long SLICE_NANOS = 25_000_000L;

    /** A long read says how far it is this often, so the orchestrator's no-progress limit does not end it. */
    private static final long REPORT_NANOS = 2_000_000_000L;

    private ServerTileEntityTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_get_tile_entities")
            .title("Read tile entities in a region")
            .description("Every tile entity in a box with its server-side NBT, in one call: the "
                + "authoritative state, not the client's copy. Filter by block ('csm:controller', or a "
                + "namespace 'csm:' or prefix 'csm:signal_*') and keep only the NBT fields you need with "
                + "'keys' (dot paths, e.g. 'tcCp' or 'Items.id'; index a list with 'tcPh.ph[0]'). Or name "
                + "exact blocks with 'positions' instead of a box. A tag over maxChars comes back as a "
                + "summary of its keys and sizes; summary: true asks for that up front. Paged: when 'next' "
                + "is present, call again with cursor = next. Unloaded chunks are listed in the 'chunks' "
                + "block; pass load: true to read them. groupBy: [keys] returns groups of identical values "
                + "with a count and sample positions instead of every entity.")
            .schema(JsonSchema.object()
                .integer("x", "X of one corner.")
                .integer("y", "Y of one corner. Defaults to 0.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Defaults to x.")
                .integer("toY", "Y of the opposite corner. Defaults to 255.")
                .integer("toZ", "Z of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .array("blocks", "Only these blocks: ids, namespaces ending in ':', or patterns with '*' anywhere.",
                    Json.obj("type", "string"))
                .array("positions", "Exact blocks to read, instead of a box: [{x, y, z}]. At most "
                    + MAX_POSITIONS + ". A position with no tile entity says so.",
                    JsonSchema.object().integer("x", "X.").integer("y", "Y.").integer("z", "Z.").build())
                .array("keys", "Only these NBT fields, as dot paths; '[n]' or '.n' picks one list element. "
                    + "Omit for the whole tag.", Json.obj("type", "string"))
                .integer("maxChars", "Largest tag, after 'keys', to return in full; larger ones come back as "
                    + "a summary. Default " + DEFAULT_ENTITY_CHARS + ".", 256, MAX_ENTITY_CHARS)
                .bool("summary", "Return each tag's keys and their sizes in characters instead of the "
                    + "values, to see what to ask for with 'keys'.")
                .integer("limit", "Entities per page. Default 200.", 1, 2000)
                .integer("cursor", "Where to continue from: 'next' from the previous page.")
                .bool("load", "Load the box's unloaded chunks for this read, and release them afterwards.")
                .array("groupBy", "NBT keys (dot paths, as 'keys') to group by: returns groups of block + "
                    + "identical values, each with a count and sample positions, not each entity. Not paged; "
                    + "'limit' caps the groups listed. Reads at most " + MAX_GROUP_CANDIDATES + " tile entities, "
                    + "and says 'truncated' when it stopped there.", Json.obj("type", "string"))
                .integer("samples", "With groupBy: sample positions per group. Default 3.", 0, 20)
                .build())
            .alias("include", "blocks")
            .alias("offset", "cursor")
            .serverOnly()
            .readOnly()
            .handler(context -> {
                final List<String> keys = Json.getStringList(context.getArguments(), "keys");
                final int maxChars = context.getBoundedInt("maxChars", DEFAULT_ENTITY_CHARS, 256, MAX_ENTITY_CHARS);
                final boolean summary = context.getBoolean("summary", false);
                if (context.getArguments().has("positions")) {
                    return readPositions(context, keys, maxChars, summary);
                }
                if (!context.getArguments().has("x") || !context.getArguments().has("z")) {
                    return ToolResult.error("Pass a box (x, z, and optionally y, toX, toY, toZ) or 'positions'.");
                }
                final int x1 = context.requireInt("x");
                final int z1 = context.requireInt("z");
                final int minX = Math.min(x1, context.getInt("toX", x1));
                final int maxX = Math.max(x1, context.getInt("toX", x1));
                final int minZ = Math.min(z1, context.getInt("toZ", z1));
                final int maxZ = Math.max(z1, context.getInt("toZ", z1));
                final int minY = Math.max(0, Math.min(context.getInt("y", 0), context.getInt("toY", 255)));
                final int maxY = Math.min(255, Math.max(context.getInt("y", 0), context.getInt("toY", 255)));
                final int dimension = context.getInt("dimension", 0);
                final List<String> blocks = Json.getStringList(context.getArguments(), "blocks");
                final int limit = context.getBoundedInt("limit", 200, 1, 2000);
                final int cursor = Math.max(0, context.getInt("cursor", 0));
                final List<String> groupBy = Json.getStringList(context.getArguments(), "groupBy");
                final int samples = context.getBoundedInt("samples", 3, 0, 20);
                long chunkCount = ChunkKeys.boxCount(minX, minZ, maxX, maxZ);
                if (chunkCount > 4096) {
                    return ToolResult.error("That box covers " + chunkCount + " chunks; read at most 4096 "
                        + "(about 1000 x 1000 blocks) at once.");
                }
                final Set<Long> chunks = ChunkKeys.box(minX, minZ, maxX, maxZ);

                return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
                    if (!groupBy.isEmpty()) {
                        return ToolResult.structured(grouped(context, dimension, chunks, minX, minY, minZ, maxX, maxY,
                            maxZ, blocks, groupBy, samples, limit, outcome));
                    }
                    return ToolResult.structured(boxed(context, dimension, chunks, minX, minY, minZ, maxX, maxY, maxZ,
                        blocks, keys, maxChars, summary, cursor, limit, outcome));
                });
            })
            .build());
    }

    /**
     * Box mode: one page of the matching entities, read in game-thread slices of about
     * {@link #SLICE_NANOS} (#219). Finding them is a walk of up to 4096 chunks' tile entities and a page
     * is up to 2000 tags serialised, and both in one task held the server thread for all of it. Only
     * positions cross between tasks, never a tile entity: each is looked up again where it is described,
     * and one that went in between is listed as gone rather than read from an object the world dropped.
     */
    private static JsonObject boxed(ToolContext context, final int dimension, Set<Long> chunks, final int minX,
                                    final int minY, final int minZ, final int maxX, final int maxY, final int maxZ,
                                    final List<String> blocks, final List<String> keys, final int maxChars,
                                    final boolean summary, int cursor, int limit,
                                    @Nullable ChunkLoadGovernor.Outcome outcome) {
        final List<Long> pending = new ArrayList<>(chunks);
        // Written by the game thread one task at a time, read here after the last.
        final List<BlockPos> found = new ArrayList<>();
        int done = 0;
        long lastReport = System.nanoTime();
        while (done < pending.size()) {
            // Every two seconds, so a wide box is not taken for a call that has stopped answering.
            if (System.nanoTime() - lastReport >= 2_000_000_000L) {
                lastReport = System.nanoTime();
                context.reportProgress(done, pending.size(), "Looked in " + done + " of " + pending.size() + " chunks");
            }
            final int from = done;
            done = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long started = System.nanoTime();
                int at = from;
                while (at < pending.size() && (at == from || System.nanoTime() - started < SLICE_NANOS)) {
                    long key = pending.get(at++);
                    Chunk chunk = world.getChunkProvider().getLoadedChunk(ChunkKeys.x(key), ChunkKeys.z(key));
                    if (chunk == null) {
                        continue;
                    }
                    for (TileEntity tile : chunk.getTileEntityMap().values()) {
                        BlockPos pos = tile.getPos();
                        if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ
                            || pos.getY() < minY || pos.getY() > maxY || tile.isInvalid()) {
                            continue;
                        }
                        ResourceLocation name = world.getBlockState(pos).getBlock().getRegistryName();
                        if (NbtProjection.matches(name == null ? "unknown" : name.toString(), blocks)) {
                            found.add(pos.toImmutable());
                        }
                    }
                }
                return at;
            });
        }
        // A fixed order, so a cursor means the same thing on the next page.
        found.sort(Comparator.<BlockPos>comparingInt(pos -> pos.getX())
            .thenComparingInt(pos -> pos.getZ()).thenComparingInt(pos -> pos.getY()));
        final JsonArray entities = new JsonArray();
        final int end = (int) Math.min((long) found.size(), (long) cursor + limit);
        int next = cursor;
        while (next < end) {
            if (System.nanoTime() - lastReport >= 2_000_000_000L) {
                lastReport = System.nanoTime();
                context.reportProgress(next - cursor, end - cursor, "Read " + (next - cursor) + " of " + (end - cursor)
                    + " tile entities");
            }
            final int first = next;
            next = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long started = System.nanoTime();
                int at = first;
                while (at < end && (at == first || System.nanoTime() - started < SLICE_NANOS)) {
                    BlockPos pos = found.get(at++);
                    // Its chunk may have unloaded since it was found, and asking would load it again.
                    TileEntity tile = world.isBlockLoaded(pos) ? world.getTileEntity(pos) : null;
                    if (tile == null || tile.isInvalid()) {
                        JsonObject gone = GameJson.blockPos(pos);
                        gone.addProperty("error", "The tile entity here went while this page was being read.");
                        entities.add(gone);
                        continue;
                    }
                    entities.add(describe(world, tile, keys, maxChars, summary));
                }
                return at;
            });
        }
        JsonObject result = new JsonObject();
        result.addProperty("dimension", dimension);
        result.addProperty("matched", found.size());
        result.addProperty("returned", entities.size());
        if (end < found.size()) {
            result.addProperty("next", end);
        }
        result.add("tileEntities", com.micatechnologies.minecraft.mcmcp.json.JsonTables.table(entities));
        result.add("chunks", chunksBlock(context, dimension, chunks, outcome));
        return result;
    }

    /**
     * {@code groupBy}: the matching entities as groups of identical values (#124), read in game-thread
     * slices of about {@link #SLICE_NANOS} a chunk batch at a time. Serialising a tag is the expensive
     * part, and over a 4096-chunk box in one task it held the server thread for seconds (#132). Stops
     * after {@link #MAX_GROUP_CANDIDATES} entities and says so.
     */
    private static JsonObject grouped(ToolContext context, final int dimension, Set<Long> chunks, final int minX,
                                      final int minY, final int minZ, final int maxX, final int maxY, final int maxZ,
                                      final List<String> blocks, final List<String> groupBy, int samples, int limit,
                                      @Nullable final ChunkLoadGovernor.Outcome outcome) {
        // Chunk by chunk, west to east then north to south, so the samples come in a fixed order.
        final List<Long> pending = new ArrayList<>(chunks);
        pending.sort(Comparator.<Long>comparingInt(ChunkKeys::x).thenComparingInt(ChunkKeys::z));
        final NbtGroups groups = new NbtGroups(samples);
        // matched, unreadable: written by the game thread one task at a time, read here after each.
        final int[] counts = {0, 0};
        int done = 0;
        while (done < pending.size() && counts[0] < MAX_GROUP_CANDIDATES) {
            final int from = done;
            done = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long started = System.nanoTime();
                int at = from;
                while (at < pending.size() && counts[0] < MAX_GROUP_CANDIDATES
                    && (at == from || System.nanoTime() - started < SLICE_NANOS)) {
                    long key = pending.get(at++);
                    Chunk chunk = world.getChunkProvider().getLoadedChunk(ChunkKeys.x(key), ChunkKeys.z(key));
                    if (chunk == null) {
                        continue;
                    }
                    List<TileEntity> found = new ArrayList<>();
                    for (TileEntity tile : chunk.getTileEntityMap().values()) {
                        BlockPos pos = tile.getPos();
                        if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ
                            || pos.getY() < minY || pos.getY() > maxY || tile.isInvalid()) {
                            continue;
                        }
                        ResourceLocation name = world.getBlockState(pos).getBlock().getRegistryName();
                        if (NbtProjection.matches(name == null ? "unknown" : name.toString(), blocks)) {
                            found.add(tile);
                        }
                    }
                    found.sort(Comparator.<TileEntity>comparingInt(t -> t.getPos().getX())
                        .thenComparingInt(t -> t.getPos().getZ()).thenComparingInt(t -> t.getPos().getY()));
                    for (TileEntity tile : found) {
                        if (counts[0] >= MAX_GROUP_CANDIDATES) {
                            // The rest of this chunk is not read either: start again from it on a narrower box.
                            break;
                        }
                        counts[0]++;
                        BlockPos pos = tile.getPos();
                        ResourceLocation name = world.getBlockState(pos).getBlock().getRegistryName();
                        JsonObject value;
                        try {
                            JsonElement tag = NbtJson.toJson(tile.writeToNBT(new NBTTagCompound()));
                            value = NbtProjection.project(tag.isJsonObject() ? tag.getAsJsonObject() : new JsonObject(),
                                groupBy);
                        }
                        catch (RuntimeException e) {
                            counts[1]++;
                            continue;
                        }
                        groups.add(name == null ? "unknown" : name.toString(), value, pos.getX(), pos.getY(), pos.getZ());
                    }
                }
                return at;
            });
            if (pending.size() > 64) {
                context.reportProgress(done, pending.size(), "Grouped " + done + " of " + pending.size() + " chunks");
            }
        }
        final int chunksRead = done;
        JsonObject result = new JsonObject();
        result.addProperty("dimension", dimension);
        result.addProperty("matched", counts[0]);
        if (counts[0] >= MAX_GROUP_CANDIDATES) {
            result.addProperty("truncated", true);
            result.addProperty("chunksNotRead", pending.size() - chunksRead);
            result.addProperty("note", "Stopped after " + MAX_GROUP_CANDIDATES + " tile entities, the most one groupBy "
                + "reads: the groups count only those. Narrow the box or 'blocks' to read the rest.");
        }
        result.addProperty("groupCount", groups.size());
        if (groups.size() > limit) {
            result.addProperty("groupsNotListed", groups.size() - limit);
        }
        if (counts[1] > 0) {
            result.addProperty("unreadable", counts[1]);
        }
        result.add("groups", groups.toJson(limit));
        result.add("chunks", chunksBlock(context, dimension, chunks, outcome));
        return result;
    }

    /** {@code positions} mode: the named blocks, in the order named. */
    private static ToolResult readPositions(final ToolContext context, final List<String> keys, final int maxChars,
                                            final boolean summary) throws Exception {
        JsonArray named = context.getArguments().get("positions").isJsonArray()
            ? context.getArguments().getAsJsonArray("positions") : new JsonArray();
        if (named.size() == 0 || named.size() > MAX_POSITIONS) {
            return ToolResult.error("'positions' takes 1 to " + MAX_POSITIONS + " entries; it had " + named.size() + ".");
        }
        final List<BlockPos> positions = new ArrayList<>();
        final Set<Long> chunks = new java.util.LinkedHashSet<>();
        for (JsonElement element : named) {
            if (!element.isJsonObject()) {
                return ToolResult.error("Each position is an object {x, y, z}.");
            }
            JsonObject at = element.getAsJsonObject();
            if (!at.has("x") || !at.has("y") || !at.has("z")) {
                return ToolResult.error("Each position needs x, y and z.");
            }
            BlockPos pos = new BlockPos(Json.getInt(at, "x", 0), Json.getInt(at, "y", 0), Json.getInt(at, "z", 0));
            positions.add(pos);
            chunks.add(ChunkKeys.ofBlock(pos.getX(), pos.getZ()));
        }
        final int dimension = context.getInt("dimension", 0);
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            // In game-thread slices of about SLICE_NANOS, as box mode (#220): 1,000 tags serialised in
            // one task held the server thread for all of them. Written by the game thread one task at a
            // time, read here after the last.
            final JsonArray entities = new JsonArray();
            final int[] foundCount = {0};
            long lastReport = System.nanoTime();
            int done = 0;
            while (done < positions.size()) {
                final int from = done;
                done = context.onGameThread(() -> {
                    WorldServer world = ServerWorldTools.requireWorld(dimension);
                    long started = System.nanoTime();
                    int at = from;
                    while (at < positions.size() && (at == from || System.nanoTime() - started < SLICE_NANOS)) {
                        BlockPos pos = positions.get(at++);
                        TileEntity tile = world.isBlockLoaded(pos) ? world.getTileEntity(pos) : null;
                        if (tile == null || tile.isInvalid()) {
                            JsonObject entry = GameJson.blockPos(pos);
                            entry.addProperty("error", world.isBlockLoaded(pos) ? "No tile entity here."
                                : "Chunk not loaded; pass load: true.");
                            entities.add(entry);
                            continue;
                        }
                        foundCount[0]++;
                        entities.add(describe(world, tile, keys, maxChars, summary));
                    }
                    return at;
                });
                // By the clock, not by the task: a task is 25 ms, and one report each floods the link.
                if (System.nanoTime() - lastReport >= REPORT_NANOS) {
                    lastReport = System.nanoTime();
                    context.reportProgress(done, positions.size(), "Read " + done + " of " + positions.size()
                        + " positions");
                }
            }
            JsonObject result = new JsonObject();
            result.addProperty("dimension", dimension);
            result.addProperty("matched", foundCount[0]);
            result.addProperty("returned", entities.size());
            result.add("tileEntities", com.micatechnologies.minecraft.mcmcp.json.JsonTables.table(entities));
            result.add("chunks", chunksBlock(context, dimension, chunks, outcome));
            return ToolResult.structured(result);
        });
    }

    /**
     * The read's {@code chunks} block. A chunk loaded only for this read is unloaded again after it, so
     * its tile entities tick only while a read holds it: a controller watched that way re-serves
     * phases and starves side streets, and looks broken (#88). Said, when it happened.
     *
     * <p>From the worker thread: the chunks are counted across several game-thread tasks
     * ({@link ServerChunkTools#describeChunks}), where 4096 in one held the server thread (#219).
     */
    private static JsonObject chunksBlock(ToolContext context, int dimension, Set<Long> chunks,
                                          @Nullable ChunkLoadGovernor.Outcome outcome) {
        JsonObject json = ServerChunkTools.describeChunks(context, dimension, chunks, outcome, false);
        return outcome == null ? json : loadedNote(json);
    }

    private static JsonObject loadedNote(JsonObject json) {
        if (json.get("loaded").getAsInt() > 0) {
            json.addProperty("note", "Some chunks were loaded only for this read and are unloaded after it: their "
                + "tile entities do not tick in between, so time-driven state (a controller's cycle) will not "
                + "advance between reads. Hold the region with server_keep_loaded to keep it ticking.");
        }
        return json;
    }

    /** Game thread. */
    private static JsonObject describe(WorldServer world, TileEntity tile, List<String> keys, int maxChars,
                                       boolean summary) {
        BlockPos pos = tile.getPos();
        JsonObject entry = GameJson.blockPos(pos);
        ResourceLocation name = world.getBlockState(pos).getBlock().getRegistryName();
        entry.addProperty("block", name == null ? "unknown" : name.toString());
        JsonObject nbt;
        try {
            JsonElement tag = NbtJson.toJson(tile.writeToNBT(new NBTTagCompound()));
            nbt = tag.isJsonObject() ? tag.getAsJsonObject() : new JsonObject();
        }
        catch (RuntimeException e) {
            entry.addProperty("error", "The tile entity could not write its tag: " + e);
            return entry;
        }
        JsonObject shown = keys.isEmpty() ? nbt : NbtProjection.project(nbt, keys);
        int size = Json.write(shown).length();
        if (summary || size > maxChars) {
            if (!summary) {
                entry.addProperty("truncated", true);
            }
            entry.addProperty("chars", size);
            entry.add("summary", NbtProjection.summary(shown, 3));
        }
        else {
            entry.add("nbt", shown);
        }
        return entry;
    }
}
