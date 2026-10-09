package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.chunkload.LoadBudget;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.PackedIndices;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Writing at the scale of a district (#55), and running many commands at once.
 *
 * <p>{@code server_set_blocks} with {@code mode: "palette"} lands here: a palette and a flat cell list
 * (or a packed box), any size up to {@code limits.maxWriteCells}, applied in game-thread batches, with
 * an optional expected block per cell so a generator never overwrites a cell a person changed since it
 * last read it.
 *
 * <p>{@code server_run_commands} runs a list of commands as one call with a structured result per
 * command and no chat in between, replacing scripts that teleport a player around a region and send
 * commands through chat in groups of 250.
 */
public final class ServerBulkTools {

    /** A palette entry that leaves its cells as they are, for packed boxes with holes. */
    static final String SKIP = "mcmcp:skip";

    /** At most this many conflicts, failures or command results are listed in a reply. */
    private static final int MAX_LISTED = 50;

    /** Cells written per minute, per caller. */
    private static final LoadBudget WRITE_BUDGET = new LoadBudget();

    private ServerBulkTools() {
    }

    public static void register() {
        registerRunCommands();
    }

    // ------------------------------------------------------------------
    // Palette writes
    // ------------------------------------------------------------------

    /** One palette entry: the state to write, its NBT, or skip. */
    static final class Entry {

        @Nullable
        final IBlockState state;
        @Nullable
        final NBTTagCompound nbt;
        final String id;

        Entry(@Nullable IBlockState state, @Nullable NBTTagCompound nbt, String id) {
            this.state = state;
            this.nbt = nbt;
            this.id = id;
        }
    }

    /** An expected block: a block, and a metadata value or -1 for any. */
    static final class Expected {

        final Block block;
        final int meta;
        final String id;

        Expected(Block block, int meta, String id) {
            this.block = block;
            this.meta = meta;
            this.id = id;
        }

        boolean matches(IBlockState state) {
            return state.getBlock() == block && (meta < 0 || state.getBlock().getMetaFromState(state) == meta);
        }
    }

    /**
     * Splits {@code "mod:block"}, {@code "mod:block:3"} or {@code "mod:block 3"} into id and metadata
     * (-1 when none is given).
     */
    static Object[] splitId(String text) {
        String trimmed = text.trim();
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            return new Object[] {trimmed.substring(0, space), Integer.parseInt(trimmed.substring(space + 1).trim())};
        }
        String[] parts = trimmed.split(":");
        if (parts.length == 3 && parts[2].matches("\\d+")) {
            return new Object[] {parts[0] + ":" + parts[1], Integer.parseInt(parts[2])};
        }
        return new Object[] {trimmed, -1};
    }

    @SuppressWarnings("deprecation")
    private static Entry parseEntry(JsonElement element) {
        String text;
        Integer meta = null;
        NBTTagCompound nbt = null;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            text = Json.getString(object, "block", "");
            if (object.has("metadata")) {
                meta = Json.getInt(object, "metadata", 0);
            }
            if (object.has("nbt")) {
                nbt = TileEntityNbt.parse(object.get("nbt"));
            }
        }
        else {
            text = element.getAsString();
        }
        if (SKIP.equals(text.trim())) {
            return new Entry(null, null, SKIP);
        }
        Object[] split = splitId(text);
        Block block = BlockIds.resolve((String) split[0]);
        if (block == null) {
            throw new IllegalArgumentException(BlockIds.describeUnknown((String) split[0], "place"));
        }
        int metadata = meta != null ? meta : Math.max(0, (Integer) split[1]);
        return new Entry(block.getStateFromMeta(metadata), nbt, text.trim());
    }

    private static Expected parseExpected(String text) {
        Object[] split = splitId(text);
        Block block = BlockIds.resolve((String) split[0]);
        if (block == null) {
            throw new IllegalArgumentException(BlockIds.describeUnknown((String) split[0], "expect"));
        }
        return new Expected(block, (Integer) split[1], text.trim());
    }

    /** {@code server_set_blocks} in palette mode. */
    static ToolResult paletteWrite(final ToolContext context) throws Exception {
        final int dimension = context.getInt("dimension", 0);
        JsonObject arguments = context.getArguments();
        JsonArray paletteJson = Json.getArray(arguments, "palette");
        if (paletteJson == null || paletteJson.size() == 0) {
            return ToolResult.error("Palette mode needs 'palette': the blocks the cells refer to by index.");
        }
        final List<Entry> palette = new ArrayList<>();
        final List<Expected> expectPalette = new ArrayList<>();
        final Expected expectAll;
        try {
            for (JsonElement element : paletteJson) {
                palette.add(parseEntry(element));
            }
            for (String text : Json.getStringList(arguments, "expectPalette")) {
                expectPalette.add(parseExpected(text));
            }
            String all = Json.getString(arguments, "expect");
            expectAll = all == null ? null : parseExpected(all);
        }
        catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        // Cells: a flat list, or a dense box with packed indices.
        final int[] xs;
        final int[] ys;
        final int[] zs;
        final int[] values;
        final int[] expects;
        JsonObject origin = Json.getObjectOrEmpty(arguments, "origin");
        int ox = Json.getInt(origin, "x", 0);
        int oy = Json.getInt(origin, "y", 0);
        int oz = Json.getInt(origin, "z", 0);
        if (arguments.has("indices")) {
            int sizeX = Json.getInt(arguments, "sizeX", 0);
            int sizeY = Json.getInt(arguments, "sizeY", 0);
            int sizeZ = Json.getInt(arguments, "sizeZ", 0);
            long count = (long) sizeX * sizeY * sizeZ;
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || count > McmcpConfig.getMaxWriteCells()) {
                return ToolResult.error("A packed write needs sizeX, sizeY and sizeZ, at most "
                    + McmcpConfig.getMaxWriteCells() + " cells (limits.maxWriteCells).");
            }
            int[] decoded;
            try {
                decoded = PackedIndices.decode(Json.getString(arguments, "indices", ""), (int) count);
            }
            catch (RuntimeException e) {
                return ToolResult.error("'indices' is not a packed array of " + count + " values: " + e.getMessage());
            }
            xs = new int[(int) count];
            ys = new int[(int) count];
            zs = new int[(int) count];
            values = decoded;
            expects = null;
            for (int i = 0; i < count; i++) {
                xs[i] = ox + i % sizeX;
                zs[i] = oz + (i / sizeX) % sizeZ;
                ys[i] = oy + i / (sizeX * sizeZ);
            }
        }
        else {
            JsonArray cells = Json.getArray(arguments, "cells");
            if (cells == null) {
                return ToolResult.error("Palette mode needs 'cells' ([dx, dy, dz, index, ...] relative to origin) "
                    + "or 'indices' with sizeX, sizeY and sizeZ for a dense box.");
            }
            int stride = expectPalette.isEmpty() ? 4 : 5;
            if (cells.size() % stride != 0) {
                return ToolResult.error("'cells' must be groups of " + stride + " numbers: dx, dy, dz, palette index"
                    + (stride == 5 ? ", expectPalette index (-1 for none)" : "") + ". Got " + cells.size() + " numbers.");
            }
            int count = cells.size() / stride;
            if (count > McmcpConfig.getMaxWriteCells()) {
                return ToolResult.error(count + " cells is over the " + McmcpConfig.getMaxWriteCells()
                    + " one call writes (limits.maxWriteCells). Split it.");
            }
            xs = new int[count];
            ys = new int[count];
            zs = new int[count];
            values = new int[count];
            expects = stride == 5 ? new int[count] : null;
            for (int i = 0; i < count; i++) {
                xs[i] = ox + cells.get(i * stride).getAsInt();
                ys[i] = oy + cells.get(i * stride + 1).getAsInt();
                zs[i] = oz + cells.get(i * stride + 2).getAsInt();
                values[i] = cells.get(i * stride + 3).getAsInt();
                if (expects != null) {
                    expects[i] = cells.get(i * stride + 4).getAsInt();
                }
            }
        }
        final int count = values.length;
        for (int i = 0; i < count; i++) {
            if (values[i] < 0 || values[i] >= palette.size()) {
                return ToolResult.error("Cell " + i + " names palette index " + values[i] + "; the palette has "
                    + palette.size() + " entries.");
            }
            if (expects != null && (expects[i] < -1 || expects[i] >= expectPalette.size())) {
                return ToolResult.error("Cell " + i + " names expectPalette index " + expects[i] + ".");
            }
            if (ys[i] < 0 || ys[i] > 255) {
                return ToolResult.error("Cell " + i + " is at y=" + ys[i] + "; y must be 0-255.");
            }
        }

        // Writes per minute, per caller.
        final String caller = ChunkLoadGovernor.callerOf(context.getPrincipal());
        long now = System.currentTimeMillis();
        int perMinute = McmcpConfig.getWriteCellsPerMinutePerCaller();
        synchronized (WRITE_BUDGET) {
            WRITE_BUDGET.newTick();
            if (WRITE_BUDGET.allowance(caller, count, now, Integer.MAX_VALUE, Integer.MAX_VALUE, perMinute) < count) {
                JsonObject busy = new JsonObject();
                busy.addProperty("reason", "limit");
                busy.addProperty("limit", "limits.writeCellsPerMinutePerCaller");
                busy.addProperty("retryAfterMs", Math.max(1000L,
                    WRITE_BUDGET.millisUntilRoom(caller, now, Integer.MAX_VALUE, perMinute)));
                JsonObject structured = new JsonObject();
                structured.add("busy", busy);
                return ToolResult.error(count + " more cells would pass the " + perMinute
                    + " a caller may write in a minute (limits.writeCellsPerMinutePerCaller).").withStructured(structured);
            }
            WRITE_BUDGET.record(caller, count, now);
        }

        final boolean abortOnConflict = "abort".equals(context.getString("onConflict", "skip"));
        final boolean skipTileEntities = context.getBoolean("skipIfTileEntity", false);
        final int flags = context.getBoolean("neighbourUpdates", true) ? 3 : 2 | 16;
        final Set<Long> chunks = new LinkedHashSet<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            chunks.add(ChunkKeys.ofBlock(xs[i], zs[i]));
            minX = Math.min(minX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxX = Math.max(maxX, xs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        final int[] area = UndoPoints.imageArea(minX, minZ, maxX, maxZ);
        final int batch = McmcpConfig.getMaxBlockVolume();

        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final PaletteTally tally = new PaletteTally();
            if (abortOnConflict && (expectAll != null || expects != null)) {
                for (int from = 0; from < count; from += batch) {
                    final int start = from;
                    final int end = Math.min(count, from + batch);
                    context.onGameThread(() -> {
                        WorldServer world = ServerWorldTools.requireWorld(dimension);
                        for (int i = start; i < end; i++) {
                            Expected expected = expectedFor(i, expectAll, expects, expectPalette);
                            BlockPos pos = new BlockPos(xs[i], ys[i], zs[i]);
                            if (expected != null && world.isBlockLoaded(pos)) {
                                IBlockState actual = world.getBlockState(pos);
                                if (!expected.matches(actual)) {
                                    tally.conflict(pos, expected, actual);
                                }
                            }
                        }
                        return null;
                    });
                }
                if (tally.conflicts > 0) {
                    JsonObject json = tally.toJson(dimension, count);
                    json.addProperty("aborted", true);
                    json.addProperty("note", "onConflict 'abort': nothing was written because " + tally.conflicts
                        + " cell(s) no longer hold what was expected.");
                    return ToolResult.structured(json);
                }
            }

            final UndoPoints.Recorder recorder = ServerUndoTools.start(context, false);
            final BufferedImage beforeImage = recorder == null ? null : ServerUndoTools.drawImage(context, dimension, area);
            for (int from = 0; from < count; from += batch) {
                final int start = from;
                final int end = Math.min(count, from + batch);
                context.onGameThread(() -> {
                    WorldServer world = ServerWorldTools.requireWorld(dimension);
                    for (int i = start; i < end; i++) {
                        Entry entry = palette.get(values[i]);
                        if (entry.state == null) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(xs[i], ys[i], zs[i]);
                        if (!world.isBlockLoaded(pos)) {
                            tally.unloaded++;
                            continue;
                        }
                        IBlockState actual = world.getBlockState(pos);
                        Expected expected = expectedFor(i, expectAll, expects, expectPalette);
                        if (expected != null && !expected.matches(actual)) {
                            tally.conflict(pos, expected, actual);
                            continue;
                        }
                        if (skipTileEntities && world.getTileEntity(pos) != null) {
                            tally.skippedTileEntity++;
                            continue;
                        }
                        if (actual == entry.state && entry.nbt == null) {
                            tally.unchanged++;
                            continue;
                        }
                        UndoPoints.Before was = recorder == null ? null : recorder.capture(world, pos);
                        boolean changed = actual != entry.state && world.setBlockState(pos, entry.state, flags);
                        if (actual != entry.state && !changed) {
                            tally.failed(pos, entry.id);
                            continue;
                        }
                        if (entry.nbt != null) {
                            TileEntityNbt.Outcome merged = TileEntityNbt.merge(world, pos, entry.nbt);
                            changed |= merged == TileEntityNbt.Outcome.APPLIED;
                            if (merged == TileEntityNbt.Outcome.NO_TILE_ENTITY) {
                                tally.noTileEntity++;
                            }
                        }
                        if (changed) {
                            tally.applied++;
                            if (recorder != null) {
                                recorder.record(pos, was, world.getBlockState(pos));
                            }
                        }
                        else {
                            tally.unchanged++;
                        }
                    }
                    return null;
                });
                if (count > batch) {
                    context.reportProgress(end, count, "Wrote " + end + " of " + count + " cells");
                }
            }
            JsonObject json = tally.toJson(dimension, count);
            json.addProperty("neighbourUpdates", flags == 3);
            json.add("chunks", context.onGameThread(() -> {
                ChunkLoadGovernor.touch(dimension, chunks);
                return outcome != null ? outcome.toJson()
                    : ChunkLoadGovernor.describeUnloaded(ServerWorldTools.requireWorld(dimension), chunks);
            }));
            if (recorder != null) {
                ServerUndoTools.finish(context, recorder, "server_set_blocks", dimension, area, beforeImage, json, null);
            }
            return ToolResult.structured(json);
        });
    }

    @Nullable
    private static Expected expectedFor(int i, @Nullable Expected all, @Nullable int[] expects, List<Expected> palette) {
        if (expects != null && expects[i] >= 0) {
            return palette.get(expects[i]);
        }
        return all;
    }

    /** What a palette write did. Written on the game thread, read after the last batch. */
    private static final class PaletteTally {

        int applied;
        int unchanged;
        int conflicts;
        int skippedTileEntity;
        int unloaded;
        int failedCount;
        int noTileEntity;
        final JsonArray conflictList = new JsonArray();
        final JsonArray failedList = new JsonArray();

        void conflict(BlockPos pos, Expected expected, IBlockState actual) {
            conflicts++;
            if (conflictList.size() < MAX_LISTED) {
                JsonObject item = new JsonObject();
                item.add("pos", GameJson.blockPos(pos));
                item.addProperty("expected", expected.id);
                item.addProperty("actual", ServerRegionTools.describe(Block.getStateId(actual)));
                conflictList.add(item);
            }
        }

        void failed(BlockPos pos, String id) {
            failedCount++;
            if (failedList.size() < MAX_LISTED) {
                JsonObject item = new JsonObject();
                item.add("pos", GameJson.blockPos(pos));
                item.addProperty("block", id);
                failedList.add(item);
            }
        }

        JsonObject toJson(int dimension, int cells) {
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("cells", cells);
            json.addProperty("applied", applied);
            json.addProperty("written", applied);
            json.addProperty("unchanged", unchanged);
            json.addProperty("conflicts", conflicts);
            json.addProperty("skippedTileEntity", skippedTileEntity);
            json.addProperty("unloaded", unloaded);
            json.addProperty("failed", failedCount);
            if (noTileEntity > 0) {
                json.addProperty("nbtWithoutTileEntity", noTileEntity);
            }
            if (conflictList.size() > 0) {
                json.add("conflictList", conflictList);
            }
            if (failedList.size() > 0) {
                json.add("failedList", failedList);
            }
            return json;
        }
    }

    // ------------------------------------------------------------------
    // Many commands
    // ------------------------------------------------------------------

    private static void registerRunCommands() {
        McpRegistry.registerTool(McpTool.named("server_run_commands")
            .title("Run many commands")
            .description("Run a list of commands on the server in one call, in order, with a structured "
                + "result for each and nothing sent to chat. For a build made of hundreds of /setblock, "
                + "/fill or /blockdata commands, instead of sending them through a player's chat in groups. "
                + "Runs as asPlayer when given, else with the console's authority (through the companion: as "
                + "you). A /fill or /clone leaves an undo point; the whole call leaves one. Commands on "
                + "permissions.blockedCommands are refused before anything runs.\n\n"
                + "Commands only act on loaded chunks; pass load: true to load the chunks their coordinates "
                + "name first (relative coordinates resolve against the player, or world spawn for the "
                + "console). A call that runs past two minutes stops and returns nextIndex: call again from "
                + "there.")
            .schema(JsonSchema.object()
                .array("commands", "The commands, with or without the leading slash. At most 10,000.",
                    Json.obj("type", "string"))
                .string("asPlayer", "Run as this connected player. Omit for console authority.")
                .bool("stopOnError", "Stop at the first command that does not succeed. Default false.")
                .bool("allResults", "List every command's result, not just the ones that failed. Default false.")
                .bool("load", "Load the chunks the commands' coordinates name, and release them afterwards.")
                .required("commands")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerBulkTools::runCommands)
            .build());
    }

    private static ToolResult runCommands(final ToolContext context) throws Exception {
        String denied = context.refusal(Capability.COMMANDS, McmcpConfig.isAllowCommands(),
            "Command execution is disabled by permissions.allowCommands in the MCMCP config.");
        if (denied != null) {
            return ToolResult.error(denied);
        }
        final List<String> commands = new ArrayList<>();
        for (String raw : Json.getStringList(context.getArguments(), "commands")) {
            String command = raw.trim().startsWith("/") ? raw.trim().substring(1) : raw.trim();
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
        if (commands.isEmpty()) {
            return ToolResult.error("'commands' is empty.");
        }
        if (commands.size() > 10_000) {
            return ToolResult.error(commands.size() + " commands is over the 10,000 one call runs. Split it.");
        }
        for (int i = 0; i < commands.size(); i++) {
            if (McmcpConfig.isCommandBlocked(commands.get(i))) {
                return ToolResult.error("Command " + i + " ('" + commands.get(i).split(" ")[0] + "') is on "
                    + "permissions.blockedCommands. Nothing was run.");
            }
        }
        final String asPlayer = context.getString("asPlayer", null);
        final boolean stopOnError = context.getBoolean("stopOnError", false);
        final boolean allResults = context.getBoolean("allResults", false);

        // The chunks the commands name, for load: true.
        final int[] base = context.onGameThread(() -> {
            MinecraftServer server = ServerThreadBridge.server();
            if (server == null) {
                throw new IllegalStateException("No Minecraft server is running");
            }
            if (asPlayer != null && !asPlayer.isEmpty()) {
                EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(asPlayer);
                if (player == null) {
                    throw new IllegalStateException("No player named '" + asPlayer + "' is connected.");
                }
                BlockPos at = GameJson.blockPosOf(player);
                return new int[] {at.getX(), at.getY(), at.getZ(), player.dimension};
            }
            BlockPos spawn = server.getWorld(0).getSpawnPoint();
            return new int[] {spawn.getX(), spawn.getY(), spawn.getZ(), 0};
        });
        final Set<Long> chunks = new LinkedHashSet<>();
        for (String command : commands) {
            for (int[] box : CommandTargets.boxes(command, base)) {
                chunks.addAll(ChunkKeys.box(box[0], box[2], box[3], box[5]));
            }
        }

        return ServerChunkTools.withChunks(context, base[3], chunks, outcome -> {
            final long deadline = System.currentTimeMillis() + 120_000L;
            final UndoPoints.Recorder recorder = ServerUndoTools.start(context, false);
            final Object[] undo = new Object[3];
            final int[] counts = new int[2];
            final JsonArray results = new JsonArray();
            final int[] next = {0};
            final boolean[] stopped = {false};
            while (next[0] < commands.size() && !stopped[0]) {
                if (System.currentTimeMillis() > deadline || context.getCancellation().isCancelled()) {
                    break;
                }
                context.onGameThread(() -> {
                    MinecraftServer server = ServerThreadBridge.server();
                    ICommandSender origin = server;
                    if (asPlayer != null && !asPlayer.isEmpty()) {
                        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(asPlayer);
                        if (player == null) {
                            throw new IllegalStateException("'" + asPlayer + "' left the server.");
                        }
                        origin = player;
                    }
                    // A slice of at most 25 ms of the tick, so a long list never holds one tick.
                    long sliceEnd = System.nanoTime() + 25_000_000L;
                    while (next[0] < commands.size() && System.nanoTime() < sliceEnd && !stopped[0]) {
                        int index = next[0]++;
                        String command = commands.get(index);
                        CapturingCommandSender sender = new CapturingCommandSender(origin);
                        CommandUndo.Snapshot snapshot = recorder == null ? null : CommandUndo.before(sender, command, recorder);
                        if (snapshot != null && undo[0] == null) {
                            undo[0] = snapshot.world.provider.getDimension();
                            undo[1] = snapshot.area();
                            undo[2] = undo[1] == null ? null : UndoPoints.drawArea(snapshot.world, (int[]) undo[1]);
                        }
                        int value;
                        String thrown = null;
                        try {
                            value = server.getCommandManager().executeCommand(sender, command);
                        }
                        catch (RuntimeException e) {
                            value = 0;
                            thrown = String.valueOf(e.getMessage());
                        }
                        if (snapshot != null) {
                            snapshot.recordChanges(recorder);
                        }
                        boolean ok = value != 0;
                        counts[ok ? 0 : 1]++;
                        if (allResults || !ok) {
                            if (results.size() < 2000) {
                                JsonObject item = new JsonObject();
                                item.addProperty("i", index);
                                item.addProperty("succeeded", ok);
                                item.addProperty("result", value);
                                // A failure is reported to the sender both as an error and as output.
                                Set<String> unique = new LinkedHashSet<>(sender.getErrors());
                                if (thrown != null) {
                                    unique.add(thrown);
                                }
                                unique.addAll(sender.getOutput());
                                List<String> lines = new ArrayList<>(unique);
                                if (!lines.isEmpty()) {
                                    item.add("output", Json.arrayOfStrings(lines.size() > 5 ? lines.subList(0, 5) : lines));
                                }
                                results.add(item);
                            }
                        }
                        if (!ok && stopOnError) {
                            stopped[0] = true;
                        }
                    }
                    return null;
                });
                context.reportProgress(next[0], commands.size(), "Ran " + next[0] + " of " + commands.size());
            }
            JsonObject json = new JsonObject();
            json.addProperty("commands", commands.size());
            json.addProperty("ran", next[0]);
            json.addProperty("succeeded", counts[0]);
            json.addProperty("failed", counts[1]);
            json.addProperty("ranAs", asPlayer == null ? "server console" : asPlayer);
            if (next[0] < commands.size()) {
                json.addProperty("nextIndex", next[0]);
                json.addProperty("stopReason", stopped[0] ? "stopOnError" : "time limit for one call");
            }
            json.add("results", results);
            if (outcome != null) {
                json.add("chunks", outcome.toJson());
            }
            if (recorder != null && undo[0] != null) {
                ServerUndoTools.finish(context, recorder, "server_run_commands", (Integer) undo[0], (int[]) undo[1],
                    (BufferedImage) undo[2], json, null);
            }
            return ToolResult.structured(json);
        });
    }
}
