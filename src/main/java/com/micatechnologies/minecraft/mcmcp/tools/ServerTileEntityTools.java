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
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.NbtProjection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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

    /** Characters of NBT one entity may contribute; larger ones list their keys instead. */
    private static final int MAX_ENTITY_CHARS = 8192;

    private ServerTileEntityTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_get_tile_entities")
            .title("Read tile entities in a region")
            .description("Every tile entity in a box with its server-side NBT, in one call: the "
                + "authoritative state, not the client's copy. Filter by block ('csm:controller', or a "
                + "namespace 'csm:' or prefix 'csm:signal_*') and keep only the NBT fields you need with "
                + "'keys' (dot paths, e.g. 'tcCp' or 'Items.id'). Paged: when 'next' is present, call again "
                + "with cursor = next. Unloaded chunks are listed in the 'chunks' block; pass load: true to "
                + "read them.")
            .schema(JsonSchema.object()
                .integer("x", "X of one corner.")
                .integer("y", "Y of one corner. Defaults to 0.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Defaults to x.")
                .integer("toY", "Y of the opposite corner. Defaults to 255.")
                .integer("toZ", "Z of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .array("blocks", "Only these blocks: ids, namespaces ending in ':', or prefixes ending in '*'.",
                    Json.obj("type", "string"))
                .array("keys", "Only these NBT fields, as dot paths. Omit for the whole tag (large tags list "
                    + "their keys instead).", Json.obj("type", "string"))
                .integer("limit", "Entities per page. Default 200.", 1, 2000)
                .integer("cursor", "Where to continue from: 'next' from the previous page.")
                .bool("load", "Load the box's unloaded chunks for this read, and release them afterwards.")
                .required("x", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
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
                final List<String> keys = Json.getStringList(context.getArguments(), "keys");
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
                            entities.add(describe(world, found.get(i), keys));
                        }
                        JsonObject result = new JsonObject();
                        result.addProperty("dimension", dimension);
                        result.addProperty("matched", found.size());
                        result.addProperty("returned", entities.size());
                        if (end < found.size()) {
                            result.addProperty("next", end);
                        }
                        result.add("tileEntities", entities);
                        ChunkLoadGovernor.touch(dimension, chunks);
                        result.add("chunks", outcome != null ? outcome.toJson()
                            : ChunkLoadGovernor.describeUnloaded(world, chunks));
                        return result;
                    });
                    return ToolResult.structured(json);
                });
            })
            .build());
    }

    /** Game thread. */
    private static JsonObject describe(WorldServer world, TileEntity tile, List<String> keys) {
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
        if (size > MAX_ENTITY_CHARS) {
            JsonArray names = new JsonArray();
            for (java.util.Map.Entry<String, JsonElement> field : shown.entrySet()) {
                names.add(field.getKey());
            }
            entry.addProperty("truncated", true);
            entry.addProperty("chars", size);
            entry.add("keys", names);
        }
        else {
            entry.add("nbt", shown);
        }
        return entry;
    }
}
