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
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

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

    /**
     * How many game-thread batches one call may be split into. {@code limits.maxBlockVolume} bounds
     * one batch — how long a single task holds the tick loop — and this bounds the call, so clearing
     * a scene is one call rather than fourteen single-layer ones, without a typo in a corner turning
     * into a million-block write.
     */
    static final int MAX_BATCHES = 32;

    private static void registerSetBlocks() {
        McpRegistry.registerTool(McpTool.named("server_set_blocks")
            .title("Set blocks in bulk")
            .description("Place many blocks in one call, either by filling a cuboid region or by "
                + "applying an explicit list of placements. This is how to build a structure: fill the "
                + "bulk volumes first, then apply a list for the detail.\n\n"
                + "A region or list larger than limits.maxBlockVolume is written in batches of that "
                + "size, one per game tick, up to " + MAX_BATCHES + " batches — so a large area can be "
                + "cleared (fill with minecraft:air) in one call. 'nbt' on a placement, or on a fill, "
                + "merges tile-entity data into each block after it is placed, as /blockdata does; "
                + "read it back with server_get_block nbt=true.\n\n"
                + "Like server_set_block, this writes directly and does not fire block-place events, "
                + "so other mods' protection and machinery hooks do not run. Disabled unless "
                + "permissions.allowWorldEdits is enabled in the MCMCP config.")
            .schema(JsonSchema.object()
                .enumeration("mode", "'fill' writes one block type across a cuboid region. 'list' "
                    + "applies the explicit placements in the blocks argument.", "fill", "list")
                .string("block", "Namespaced block id for fill mode, e.g. 'minecraft:stone'.")
                .integer("metadata", "Block metadata for fill mode. Defaults to 0.", 0, 15)
                .property("nbt", nbtSchema("Fill mode: tile-entity data merged into every block "
                    + "filled."))
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
                        .property("nbt", nbtSchema("Tile-entity data merged into this block after "
                            + "it is placed."))
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
                final int batchLimit = McmcpConfig.getMaxBlockVolume();
                final long callLimit = (long) batchLimit * MAX_BATCHES;

                final Block replaceOnly;
                if (replaceOnlyId != null && !replaceOnlyId.isEmpty()) {
                    replaceOnly = BlockIds.resolve(replaceOnlyId);
                    if (replaceOnly == null) {
                        return ToolResult.error(
                            BlockIds.describeUnknown(replaceOnlyId, "use as replaceOnly"));
                    }
                }
                else {
                    replaceOnly = null;
                }

                final String mode = context.getString("mode",
                    context.has("blocks") ? "list" : "fill");

                final List<Placement> placements;
                if ("list".equals(mode)) {
                    try {
                        placements = parsePlacements(context.getArguments());
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error(e.getMessage());
                    }
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
                    if (placements.size() > callLimit) {
                        return ToolResult.error("That is " + placements.size() + " placements, over "
                            + "the limit of " + callLimit + " for one call (" + MAX_BATCHES
                            + " batches of limits.maxBlockVolume, " + batchLimit + "). Split it across "
                            + "several calls.");
                    }
                    for (Placement placement : placements) {
                        if (placement.block == null) {
                            return ToolResult.error(
                                BlockIds.describeUnknown(placement.blockId, "place"));
                        }
                        if (placement.pos.getY() < 0 || placement.pos.getY() > 255) {
                            return ToolResult.error("y must be between 0 and 255; got "
                                + placement.pos.getY() + " at x=" + placement.pos.getX()
                                + ", z=" + placement.pos.getZ() + ".");
                        }
                    }

                    final Tally tally = new Tally();
                    runInBatches(context, placements.size(), batchLimit, new Batch() {
                        @Override
                        public void run(int from, int to) {
                            WorldServer world = ServerWorldTools.requireWorld(dimension);
                            for (int i = from; i < to; i++) {
                                Placement placement = placements.get(i);
                                place(world, placement.pos,
                                    placement.block.getStateFromMeta(placement.metadata),
                                    placement.nbt, replaceOnly, tally);
                            }
                        }
                    }, tally);
                    return ToolResult.structured(tally.toJson(dimension, placements.size(), replaceOnlyId));
                }

                // Fill mode.
                final String blockId = context.getString("block", null);
                if (blockId == null || blockId.isEmpty()) {
                    return ToolResult.error("Fill mode requires a 'block' argument.");
                }
                final Block fillBlock = BlockIds.resolve(blockId);
                if (fillBlock == null) {
                    return ToolResult.error(BlockIds.describeUnknown(blockId, "fill with"));
                }
                final NBTTagCompound fillNbt;
                try {
                    fillNbt = context.has("nbt") ? TileEntityNbt.parse(context.getArguments().get("nbt")) : null;
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
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

                final int sizeX = maxX - minX + 1;
                final int sizeZ = maxZ - minZ + 1;
                final long volume = (long) sizeX * (maxY - minY + 1) * sizeZ;
                if (volume > callLimit) {
                    return ToolResult.error("That region is " + volume + " blocks, over the limit of "
                        + callLimit + " for one call (" + MAX_BATCHES + " batches of "
                        + "limits.maxBlockVolume, " + batchLimit + "). Fill it in several calls.");
                }

                final IBlockState state = fillBlock.getStateFromMeta(metadata);
                final Tally tally = new Tally();
                runInBatches(context, (int) volume, batchLimit, new Batch() {
                    @Override
                    public void run(int from, int to) {
                        WorldServer world = ServerWorldTools.requireWorld(dimension);
                        // Linear index, x fastest then z then y, the order server_get_blocks uses:
                        // layer by layer from the bottom, so a batch boundary never leaves a column
                        // half-filled above an unfilled one.
                        for (int i = from; i < to; i++) {
                            int x = minX + i % sizeX;
                            int z = minZ + (i / sizeX) % sizeZ;
                            int y = minY + i / (sizeX * sizeZ);
                            place(world, new BlockPos(x, y, z), state, fillNbt, replaceOnly, tally);
                        }
                    }
                }, tally);
                JsonObject json = tally.toJson(dimension, (int) volume, replaceOnlyId);
                json.addProperty("block", blockId);
                json.add("from", GameJson.blockPos(new BlockPos(minX, minY, minZ)));
                json.add("to", GameJson.blockPos(new BlockPos(maxX, maxY, maxZ)));
                return ToolResult.structured(json);
            })
            .build());
    }

    /** An {@code nbt} property: an SNBT string or a JSON object. */
    static JsonObject nbtSchema(String description) {
        JsonObject schema = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("object");
        schema.add("type", types);
        schema.addProperty("description", description + " An SNBT string such as "
            + "'{CustomName:\"Panel A\",Mode:2b}', or a JSON object; use the SNBT string when a "
            + "field must be a byte, short, long or float. For a line break inside a string, put a "
            + "literal line feed in it — SNBT has no \\n escape.");
        return schema;
    }

    /** Places one block and merges its tile-entity data. Game thread only. */
    private static void place(WorldServer world, BlockPos pos, IBlockState state,
                              @Nullable NBTTagCompound nbt, @Nullable Block replaceOnly, Tally tally) {
        if (replaceOnly != null && world.getBlockState(pos).getBlock() != replaceOnly) {
            tally.skipped++;
            return;
        }
        if (world.setBlockState(pos, state, 3)) {
            tally.written++;
        }
        if (nbt != null) {
            TileEntityNbt.Outcome outcome = TileEntityNbt.merge(world, pos, nbt);
            tally.nbt[outcome.ordinal()]++;
            if (outcome == TileEntityNbt.Outcome.NO_TILE_ENTITY && tally.noTileEntityAt.size() < 5) {
                tally.noTileEntityAt.add(GameJson.blockPos(pos));
            }
        }
    }

    /** One game-thread task's share of a bulk write, over indices {@code [from, to)}. */
    private interface Batch {
        void run(int from, int to);
    }

    /**
     * Runs {@code total} writes in game-thread tasks of at most {@code batchSize} each, one after
     * another. Each is its own task, so the tick loop runs between them and a large clear costs
     * several short stalls instead of one long one.
     */
    private static void runInBatches(final com.micatechnologies.minecraft.mcmcp.mcp.ToolContext context,
                                     int total, int batchSize, final Batch batch, Tally tally) {
        for (int from = 0; from < total; from += batchSize) {
            final int start = from;
            final int end = Math.min(total, from + batchSize);
            context.onGameThread(new Callable<Void>() {
                @Override
                public Void call() {
                    batch.run(start, end);
                    return null;
                }
            });
            tally.batches++;
            if (total > batchSize) {
                context.reportProgress(end, total, "Wrote " + end + " of " + total + " blocks");
            }
        }
    }

    /** What a bulk write did. Written on the game thread, read after the last batch. */
    private static final class Tally {
        int written;
        int skipped;
        int batches;
        /** Indexed by {@link TileEntityNbt.Outcome#ordinal()}. */
        final int[] nbt = new int[TileEntityNbt.Outcome.values().length];
        final JsonArray noTileEntityAt = new JsonArray();

        JsonObject toJson(int dimension, int attempted, @Nullable String replaceOnlyId) {
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("attempted", attempted);
            json.addProperty("written", written);
            json.addProperty("skipped", skipped);
            if (replaceOnlyId != null && !replaceOnlyId.isEmpty()) {
                json.addProperty("replaceOnly", replaceOnlyId);
            }
            // written < attempted - skipped means the world rejected some writes: usually the same
            // block was already there, occasionally an unloaded chunk. Reporting it separately from
            // `skipped` is what lets a model tell "my filter excluded these" from "the world refused
            // these".
            json.addProperty("unchanged", attempted - skipped - written);
            if (batches > 1) {
                json.addProperty("batches", batches);
            }
            int nbtTotal = 0;
            for (int count : nbt) {
                nbtTotal += count;
            }
            if (nbtTotal > 0) {
                JsonObject nbtJson = new JsonObject();
                nbtJson.addProperty("applied", nbt[TileEntityNbt.Outcome.APPLIED.ordinal()]);
                nbtJson.addProperty("unchanged", nbt[TileEntityNbt.Outcome.UNCHANGED.ordinal()]);
                nbtJson.addProperty("noTileEntity", nbt[TileEntityNbt.Outcome.NO_TILE_ENTITY.ordinal()]);
                if (noTileEntityAt.size() > 0) {
                    nbtJson.add("noTileEntityAt", noTileEntityAt);
                }
                json.add("nbt", nbtJson);
            }
            return json;
        }
    }

    /**
     * Parses list mode's {@code blocks} array; null if the argument is missing or the wrong shape.
     *
     * @throws IllegalArgumentException when a placement's {@code nbt} does not parse
     */
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
            BlockPos pos = new BlockPos(Json.getInt(item, "x", 0), Json.getInt(item, "y", 0),
                Json.getInt(item, "z", 0));
            NBTTagCompound nbt = null;
            if (item.has("nbt") && !item.get("nbt").isJsonNull()) {
                try {
                    nbt = TileEntityNbt.parse(item.get("nbt"));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("The placement at x=" + pos.getX() + ", y="
                        + pos.getY() + ", z=" + pos.getZ() + ": " + e.getMessage());
                }
            }
            placements.add(new Placement(pos, id, BlockIds.resolve(id),
                Math.max(0, Math.min(15, Json.getInt(item, "metadata", 0))), nbt));
        }
        return placements;
    }

    private static final class Placement {

        final BlockPos pos;
        final String blockId;
        final Block block;
        final int metadata;
        @Nullable
        final NBTTagCompound nbt;

        Placement(BlockPos pos, String blockId, Block block, int metadata, @Nullable NBTTagCompound nbt) {
            this.pos = pos;
            this.blockId = blockId;
            this.block = block;
            this.metadata = metadata;
            this.nbt = nbt;
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
