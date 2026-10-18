package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        ServerUndoTools.register();
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
                .bool("load", "Load the chunks this read needs that are not loaded, and release them "
                    + "afterwards. Default false: cells in unloaded chunks read as mcmcp:unloaded, and the "
                    + "'chunks' block says how many chunks that was. See server_keep_loaded.")
                .enumeration("encoding", "'json' (default) as above. 'packed': palette plus the index array "
                        + "as one short string (run-length, zlib, base64; see 'encoding' in the reply), with no "
                        + "listing cap up to limits.maxPackedReadCells. Use it for anything large.",
                    "json", "packed")
                .enumeration("mode", "'blocks' (default) reads every cell. 'surface' reads each column's top "
                        + "block between y and toY that is not in 'ignore', returning palette indices and heights, "
                        + "both packed. The cheapest way to survey a large area.",
                    "blocks", "surface")
                .array("ignore", "surface mode: blocks to see through, e.g. ['minecraft:leaves', "
                    + "'minecraft:snow_layer']. Air is always ignored.", com.micatechnologies.minecraft.mcmcp.json.Json.obj("type", "string"))
                .required("x", "y", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                if (ServerRegionTools.handles(context)) {
                    return ServerRegionTools.read(context);
                }
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
                final Set<Long> chunkSet = ChunkKeys.box(minX, minZ, maxX, maxZ);

                return ServerChunkTools.withChunks(context, dimension, chunkSet, outcome -> {
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
                        // Always present: an empty read over unloaded chunks must never look like
                        // empty ground.
                        json.add("chunks", outcome != null ? outcome.toJson()
                            : ChunkLoadGovernor.describeUnloaded(world, chunkSet));
                        ChunkLoadGovernor.touch(dimension, chunkSet);
                        return json;
                    }
                });
                return ToolResult.structured(result);
                });
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
                + "cleared (fill with minecraft:air) in one call. One request is at most 4 MB, which in "
                + "list mode is about 50,000 placements; palette mode packs far more cells into it. "
                + "'nbt' on a placement, or on a fill, "
                + "merges tile-entity data into each block after it is placed, as /blockdata does; "
                + "read it back with server_get_block nbt=true.\n\n"
                + "Two-part blocks (doors, tall plants, beds, and blocks with a stored 'upper' or 'top' "
                + "flag such as CSM's tall furniture) are written whole: give the lower half "
                + "(or a bed's foot) and the other half is placed with it, before either is updated, "
                + "replacing any different door already there. In list mode 'upper' sets the second "
                + "half's metadata; in any mode an explicit upper half in the cell above is used. "
                + "Neighbour updates are held until each slice is written, so a block several cells "
                + "big (a 2x2 bed) is whole before its cells check each other; cells the world did not "
                + "keep come back as notKept, and dropped items as itemsDropped.\n\n"
                + "Like server_set_block, this writes directly and does not fire block-place events, "
                + "so other mods' protection and machinery hooks do not run. Disabled unless "
                + "permissions.allowWorldEdits is enabled in the MCMCP config.")
            .schema(JsonSchema.object()
                .enumeration("mode", "'fill' writes one block type across a cuboid region. 'list' "
                    + "applies the explicit placements in the blocks argument. 'palette' writes many cells "
                    + "compactly: a palette, then cells as numbers, optionally only where each cell still holds "
                    + "what you expect.", "fill", "list", "palette")
                .property("palette", paletteSchema())
                .property("origin", Json.obj("type", "object"))
                .array("cells", "palette mode: flat numbers, four per cell: dx, dy, dz (from origin), palette "
                    + "index; five with expectPalette, the fifth its index there or -1 for no expectation.",
                    Json.obj("type", "integer"))
                .string("indices", "palette mode, a dense box instead of cells: palette indices packed as "
                    + "server_get_blocks returns them (rle-varint+zlib+base64), x fastest then z then y from origin, "
                    + "with sizeX, sizeY, sizeZ. A palette entry 'mcmcp:skip' leaves its cells alone.")
                .integer("sizeX", "palette mode with indices: box size in x.")
                .integer("sizeY", "palette mode with indices: box size in y.")
                .integer("sizeZ", "palette mode with indices: box size in z.")
                .string("expect", "palette mode: write a cell only if it currently holds this block "
                    + "('mod:block', or 'mod:block:meta' to match the metadata too).")
                .array("expectPalette", "palette mode: per-cell expectations, referred to by the cells' fifth "
                    + "number. A cell whose block differs is a conflict: not written, and listed.",
                    Json.obj("type", "string"))
                .enumeration("onConflict", "palette mode: 'skip' (default) writes the rest; 'abort' writes "
                    + "nothing if any cell conflicts.", "skip", "abort")
                .bool("skipIfTileEntity", "palette mode: leave cells that hold a tile entity (signal heads, "
                    + "controllers, chests). Default false.")
                .bool("neighbourUpdates", "Notify neighbouring blocks, as /setblock and a normal placement "
                    + "do, so a redstone block written beside a machine powers it. Default true, in every mode. "
                    + "false still updates clients and lighting but tells no neighbour, for removing networks "
                    + "(wires, tracks) without each removal recalculating them.")
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
                        .string("block", "Namespaced block id. Omit it, with nbt, to keep the block and "
                            + "metadata already there and only merge the nbt.")
                        .integer("metadata", "Block metadata. Defaults to 0.", 0, 15)
                        .integer("upper", "Two-part blocks (doors, tall plants, beds): the second "
                            + "half's metadata, e.g. 8 + hinge for a door. Omit to derive it.", 0, 15)
                        .property("nbt", nbtSchema("Tile-entity data merged into this block after "
                            + "it is placed."))
                        .required("x", "y", "z")
                        .build())
                .string("replaceOnly", "Only write where the existing block matches this namespaced "
                    + "id. Use 'minecraft:air' to build without destroying anything already there.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .bool("load", "Load the chunks this write touches that are not loaded, and release them "
                    + "afterwards. Default false: cells in unloaded chunks are not written, and are counted "
                    + "as 'unloaded'. A write never generates terrain.")
                .build())
            .serverOnly()
            .destructive()
            .handler(context -> {
                String denied = context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
                    "Direct world edits are disabled by "
                        + "permissions.allowWorldEdits in the MCMCP config. Ask the server operator to "
                        + "enable it, or build through player actions or commands instead.");
                if (denied != null) {
                    return ToolResult.error(denied);
                }

                final int dimension = context.getInt("dimension", 0);
                final String replaceOnlyId = context.getString("replaceOnly", null);
                final int flags = context.getBoolean("neighbourUpdates", true) ? 3 : 2 | 16;
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
                    context.has("blocks") ? "list" : context.has("palette") ? "palette" : "fill");
                if ("palette".equals(mode)) {
                    return ServerBulkTools.paletteWrite(context);
                }

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
                        if (placement.block == null && !placement.keepsBlock()) {
                            return ToolResult.error(
                                BlockIds.describeUnknown(placement.blockId, "place"));
                        }
                        if (placement.upper != null && (placement.block == null
                            || !TwoPartBlocks.canPair(placement.block))) {
                            return ToolResult.error("The placement at x=" + placement.pos.getX() + ", y="
                                + placement.pos.getY() + ", z=" + placement.pos.getZ() + " has 'upper', but "
                                + placement.blockId + " is not a two-part block (door, tall plant or bed).");
                        }
                        if (placement.pos.getY() < 0 || placement.pos.getY() > 255) {
                            return ToolResult.error("y must be between 0 and 255; got "
                                + placement.pos.getY() + " at x=" + placement.pos.getX()
                                + ", z=" + placement.pos.getZ() + ".");
                        }
                    }

                    final Set<Long> listChunks = new java.util.LinkedHashSet<>();
                    for (Placement placement : placements) {
                        listChunks.add(ChunkKeys.ofBlock(placement.pos.getX(), placement.pos.getZ()));
                    }
                    return ServerChunkTools.withChunks(context, dimension, listChunks, outcome -> {
                    final UndoPoints.Recorder recorder = ServerUndoTools.start(context, false);
                    final int[] area = recorder == null ? null : listArea(placements);
                    final BufferedImage beforeImage = ServerUndoTools.drawImage(context, dimension, area);
                    final Tally tally = new Tally();
                    final java.util.Map<Long, IBlockState> twoPart = new java.util.HashMap<>();
                    for (Placement placement : placements) {
                        IBlockState state = placement.state();
                        if (state != null && TwoPartBlocks.isTwoPart(state)) {
                            twoPart.put(placement.pos.toLong(), state);
                        }
                    }
                    final TwoPartBlocks.Writer pairs = new TwoPartBlocks.Writer(twoPart, flags);
                    // Neighbour updates held to the end of each slice, so a block several cells big is whole
                    // before any of its cells hears of a neighbour (#85).
                    final DeferredNeighbours deferred = (flags & 1) != 0 ? new DeferredNeighbours() : null;
                    pairs.deferred = deferred;
                    runInBatches(context, placements.size(), batchLimit, new Batch() {
                        @Override
                        public void run(int from, int to) {
                            WorldServer world = ServerWorldTools.requireWorld(dimension);
                            TwoPartBlocks.Recorder undo = TwoPartBlocks.undo(world, recorder);
                            try {
                            for (int i = from; i < to; i++) {
                                Placement placement = placements.get(i);
                                IBlockState state;
                                try {
                                    state = placement.state();
                                }
                                catch (RuntimeException e) {
                                    tally.refused(placement.pos, placement.blockId, "metadata " + placement.metadata
                                        + " is not a valid state: " + e.getMessage());
                                    continue;
                                }
                                try {
                                    if (state != null && twoPart.containsKey(placement.pos.toLong())
                                        && placePair(world, placement, state, replaceOnly, pairs, undo, tally)) {
                                        continue;
                                    }
                                    place(world, placement.pos, state, placement.nbt, replaceOnly, flags, tally, recorder,
                                        deferred);
                                }
                                catch (RuntimeException e) {
                                    // A mod hook threw (#81): this cell is refused, the rest still go in.
                                    tally.refused(placement.pos, placement.blockId,
                                        BlockWriteFaults.reason(e, placement.pos, placement.blockId));
                                }
                            }
                            }
                            finally {
                                if (deferred != null) {
                                    deferred.flush(world);
                                }
                            }
                        }
                    }, tally);
                    tally.partners = pairs.partnersWritten;
                    tally.oldHalves = pairs.oldPartnersCleared;
                    JsonObject json = tally.toJson(dimension, placements.size(), replaceOnlyId);
                    if (deferred != null) {
                        deferred.report(json);
                    }
                    json.add("chunks", chunksBlock(context, dimension, listChunks, outcome));
                    if (recorder != null) {
                        ServerUndoTools.finish(context, recorder, "server_set_blocks", dimension, area,
                            beforeImage, json, null);
                    }
                    return ToolResult.structured(json);
                    });
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
                final Set<Long> fillChunks = ChunkKeys.box(minX, minZ, maxX, maxZ);
                return ServerChunkTools.withChunks(context, dimension, fillChunks, outcome -> {
                final UndoPoints.Recorder recorder = ServerUndoTools.start(context, false);
                final int[] area = recorder == null ? null : UndoPoints.imageArea(minX, minZ, maxX, maxZ);
                final BufferedImage beforeImage = ServerUndoTools.drawImage(context, dimension, area);
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
                            BlockPos pos = new BlockPos(x, y, z);
                            try {
                                place(world, pos, state, fillNbt, replaceOnly, flags, tally, recorder, null);
                            }
                            catch (RuntimeException e) {
                                tally.refused(pos, blockId, BlockWriteFaults.reason(e, pos, blockId));
                            }
                        }
                    }
                }, tally);
                JsonObject json = tally.toJson(dimension, (int) volume, replaceOnlyId);
                json.addProperty("block", blockId);
                json.add("from", GameJson.blockPos(new BlockPos(minX, minY, minZ)));
                json.add("to", GameJson.blockPos(new BlockPos(maxX, maxY, maxZ)));
                json.add("chunks", chunksBlock(context, dimension, fillChunks, outcome));
                if (recorder != null) {
                    ServerUndoTools.finish(context, recorder, "server_set_blocks", dimension, area,
                        beforeImage, json, null);
                }
                return ToolResult.structured(json);
                });
            })
            .build());
    }

    /** The {@code chunks} block of a write: what loading cost, or what was not loaded. */
    private static JsonObject chunksBlock(com.micatechnologies.minecraft.mcmcp.mcp.ToolContext context,
                                          final int dimension, final Set<Long> chunks,
                                          @Nullable final ChunkLoadGovernor.Outcome outcome) {
        return context.onGameThread(() -> {
            ChunkLoadGovernor.touch(dimension, chunks);
            return outcome != null ? outcome.toJson()
                : ChunkLoadGovernor.describeUnloaded(ServerWorldTools.requireWorld(dimension), chunks);
        });
    }

    /** The palette of a palette-mode write: block ids, or objects with block, metadata and nbt. */
    private static JsonObject paletteSchema() {
        JsonObject item = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("object");
        item.add("type", types);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.add("items", item);
        schema.addProperty("description", "palette mode: the blocks cells refer to by index. Each 'mod:block', "
            + "'mod:block:meta', 'mod:block meta', or {block, metadata, nbt} to set tile-entity data on every cell "
            + "using that entry (one entry per distinct NBT). 'mcmcp:skip' writes nothing.");
        return schema;
    }

    /** An {@code nbt} property: an SNBT string or a JSON object. */
    public static JsonObject nbtSchema(String description) {
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

    /**
     * Places one block and merges its tile-entity data, recording what it replaced when a recorder is
     * given. Game thread only.
     */
    /** Writes one cell; a null {@code state} keeps the block there and only merges {@code nbt}. */
    /**
     * Writes a two-part placement through {@link TwoPartBlocks}.
     *
     * @return false when the cell is not two-part after all and should be written as usual
     */
    private static boolean placePair(WorldServer world, Placement placement, IBlockState state,
                                     @Nullable Block replaceOnly, TwoPartBlocks.Writer pairs,
                                     @Nullable TwoPartBlocks.Recorder undo, Tally tally) {
        BlockPos pos = placement.pos;
        if (!world.isBlockLoaded(pos)) {
            tally.unloaded++;
            return true;
        }
        if (replaceOnly != null && world.getBlockState(pos).getBlock() != replaceOnly) {
            tally.skipped++;
            return true;
        }
        IBlockState partner = placement.upper == null ? null : placement.block.getStateFromMeta(placement.upper);
        switch (pairs.write(world, pos, state, partner, undo)) {
            case NOT_TWO_PART:
                return false;
            case WRITTEN:
                tally.written++;
                break;
            case UNCHANGED:
                tally.unchanged++;
                break;
            case DONE_BY_PARTNER:
                tally.byPartner++;
                break;
            case FAILED:
                tally.refused(pos, placement.blockId, "the pair could not be placed: its other cell is "
                    + "unloaded, outside the world, or the world did not accept the state");
                break;
            default:
                break;
        }
        if (placement.nbt != null) {
            TileEntityNbt.Outcome outcome = TileEntityNbt.merge(world, pos, placement.nbt);
            tally.nbt[outcome.ordinal()]++;
        }
        return true;
    }

    private static void place(WorldServer world, BlockPos pos, @Nullable IBlockState state,
                              @Nullable NBTTagCompound nbt, @Nullable Block replaceOnly, int flags, Tally tally,
                              @Nullable UndoPoints.Recorder recorder, @Nullable DeferredNeighbours deferred) {
        if (!world.isBlockLoaded(pos)) {
            // Writing would load the chunk, or generate it if it never was; neither is this call's to
            // decide. Counted, so the caller can hold the region or pass load: true.
            tally.unloaded++;
            return;
        }
        if (replaceOnly != null && world.getBlockState(pos).getBlock() != replaceOnly) {
            tally.skipped++;
            return;
        }
        UndoPoints.Before was = recorder == null ? null : recorder.capture(world, pos);
        IBlockState actual = world.getBlockState(pos);
        boolean changed = state != null && actual != state && BlockWriteFaults.set(world, pos, state, flags, recorder, was, deferred);
        if (state != null && actual != state && !changed) {
            ResourceLocation name = state.getBlock().getRegistryName();
            tally.refused(pos, name == null ? "unknown" : name.toString(), "the world did not accept the state");
            return;
        }
        if (nbt != null) {
            TileEntityNbt.Outcome outcome = TileEntityNbt.merge(world, pos, nbt);
            changed |= outcome == TileEntityNbt.Outcome.APPLIED;
            tally.nbt[outcome.ordinal()]++;
            if (outcome == TileEntityNbt.Outcome.NO_TILE_ENTITY && tally.noTileEntityAt.size() < 5) {
                tally.noTileEntityAt.add(GameJson.blockPos(pos));
            }
        }
        if (changed) {
            tally.written++;
        }
        else {
            tally.unchanged++;
        }
        if (recorder != null && changed) {
            recorder.record(pos, was, world.getBlockState(pos));
        }
    }

    /** The map area around a list of placements, or null when they are too spread out to draw. */
    @Nullable
    private static int[] listArea(List<Placement> placements) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Placement placement : placements) {
            minX = Math.min(minX, placement.pos.getX());
            minZ = Math.min(minZ, placement.pos.getZ());
            maxX = Math.max(maxX, placement.pos.getX());
            maxZ = Math.max(maxZ, placement.pos.getZ());
        }
        return UndoPoints.imageArea(minX, minZ, maxX, maxZ);
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
        int unloaded;
        int batches;
        /** Second halves of a pair written by their first half's placement. */
        int byPartner;
        /** Second halves placed although the write did not list them. */
        int partners;
        /** Other doors' halves removed so a new pair could go in. */
        int oldHalves;
        int unchanged;
        int refusedCount;
        final JsonArray refusedList = new JsonArray();

        void refused(BlockPos pos, @Nullable String id, String reason) {
            refusedCount++;
            if (refusedList.size() < 20) {
                JsonObject item = GameJson.blockPos(pos);
                item.addProperty("block", id);
                item.addProperty("reason", reason);
                refusedList.add(item);
            }
        }
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
            // `skipped` is what a filter excluded, `unchanged` what already held the block, `refused`
            // what the world would not take. Kept apart, they tell a model which of the three stopped it.
            // Every cell lands in exactly one of these, so they always add up to attempted (#80).
            json.addProperty("unchanged", unchanged);
            json.addProperty("refused", refusedCount);
            if (byPartner + partners + oldHalves > 0) {
                JsonObject pairs = new JsonObject();
                pairs.addProperty("byPartner", byPartner);
                pairs.addProperty("partnersAdded", partners);
                pairs.addProperty("oldHalvesCleared", oldHalves);
                json.add("pairs", pairs);
            }
            if (refusedList.size() > 0) {
                json.add("refusedAt", refusedList);
            }
            if (unloaded > 0) {
                json.addProperty("unloaded", unloaded);
            }
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
            BlockPos pos = new BlockPos(Json.getInt(item, "x", 0), Json.getInt(item, "y", 0),
                Json.getInt(item, "z", 0));
            boolean keep = id == null || id.isEmpty();
            if (keep && (!item.has("nbt") || item.get("nbt").isJsonNull())) {
                // Skipped silently before, which reads as written to whoever left the block out.
                throw new IllegalArgumentException("The placement at x=" + pos.getX() + ", y=" + pos.getY()
                    + ", z=" + pos.getZ() + " has no 'block'. Give one, or give 'nbt' alone to merge it into "
                    + "the block already there.");
            }
            NBTTagCompound nbt = null;
            if (item.has("nbt") && !item.get("nbt").isJsonNull()) {
                try {
                    nbt = TileEntityNbt.parse(item.get("nbt"));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("The placement at x=" + pos.getX() + ", y="
                        + pos.getY() + ", z=" + pos.getZ() + ": " + e.getMessage());
                }
            }
            Integer upper = item.has("upper") && !item.get("upper").isJsonNull()
                ? Math.max(0, Math.min(15, Json.getInt(item, "upper", 0))) : null;
            placements.add(new Placement(pos, keep ? null : id, keep ? null : BlockIds.resolve(id),
                Math.max(0, Math.min(15, Json.getInt(item, "metadata", 0))), nbt, upper));
        }
        return placements;
    }

    private static final class Placement {

        final BlockPos pos;
        /** Null for an nbt-only placement, which keeps the block there. */
        @Nullable
        final String blockId;
        @Nullable
        final Block block;
        final int metadata;
        @Nullable
        final NBTTagCompound nbt;
        /** A two-part block's second-half metadata, given rather than derived. */
        @Nullable
        final Integer upper;

        Placement(BlockPos pos, @Nullable String blockId, @Nullable Block block, int metadata,
                  @Nullable NBTTagCompound nbt, @Nullable Integer upper) {
            this.pos = pos;
            this.blockId = blockId;
            this.block = block;
            this.metadata = metadata;
            this.nbt = nbt;
            this.upper = upper;
        }

        @Nullable
        IBlockState state() {
            return block == null ? null : block.getStateFromMeta(metadata);
        }

        boolean keepsBlock() {
            return blockId == null;
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
