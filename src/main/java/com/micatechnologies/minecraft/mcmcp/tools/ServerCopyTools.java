package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.CellOverlay;
import com.micatechnologies.minecraft.mcmcp.regions.QuarterTurns;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Copies a box of the world somewhere else, turned by quarter turns: block states, two-part blocks and
 * tile entities with the positions inside their NBT (#191).
 *
 * <p>An approved apartment model faces north and the next five face south, east and west. The copy was
 * being made from outside the game: a palette read and paged tile-entity reads, states worked out from
 * metadata tables, coordinates rewritten in SNBT by regular expression, then palette writes, a door
 * pass and an NBT pass, about ten calls a part and a check afterwards. Here the cells never leave the
 * server.
 *
 * <p>The source is read into a {@link CellOverlay} already turned, and from there it is
 * {@code server_apply_ops}' own write: diffed against what stands at the destination, two-part blocks
 * placed whole, an undo point, drops discarded, in game-thread slices. Tile entities follow once every
 * block is in place, as vanilla's structure blocks do it: the source's data set on the new tile
 * entity, then its own {@code rotate} hook.
 */
public final class ServerCopyTools {

    private static final long SLICE_NANOS = 25_000_000L;
    private static final int LISTED = 20;

    private ServerCopyTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_copy_structure")
            .title("Copy a structure, turned")
            .description("Copy the box x,y,z..toX,toY,toZ so that the copy's minimum corner is at 'to', turned "
                + "'rotate' degrees clockwise seen from above (0, 90, 180, 270): north-facing becomes "
                + "east-facing at 90. Block states are turned by the game's own rotation, and by their property "
                + "names (facing, axis, north/east/south/west...) for a mod block that has none; doors, beds and "
                + "other two-part blocks land whole; tile entities are copied with their data, and block "
                + "positions inside that data which point into the box (links, lists of {x,y,z}, packed "
                + "positions) are moved with it. Only cells that differ at the destination are written, with an "
                + "undo point. dryRun reports without writing. The reply lists what could not be turned: "
                + "'unturned' block ids whose state names a direction with nowhere to go, 'nbt.directionKeys' "
                + "for a direction kept in a tile entity, 'nbt.outside' for positions pointing outside the box "
                + "(left as they were), and entities, which are not copied. A direction kept in a block's id, or "
                + "in a chiselled block's shape, is not turned. Needs permissions.allowWorldEdits.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .integer("x", "X of one corner of the source box.")
                .integer("y", "Y of one corner.", 0, 255)
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner.")
                .integer("toY", "Y of the opposite corner.", 0, 255)
                .integer("toZ", "Z of the opposite corner.")
                .array("to", "[x, y, z]: where the copy's minimum corner goes. The reply's 'destination' is "
                    + "the box it fills; at 90 and 270 its x and z sizes are the source's swapped.",
                    Json.obj("type", "integer"))
                .integer("rotate", "Degrees clockwise seen from above: 0 (default), 90, 180 or 270.", 0, 270)
                .integer("dimension", "Dimension id. Default 0.")
                .bool("skipAir", "Leave the destination as it is where the source is air. Default false: the "
                    + "copy is exact, air included.")
                .bool("tileEntities", "Copy tile-entity data. Default true.")
                .enumeration("expect", "'air': write nothing if a destination cell that would change holds a "
                    + "block. Default 'any'.", "any", "air")
                .bool("dryRun", "Read, turn and diff; write nothing.")
                .bool("neighbourUpdates", "Notify neighbouring blocks. Default false: the copy is already "
                    + "complete."))
                .required("x", "y", "z", "toX", "toY", "toZ", "to")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerCopyTools::copy)
            .build());
    }

    /** What reading the source found, besides the cells. Game thread while reading; read after. */
    private static final class Survey {
        long cells;
        long air;
        long turned;
        long unloaded;
        int tileEntities;
        int tileEntityFailures;
        /** Block ids with a direction in their state that could not be turned, with how many cells. */
        final Map<String, Integer> unturned = new TreeMap<>();
        final JsonArray unturnedAt = new JsonArray();
        /** Chiselled blocks, whose shape is in their NBT and is copied as it is. */
        int chiselled;
        final NbtPositions.Report nbt = new NbtPositions.Report();
    }

    private static ToolResult copy(final ToolContext context) throws Exception {
        String denied = context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
            "Direct world edits are disabled by permissions.allowWorldEdits in the MCMCP config.");
        if (denied != null) {
            return ToolResult.error(denied);
        }
        List<String> toList = Json.getStringList(context.getArguments(), "to");
        final int[] to = new int[3];
        try {
            if (toList.size() != 3) {
                throw new NumberFormatException();
            }
            for (int i = 0; i < 3; i++) {
                to[i] = (int) Math.round(Double.parseDouble(toList.get(i).trim()));
            }
        }
        catch (NumberFormatException e) {
            return ToolResult.error("'to' is three whole numbers, [x, y, z]: the copy's minimum corner.");
        }
        final int turns;
        try {
            turns = QuarterTurns.ofDegrees(context.getInt("rotate", 0));
        }
        catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        final QuarterTurns.Box box = new QuarterTurns.Box(context.requireInt("x"), context.requireInt("y"),
            context.requireInt("z"), context.requireInt("toX"), context.requireInt("toY"), context.requireInt("toZ"),
            turns, to[0], to[1], to[2]);
        final int[] destination = box.destination();
        if (box.minY < 0 || box.maxY > 255 || destination[1] < 0 || destination[4] > 255) {
            return ToolResult.error("y must be 0-255, for the source box and for the copy (which would reach y "
                + destination[4] + ").");
        }
        long cap = (long) McmcpConfig.getMaxWriteCells() * 4L;
        if (box.cells() > cap) {
            return ToolResult.error("The box is " + box.cells() + " cells; one call copies at most " + cap
                + " (4 x limits.maxWriteCells). Copy it in parts.");
        }
        final int dimension = context.getInt("dimension", 0);
        final boolean dryRun = context.getBoolean("dryRun", false);
        final boolean skipAir = context.getBoolean("skipAir", false);
        final boolean withTileEntities = context.getBoolean("tileEntities", true);
        final boolean expectAir = "air".equals(context.getString("expect", "any"));
        final int flags = context.getBoolean("neighbourUpdates", false) ? 3 : 2 | 16;
        if (!dryRun) {
            ToolResult overBudget = ServerBulkTools.chargeWrites(context, box.cells());
            if (overBudget != null) {
                return overBudget;
            }
        }
        final Set<Long> chunks = new LinkedHashSet<>(ChunkKeys.box(box.minX, box.minZ, box.maxX, box.maxZ));
        chunks.addAll(ChunkKeys.box(destination[0], destination[2], destination[3], destination[5]));

        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final CellOverlay overlay = new CellOverlay();
            final ServerApplyOps.StatePalette palette = new ServerApplyOps.StatePalette();
            final Map<Long, NBTTagCompound> tags = new LinkedHashMap<>();
            final Survey survey = new Survey();
            read(context, dimension, box, skipAir, withTileEntities, overlay, palette, tags, survey);
            if (survey.unloaded > 0) {
                return ToolResult.error(survey.unloaded + " cell(s) of the source box are in chunks that are not "
                    + "loaded, so the copy would have holes. Nothing was written. Pass load: true, or hold the "
                    + "region with server_keep_loaded.");
            }

            if (expectAir) {
                JsonObject inTheWay = occupied(context, dimension, overlay, palette);
                if (inTheWay.get("cells").getAsLong() > 0) {
                    return ToolResult.error("expect 'air': " + inTheWay.get("cells").getAsLong() + " cell(s) at the "
                        + "destination that the copy would change already hold a block. Nothing was written.")
                        .withStructured(Json.obj("occupied", inTheWay));
                }
            }

            final ServerApplyOps.Tally tally = new ServerApplyOps.Tally();
            final UndoPoints.Recorder recorder = dryRun ? null : ServerUndoTools.start(context, false);
            final int[] area = recorder == null ? null
                : UndoPoints.imageArea(destination[0], destination[2], destination[3], destination[5]);
            final BufferedImage beforeImage = recorder == null ? null : ServerUndoTools.drawImage(context, dimension, area);
            TwoPartBlocks.Writer pairs = ServerApplyOps.write(context, dimension, overlay, palette,
                Collections.<Long>emptySet(), flags, dryRun, tally, recorder, null);
            final int[] hooks = {0, 0};
            if (!dryRun) {
                placeTileEntities(context, dimension, tags, StateRotation.rotation(turns), tally, hooks);
            }

            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("dryRun", dryRun);
            json.addProperty("rotate", turns * 90);
            JsonObject source = new JsonObject();
            source.add("from", GameJson.blockPos(new BlockPos(box.minX, box.minY, box.minZ)));
            source.add("to", GameJson.blockPos(new BlockPos(box.maxX, box.maxY, box.maxZ)));
            json.add("source", source);
            JsonObject target = new JsonObject();
            target.add("from", GameJson.blockPos(new BlockPos(destination[0], destination[1], destination[2])));
            target.add("to", GameJson.blockPos(new BlockPos(destination[3], destination[4], destination[5])));
            json.add("destination", target);
            json.addProperty("cells", survey.cells);
            if (skipAir) {
                json.addProperty("airSkipped", survey.air);
            }
            json.addProperty(dryRun ? "wouldWrite" : "written", tally.written);
            json.addProperty("unchanged", tally.unchanged);
            json.addProperty("unloaded", tally.unloaded);
            json.addProperty("refused", tally.refused);
            if (tally.byPartner > 0) {
                json.addProperty("byPartner", tally.byPartner);
            }
            pairs.report(json);
            if (turns != 0) {
                json.addProperty("turned", survey.turned);
            }
            if (!survey.unturned.isEmpty()) {
                JsonObject unturned = new JsonObject();
                for (Map.Entry<String, Integer> entry : survey.unturned.entrySet()) {
                    unturned.addProperty(entry.getKey(), entry.getValue());
                }
                json.add("unturned", unturned);
                json.add("unturnedAt", survey.unturnedAt);
                json.addProperty("unturnedNote", "These blocks' states name a direction that has no value for where "
                    + "the turn points it, so they were copied facing as they did. unturnedAt lists where the "
                    + "first of them landed.");
            }
            if (survey.chiselled > 0 && turns != 0) {
                json.addProperty("chiselledNotTurned", survey.chiselled);
            }
            if (survey.tileEntities > 0 || survey.tileEntityFailures > 0) {
                JsonObject nbt = survey.nbt.toJson();
                nbt.addProperty("tileEntities", survey.tileEntities);
                if (survey.tileEntityFailures > 0) {
                    nbt.addProperty("unreadable", survey.tileEntityFailures);
                }
                if (!dryRun) {
                    nbt.addProperty("applied", tally.nbtApplied);
                    nbt.addProperty("unchanged", tally.nbtUnchanged);
                    if (tally.nbtNoTileEntity > 0) {
                        nbt.addProperty("noTileEntity", tally.nbtNoTileEntity);
                    }
                    if (hooks[1] > 0) {
                        nbt.addProperty("rotateHookFailed", hooks[1]);
                    }
                }
                json.add("nbt", nbt);
            }
            if (tally.refusedAt.size() > 0) {
                json.add("refusedAt", tally.refusedAt);
            }
            if (dryRun) {
                json.add("sample", tally.sample);
            }
            // Follow-ups: the write has happened, and dropping these would fail the call before its undo
            // point is saved.
            int entities = context.onGameThreadFollowUp(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                int count = 0;
                for (Entity entity : world.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(box.minX, box.minY,
                    box.minZ, box.maxX + 1.0D, box.maxY + 1.0D, box.maxZ + 1.0D))) {
                    if (!(entity instanceof EntityPlayer)) {
                        count++;
                    }
                }
                return count;
            });
            if (entities > 0) {
                json.addProperty("entitiesNotCopied", entities);
            }
            json.add("chunks", context.onGameThreadFollowUp(() -> {
                ChunkLoadGovernor.touch(dimension, chunks);
                return outcome != null ? outcome.toJson()
                    : ChunkLoadGovernor.describeUnloaded(ServerWorldTools.requireWorld(dimension), chunks);
            }));
            if (recorder != null) {
                ServerUndoTools.finish(context, recorder, "server_copy_structure", dimension, area, beforeImage, json,
                    null);
            }
            return ToolResult.structured(json);
        });
    }

    /**
     * Reads the source into the overlay, each cell already where it goes and turned, and each tile
     * entity's data with its positions moved. The whole source is read before anything is written, so
     * a destination that overlaps it copies what was there, not what the copy has just put there.
     */
    private static void read(ToolContext context, int dimension, QuarterTurns.Box box, boolean skipAir,
                             boolean withTileEntities, CellOverlay overlay, ServerApplyOps.StatePalette palette,
                             Map<Long, NBTTagCompound> tags, Survey survey) {
        final long total = box.cells();
        final long[] cursor = {0L};
        final int sizeX = box.sizeX();
        final int sizeZ = box.sizeZ();
        final Map<IBlockState, StateRotation.Turned> turnedStates = new java.util.IdentityHashMap<>();
        while (cursor[0] < total) {
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long started = System.nanoTime();
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                while (cursor[0] < total) {
                    long i = cursor[0];
                    if ((i & 1023) == 0 && System.nanoTime() - started >= SLICE_NANOS) {
                        break;
                    }
                    cursor[0]++;
                    int x = box.minX + (int) (i % sizeX);
                    int z = box.minZ + (int) ((i / sizeX) % sizeZ);
                    int y = box.minY + (int) (i / ((long) sizeX * sizeZ));
                    pos.setPos(x, y, z);
                    if (!world.isBlockLoaded(pos)) {
                        survey.unloaded++;
                        continue;
                    }
                    IBlockState state = world.getBlockState(pos);
                    survey.cells++;
                    if (state.getMaterial() == Material.AIR) {
                        survey.air++;
                        if (skipAir) {
                            continue;
                        }
                    }
                    StateRotation.Turned turned = turnedStates.get(state);
                    if (turned == null) {
                        turned = StateRotation.turn(state, box.turns);
                        turnedStates.put(state, turned);
                    }
                    int[] at = box.map(x, y, z);
                    overlay.set(at[0], at[1], at[2], palette.value(turned.state));
                    if (turned.state != state) {
                        survey.turned++;
                    }
                    ResourceLocation id = state.getBlock().getRegistryName();
                    if (turned.stuck) {
                        survey.unturned.merge(String.valueOf(id), 1, Integer::sum);
                        if (survey.unturnedAt.size() < LISTED) {
                            survey.unturnedAt.add(GameJson.blockPos(new BlockPos(at[0], at[1], at[2])));
                        }
                    }
                    if (id != null && "chiselsandbits".equals(id.getNamespace())) {
                        survey.chiselled++;
                    }
                    if (!withTileEntities || !state.getBlock().hasTileEntity(state)) {
                        continue;
                    }
                    TileEntity tileEntity = world.getTileEntity(pos);
                    if (tileEntity == null) {
                        continue;
                    }
                    try {
                        NBTTagCompound tag = tileEntity.writeToNBT(new NBTTagCompound());
                        NbtPositions.move(tag, box, survey.nbt);
                        tags.put(new BlockPos(at[0], at[1], at[2]).toLong(), tag);
                        survey.tileEntities++;
                    }
                    catch (RuntimeException e) {
                        // A mod's tile entity that cannot be saved: its block is copied, its data is not.
                        survey.tileEntityFailures++;
                    }
                }
                return null;
            });
            context.reportProgress(cursor[0], total * 3.0D, "Read " + cursor[0] + " of " + total + " cells");
        }
    }

    /** The destination cells that would change and hold something other than air: count, and a sample. */
    private static JsonObject occupied(ToolContext context, int dimension, CellOverlay overlay,
                                       ServerApplyOps.StatePalette palette) {
        final long[] count = {0L};
        final JsonArray sample = new JsonArray();
        final List<Long> sections = overlay.sectionsBottomUp();
        final int[] cursor = {0};
        while (cursor[0] < sections.size()) {
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long started = System.nanoTime();
                while (cursor[0] < sections.size() && System.nanoTime() - started < SLICE_NANOS) {
                    overlay.visit(sections.get(cursor[0]++), (x, y, z, value) -> {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!world.isBlockLoaded(pos)) {
                            return;
                        }
                        IBlockState actual = world.getBlockState(pos);
                        if (actual.getMaterial() != Material.AIR && actual != palette.state(value)) {
                            count[0]++;
                            if (sample.size() < LISTED) {
                                JsonArray item = new JsonArray();
                                item.add(x);
                                item.add(y);
                                item.add(z);
                                item.add(ServerRegionTools.describe(Block.getStateId(actual)));
                                sample.add(item);
                            }
                        }
                    });
                }
                return null;
            });
        }
        JsonObject json = new JsonObject();
        json.addProperty("cells", count[0]);
        json.add("sample", sample);
        return json;
    }

    /**
     * Sets each copied tile entity's data on the one now standing at its destination, then gives it
     * the turn through its own {@code rotate} hook, as a structure block's placement does: a skull
     * turns its rotation there, and a mod's tile entity whatever it keeps of its own.
     *
     * <p>Set, not merged: the destination's tile entity is to hold what the source's did, with nothing
     * left over from a block that stood there before.
     */
    private static void placeTileEntities(ToolContext context, int dimension, Map<Long, NBTTagCompound> tags,
                                          Rotation rotation, ServerApplyOps.Tally tally, int[] hooks) {
        if (tags.isEmpty()) {
            return;
        }
        final List<Map.Entry<Long, NBTTagCompound>> entries = new ArrayList<>(tags.entrySet());
        final int[] cursor = {0};
        while (cursor[0] < entries.size()) {
            // An ordinary step, not a follow-up: it writes, so it takes its turn and an emergency stop
            // ends it. A copy stopped here has its blocks and not all their data, and says so in counts.
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long started = System.nanoTime();
                while (cursor[0] < entries.size() && System.nanoTime() - started < SLICE_NANOS) {
                    Map.Entry<Long, NBTTagCompound> entry = entries.get(cursor[0]++);
                    BlockPos pos = BlockPos.fromLong(entry.getKey());
                    TileEntity tileEntity = world.isBlockLoaded(pos) ? world.getTileEntity(pos) : null;
                    if (tileEntity == null) {
                        tally.nbtNoTileEntity++;
                        continue;
                    }
                    try {
                        NBTTagCompound before = tileEntity.writeToNBT(new NBTTagCompound());
                        NBTTagCompound next = entry.getValue().copy();
                        next.setInteger("x", pos.getX());
                        next.setInteger("y", pos.getY());
                        next.setInteger("z", pos.getZ());
                        boolean changed = !next.equals(before);
                        if (changed) {
                            tileEntity.readFromNBT(next);
                        }
                        if (rotation != Rotation.NONE && changed) {
                            try {
                                tileEntity.rotate(rotation);
                                hooks[0]++;
                            }
                            catch (RuntimeException e) {
                                hooks[1]++;
                            }
                        }
                        if (changed) {
                            tileEntity.markDirty();
                            IBlockState state = world.getBlockState(pos);
                            world.notifyBlockUpdate(pos, state, state, 3);
                            tally.nbtApplied++;
                        }
                        else {
                            tally.nbtUnchanged++;
                        }
                    }
                    catch (RuntimeException e) {
                        tally.refuse(pos, "its tile entity would not take the copied data: " + e);
                    }
                }
                return null;
            });
            context.reportProgress(entries.size() * 2.0D + cursor[0], entries.size() * 3.0D, "Set " + cursor[0]
                + " of " + entries.size() + " tile entities");
        }
    }
}
