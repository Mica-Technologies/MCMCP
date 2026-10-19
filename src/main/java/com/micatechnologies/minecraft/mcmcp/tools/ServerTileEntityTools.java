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
                + "block; pass load: true to read them.")
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
                .build())
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
                long chunkCount = ChunkKeys.boxCount(minX, minZ, maxX, maxZ);
                if (chunkCount > 4096) {
                    return ToolResult.error("That box covers " + chunkCount + " chunks; read at most 4096 "
                        + "(about 1000 x 1000 blocks) at once.");
                }
                final Set<Long> chunks = ChunkKeys.box(minX, minZ, maxX, maxZ);

                return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
                    JsonObject json = context.onGameThread(() -> {
                        WorldServer world = ServerWorldTools.requireWorld(dimension);
                        List<TileEntity> found = new ArrayList<>();
                        for (long key : chunks) {
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
                                    found.add(tile);
                                }
                            }
                        }
                        // A fixed order, so a cursor means the same thing on the next page.
                        found.sort(Comparator.<TileEntity>comparingInt(t -> t.getPos().getX())
                            .thenComparingInt(t -> t.getPos().getZ()).thenComparingInt(t -> t.getPos().getY()));
                        JsonArray entities = new JsonArray();
                        int end = Math.min(found.size(), cursor + limit);
                        for (int i = cursor; i < end; i++) {
                            entities.add(describe(world, found.get(i), keys, maxChars, summary));
                        }
                        JsonObject result = new JsonObject();
                        result.addProperty("dimension", dimension);
                        result.addProperty("matched", found.size());
                        result.addProperty("returned", entities.size());
                        if (end < found.size()) {
                            result.addProperty("next", end);
                        }
                        result.add("tileEntities", com.micatechnologies.minecraft.mcmcp.json.JsonTables.table(entities));
                        ChunkLoadGovernor.touch(dimension, chunks);
                        result.add("chunks", chunksBlock(world, chunks, outcome));
                        return result;
                    });
                    return ToolResult.structured(json);
                });
            })
            .build());
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
            JsonObject json = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                JsonArray entities = new JsonArray();
                int foundCount = 0;
                for (BlockPos pos : positions) {
                    TileEntity tile = world.isBlockLoaded(pos) ? world.getTileEntity(pos) : null;
                    if (tile == null || tile.isInvalid()) {
                        JsonObject entry = GameJson.blockPos(pos);
                        entry.addProperty("error", world.isBlockLoaded(pos) ? "No tile entity here."
                            : "Chunk not loaded; pass load: true.");
                        entities.add(entry);
                        continue;
                    }
                    foundCount++;
                    entities.add(describe(world, tile, keys, maxChars, summary));
                }
                JsonObject result = new JsonObject();
                result.addProperty("dimension", dimension);
                result.addProperty("matched", foundCount);
                result.addProperty("returned", entities.size());
                result.add("tileEntities", com.micatechnologies.minecraft.mcmcp.json.JsonTables.table(entities));
                ChunkLoadGovernor.touch(dimension, chunks);
                result.add("chunks", chunksBlock(world, chunks, outcome));
                return result;
            });
            return ToolResult.structured(json);
        });
    }

    /**
     * The read's {@code chunks} block. A chunk loaded only for this read is unloaded again after it, so
     * its tile entities tick only while a read holds it: a controller watched that way re-serves
     * phases and starves side streets, and looks broken (#88). Said, when it happened. Game thread.
     */
    private static JsonObject chunksBlock(WorldServer world, Set<Long> chunks,
                                          @Nullable ChunkLoadGovernor.Outcome outcome) {
        if (outcome == null) {
            return ChunkLoadGovernor.describeUnloaded(world, chunks);
        }
        JsonObject json = outcome.toJson();
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
