package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Bulk region read and write — the pair of tools that make an agent able to survey an area and then
 * build in it.
 *
 * <h2>Why bulk, and why a palette</h2>
 *
 * Reading a 16x16x16 area one block at a time is 4,096 MCP round trips, each with its own JSON
 * envelope, and it produces a transcript no model can hold in context. So {@code server_get_blocks}
 * reads a whole region in one game-thread task and encodes it the way Minecraft itself encodes
 * chunk sections: a <em>palette</em> of the distinct block ids present, plus a flat array of indices
 * into that palette.
 *
 * <p>That representation is what makes the response affordable. A 16³ region of mostly stone and air
 * is two palette entries and 4,096 small integers — a few kilobytes — where the same region as
 * per-block objects would be well over a megabyte of JSON. It is also the shape a model reasons
 * about most easily: the palette answers "what is here" at a glance, and the index array answers
 * "where" without repeating a namespaced id 4,000 times.
 *
 * <p>The {@code summary} format goes further and returns only the palette with counts. For "what am
 * I standing in the middle of", that is the entire useful answer for a fraction of the tokens.
 *
 * <h2>Writing</h2>
 *
 * {@code server_set_blocks} takes either a fill region or an explicit list of placements, so a model
 * can lay a foundation with one call and then place the individual detail blocks with another,
 * instead of issuing one call per block. Both paths are bounded by {@code limits.maxBlockVolume},
 * because the whole operation runs inside a single game-thread task: the bound on volume is directly
 * a bound on how long one MCP call can stall the tick loop.
 */
public final class ServerBuildTools {

    private ServerBuildTools() {
    }

    public static void register() {
        registerGetBlocks();
        registerSetBlocks();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private static void registerGetBlocks() {
        McpRegistry.registerTool(McpTool.named("server_get_blocks")
            .title("Get blocks in a region")
            .description("Read every block in a cuboid region in one call. Use this to survey an area "
                + "before building in it, rather than reading positions individually.\n\n"
                + "Returns a palette of the distinct blocks present plus a flat index array in "
                + "x-then-z-then-y order (x varies fastest, y slowest), so the block at local offset "
                + "(dx, dy, dz) is at index dx + sizeX * (dz + sizeZ * dy). Choose format 'summary' when "
                + "you only need to know what is present, not where — it is far cheaper.")
            .schema(JsonSchema.object()
                .integer("x", "X coordinate of one corner of the region.")
                .integer("y", "Y coordinate of one corner, 0-255.")
                .integer("z", "Z coordinate of one corner.")
                .integer("toX", "X coordinate of the opposite corner. Defaults to x.")
                .integer("toY", "Y coordinate of the opposite corner. Defaults to y.")
                .integer("toZ", "Z coordinate of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id: 0 overworld, -1 nether, 1 end. Defaults to 0.")
                .enumeration("format", "How to encode the result. 'palette' (default) returns the "
                        + "palette and the full index array. 'summary' returns only the palette with "
                        + "block counts. 'list' returns one object per non-air block, which is the most "
                        + "readable but by far the largest.",
                    "palette", "summary", "list")
                .bool("includeAir", "Include air in the palette and counts. Defaults to true for "
                    + "'palette' (the index array needs it) and false for 'summary' and 'list'.")
                .required("x", "y", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                final int x1 = context.requireInt("x");
                final int y1 = context.requireInt("y");
                final int z1 = context.requireInt("z");
                final int x2 = context.getInt("toX", x1);
                final int y2 = context.getInt("toY", y1);
                final int z2 = context.getInt("toZ", z1);
                final int dimension = context.getInt("dimension", 0);
                final String format = context.getString("format", "palette");

                final int minX = Math.min(x1, x2);
                final int minY = Math.max(0, Math.min(y1, y2));
                final int minZ = Math.min(z1, z2);
                final int maxX = Math.max(x1, x2);
                final int maxY = Math.min(255, Math.max(y1, y2));
                final int maxZ = Math.max(z1, z2);

                final int sizeX = maxX - minX + 1;
                final int sizeY = maxY - minY + 1;
                final int sizeZ = maxZ - minZ + 1;
                final long volume = (long) sizeX * sizeY * sizeZ;

                if (volume > McmcpConfig.getMaxBlockVolume()) {
                    return ToolResult.error("That region is " + volume + " blocks, over the limit of "
                        + McmcpConfig.getMaxBlockVolume() + " set by limits.maxBlockVolume. Read it in "
                        + "several smaller regions, or use format 'summary' on a region within the "
                        + "limit.");
                }

                final boolean includeAir = context.getBoolean("includeAir", "palette".equals(format));

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        WorldServer world = ServerWorldTools.requireWorld(dimension);

                        // Insertion-ordered so palette indices are stable and reproducible for the
                        // same region — a model comparing two reads of the same area should not see
                        // the indices shuffle.
                        Map<String, PaletteEntry> palette = new LinkedHashMap<>();
                        List<Integer> indices = new ArrayList<>();
                        JsonArray blockList = new JsonArray();
                        int unloaded = 0;

                        // y outermost, then z, then x: the documented ordering, and it also matches
                        // how a builder thinks about a structure — layer by layer from the bottom.
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                for (int x = minX; x <= maxX; x++) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    String id;
                                    int metadata = 0;

                                    if (!world.isBlockLoaded(pos)) {
                                        // A distinct marker, not air. Reporting an unloaded chunk as
                                        // air would have a model build into terrain it cannot see.
                                        id = "mcmcp:unloaded";
                                        unloaded++;
                                    }
                                    else {
                                        IBlockState state = world.getBlockState(pos);
                                        ResourceLocation name = state.getBlock().getRegistryName();
                                        id = name == null ? "unknown" : name.toString();
                                        metadata = state.getBlock().getMetaFromState(state);
                                        if (metadata != 0) {
                                            id = id + ":" + metadata;
                                        }
                                    }

                                    boolean isAir = "minecraft:air".equals(id);
                                    PaletteEntry entry = palette.get(id);
                                    if (entry == null) {
                                        entry = new PaletteEntry(palette.size(), id, metadata);
                                        palette.put(id, entry);
                                    }
                                    entry.count++;

                                    if ("palette".equals(format)) {
                                        indices.add(entry.index);
                                    }
                                    else if ("list".equals(format) && (includeAir || !isAir)) {
                                        JsonObject item = GameJson.blockPos(pos);
                                        item.addProperty("block", id);
                                        blockList.add(item);
                                    }
                                }
                            }
                        }

                        JsonObject json = new JsonObject();
                        json.addProperty("dimension", dimension);
                        json.add("origin", GameJson.blockPos(new BlockPos(minX, minY, minZ)));
                        json.addProperty("sizeX", sizeX);
                        json.addProperty("sizeY", sizeY);
                        json.addProperty("sizeZ", sizeZ);
                        json.addProperty("volume", (int) volume);
                        if (unloaded > 0) {
                            json.addProperty("unloadedBlocks", unloaded);
                        }

                        JsonArray paletteArray = new JsonArray();
                        for (PaletteEntry entry : palette.values()) {
                            if (!includeAir && "minecraft:air".equals(entry.id)) {
                                continue;
                            }
                            JsonObject paletteJson = new JsonObject();
                            paletteJson.addProperty("index", entry.index);
                            paletteJson.addProperty("block", entry.id);
                            paletteJson.addProperty("count", entry.count);
                            paletteArray.add(paletteJson);
                        }
                        json.add("palette", paletteArray);

                        if ("palette".equals(format)) {
                            json.addProperty("indexOrder",
                                "index = dx + sizeX * (dz + sizeZ * dy), relative to origin");
                            JsonArray indexArray = new JsonArray();
                            for (Integer index : indices) {
                                indexArray.add(index);
                            }
                            json.add("indices", indexArray);
                        }
                        else if ("list".equals(format)) {
                            json.add("blocks", blockList);
                        }
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    private static void registerSetBlocks() {
        McpRegistry.registerTool(McpTool.named("server_set_blocks")
            .title("Set blocks in bulk")
            .description("Place many blocks in one call, either by filling a cuboid region or by "
                + "applying an explicit list of placements. This is how to build a structure: fill the "
                + "bulk volumes first, then apply a list for the detail.\n\n"
                + "Like server_set_block, this writes directly and does not fire block-place events, "
                + "so other mods' protection and machinery hooks do not run. Disabled unless "
                + "permissions.allowWorldEdits is enabled in the MCMCP config.")
            .schema(JsonSchema.object()
                .enumeration("mode", "'fill' writes one block type across a cuboid region. 'list' "
                    + "applies the explicit placements in the blocks argument.", "fill", "list")
                .string("block", "Namespaced block id for fill mode, e.g. 'minecraft:stone'.")
                .integer("metadata", "Block metadata for fill mode. Defaults to 0.", 0, 15)
                .integer("x", "Fill mode: X of one corner.")
                .integer("y", "Fill mode: Y of one corner, 0-255.")
                .integer("z", "Fill mode: Z of one corner.")
                .integer("toX", "Fill mode: X of the opposite corner. Defaults to x.")
                .integer("toY", "Fill mode: Y of the opposite corner. Defaults to y.")
                .integer("toZ", "Fill mode: Z of the opposite corner. Defaults to z.")
                .array("blocks", "List mode: the placements to apply, in order.",
                    JsonSchema.object()
                        .integer("x", "Block X coordinate.")
                        .integer("y", "Block Y coordinate, 0-255.")
                        .integer("z", "Block Z coordinate.")
                        .string("block", "Namespaced block id.")
                        .integer("metadata", "Block metadata. Defaults to 0.", 0, 15)
                        .required("x", "y", "z", "block")
                        .build())
                .string("replaceOnly", "Only write where the existing block matches this namespaced "
                    + "id. Use 'minecraft:air' to build without destroying anything already there.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .build())
            .serverOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowWorldEdits()) {
                    return ToolResult.error("Direct world edits are disabled by "
                        + "permissions.allowWorldEdits in the MCMCP config. Ask the server operator to "
                        + "enable it, or build through player actions or commands instead.");
                }

                final int dimension = context.getInt("dimension", 0);
                final String replaceOnlyId = context.getString("replaceOnly", null);

                final Block replaceOnly;
                if (replaceOnlyId != null && !replaceOnlyId.isEmpty()) {
                    replaceOnly = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(replaceOnlyId));
                    if (replaceOnly == null) {
                        return ToolResult.error("No block is registered with the id '" + replaceOnlyId
                            + "' for replaceOnly.");
                    }
                }
                else {
                    replaceOnly = null;
                }

                final String mode = context.getString("mode",
                    context.has("blocks") ? "list" : "fill");

                final List<Placement> placements;
                if ("list".equals(mode)) {
                    placements = parsePlacements(context.getArguments());
                    if (placements == null) {
                        return ToolResult.error("List mode requires a 'blocks' array of objects with "
                            + "x, y, z and block.");
                    }
                }
                else {
                    placements = null;
                }

                if (placements != null) {
                    if (placements.isEmpty()) {
                        return ToolResult.error("The 'blocks' array is empty; nothing to place.");
                    }
                    if (placements.size() > McmcpConfig.getMaxBlockVolume()) {
                        return ToolResult.error("That is " + placements.size() + " placements, over the "
                            + "limit of " + McmcpConfig.getMaxBlockVolume()
                            + " set by limits.maxBlockVolume. Split it across several calls.");
                    }
                    for (Placement placement : placements) {
                        if (placement.block == null) {
                            return ToolResult.error("No block is registered with the id '"
                                + placement.blockId + "'.");
                        }
                        if (placement.pos.getY() < 0 || placement.pos.getY() > 255) {
                            return ToolResult.error("y must be between 0 and 255; got "
                                + placement.pos.getY() + " at x=" + placement.pos.getX()
                                + ", z=" + placement.pos.getZ() + ".");
                        }
                    }

                    JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                        @Override
                        public JsonObject call() {
                            WorldServer world = ServerWorldTools.requireWorld(dimension);
                            int written = 0;
                            int skipped = 0;
                            for (Placement placement : placements) {
                                if (replaceOnly != null
                                    && world.getBlockState(placement.pos).getBlock() != replaceOnly) {
                                    skipped++;
                                    continue;
                                }
                                if (world.setBlockState(placement.pos,
                                    placement.block.getStateFromMeta(placement.metadata), 3)) {
                                    written++;
                                }
                            }
                            return summary(dimension, placements.size(), written, skipped, replaceOnlyId);
                        }
                    });
                    return ToolResult.structured(result);
                }

                // Fill mode.
                final String blockId = context.getString("block", null);
                if (blockId == null || blockId.isEmpty()) {
                    return ToolResult.error("Fill mode requires a 'block' argument.");
                }
                final Block fillBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(blockId));
                if (fillBlock == null) {
                    return ToolResult.error("No block is registered with the id '" + blockId + "'.");
                }

                final int metadata = context.getBoundedInt("metadata", 0, 0, 15);
                final int x1 = context.requireInt("x");
                final int y1 = context.requireInt("y");
                final int z1 = context.requireInt("z");
                final int minX = Math.min(x1, context.getInt("toX", x1));
                final int minY = Math.max(0, Math.min(y1, context.getInt("toY", y1)));
                final int minZ = Math.min(z1, context.getInt("toZ", z1));
                final int maxX = Math.max(x1, context.getInt("toX", x1));
                final int maxY = Math.min(255, Math.max(y1, context.getInt("toY", y1)));
                final int maxZ = Math.max(z1, context.getInt("toZ", z1));

                final long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                if (volume > McmcpConfig.getMaxBlockVolume()) {
                    return ToolResult.error("That region is " + volume + " blocks, over the limit of "
                        + McmcpConfig.getMaxBlockVolume() + " set by limits.maxBlockVolume. Fill it in "
                        + "several smaller regions.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        WorldServer world = ServerWorldTools.requireWorld(dimension);
                        IBlockState state = fillBlock.getStateFromMeta(metadata);
                        int written = 0;
                        int skipped = 0;
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                for (int x = minX; x <= maxX; x++) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    if (replaceOnly != null
                                        && world.getBlockState(pos).getBlock() != replaceOnly) {
                                        skipped++;
                                        continue;
                                    }
                                    if (world.setBlockState(pos, state, 3)) {
                                        written++;
                                    }
                                }
                            }
                        }
                        JsonObject json = summary(dimension, (int) volume, written, skipped, replaceOnlyId);
                        json.addProperty("block", blockId);
                        json.add("from", GameJson.blockPos(new BlockPos(minX, minY, minZ)));
                        json.add("to", GameJson.blockPos(new BlockPos(maxX, maxY, maxZ)));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static JsonObject summary(int dimension, int attempted, int written, int skipped,
                                      String replaceOnlyId) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", dimension);
        json.addProperty("attempted", attempted);
        json.addProperty("written", written);
        json.addProperty("skipped", skipped);
        if (replaceOnlyId != null && !replaceOnlyId.isEmpty()) {
            json.addProperty("replaceOnly", replaceOnlyId);
        }
        // written < attempted - skipped means the world rejected some writes: usually the same block
        // was already there, occasionally an unloaded chunk. Reporting it separately from `skipped`
        // is what lets a model tell "my filter excluded these" from "the world refused these".
        json.addProperty("unchanged", attempted - skipped - written);
        return json;
    }

    /** Parses list mode's {@code blocks} array; null if the argument is missing or the wrong shape. */
    private static List<Placement> parsePlacements(JsonObject arguments) {
        JsonArray array = Json.getArray(arguments, "blocks");
        if (array == null) {
            return null;
        }
        List<Placement> placements = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String id = Json.getString(item, "block");
            if (id == null || id.isEmpty()) {
                continue;
            }
            placements.add(new Placement(
                new BlockPos(Json.getInt(item, "x", 0), Json.getInt(item, "y", 0),
                    Json.getInt(item, "z", 0)),
                id,
                ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)),
                Math.max(0, Math.min(15, Json.getInt(item, "metadata", 0)))));
        }
        return placements;
    }

    private static final class Placement {

        final BlockPos pos;
        final String blockId;
        final Block block;
        final int metadata;

        Placement(BlockPos pos, String blockId, Block block, int metadata) {
            this.pos = pos;
            this.blockId = blockId;
            this.block = block;
            this.metadata = metadata;
        }
    }

    private static final class PaletteEntry {

        final int index;
        final String id;
        final int metadata;

        int count;

        PaletteEntry(int index, String id, int metadata) {
            this.index = index;
            this.id = id;
            this.metadata = metadata;
        }
    }
}
