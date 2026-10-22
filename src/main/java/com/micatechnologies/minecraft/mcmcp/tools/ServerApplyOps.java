package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import com.micatechnologies.minecraft.mcmcp.regions.ChangeRing;
import com.micatechnologies.minecraft.mcmcp.regions.ChangeTracker;
import com.micatechnologies.minecraft.mcmcp.regions.CommandOps;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Replays a build generator's {@code /fill}, {@code /setblock} and {@code /blockdata} lines on the
 * server and writes only the cells whose result differs from what is there (#72).
 *
 * <p>The script-side version read the whole bounding box down — 300,000 to 800,000 cells a building
 * — replayed the ops, diffed, and sent the changed cells back. The read was most of a build's time,
 * and the part that failed when chunk holds were contended. Here the ops travel instead of the cells.
 *
 * <p>Three phases, each in game-thread slices of about {@link #SLICE_NANOS}: replay into a
 * {@link CellOverlay} (reading the world only where a filter or keep needs the block underneath), diff
 * and write, then merge the tags. Two-part blocks go through {@link TwoPartBlocks}, so a generator's
 * setblock pair for a door lands as one door.
 */
public final class ServerApplyOps {

    private static final long SLICE_NANOS = 25_000_000L;
    private static final int MAX_OPS = 50_000;
    private static final int LISTED = 20;

    private ServerApplyOps() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_apply_ops")
            .title("Replay fill and setblock commands")
            .description("Replay a list of /fill, /setblock and /blockdata command lines on the server, in "
                + "order, and write only the cells whose final block differs from what is there: one call per "
                + "build phase, with no read of the area first. fill takes replace [filter [meta]], keep, "
                + "destroy, hollow and outline; data may be metadata or a state string; a trailing {nbt} is "
                + "merged as /blockdata does. Coordinates must be absolute. Door and other two-part setblock "
                + "pairs are written as one block. dryRun counts what would change and lists a sample. "
                + "'since' (a server_changes_since token) leaves cells someone else changed since then. "
                + "Counts always add up to the cells the ops reach. Needs permissions.allowWorldEdits.")
            .schema(JsonSchema.object()
                .array("ops", "The command lines, with or without the slash. At most " + MAX_OPS + ".",
                    Json.obj("type", "string"))
                .integer("dimension", "Dimension id. Defaults to 0.")
                .bool("dryRun", "Replay and diff, write nothing; return the counts and a sample of changes.")
                .bool("neighbourUpdates", "Notify neighbouring blocks, as a normal placement does. Default false: "
                    + "a generator's output is already complete, and updates mid-build break half-built things.")
                .string("since", "A server_changes_since token: cells someone else changed after it are left "
                    + "alone and counted as conflicts.")
                .bool("stopOnRefusal", "Write nothing if any line fails to parse or names an unknown block.")
                .bool("pairTwoPart", "Write two-part blocks (doors, beds, tall furniture) whole, deriving a "
                    + "missing second half. Default true; false writes every cell exactly as given.")
                .bool("load", "Load the chunks the ops reach for this call, and release them afterwards.")
                .bool("holdPhysics", "Hold sand, gravel and fluids in and next to the write still until the call "
                    + "ends, then report what would still move (physics) and let it. Default false.")
                .required("ops")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerApplyOps::run)
            .build());
    }

    /** An op made ready for the game: block states resolved. */
    private static final class Ready {
        final int index;
        final CommandOps.Op op;
        @Nullable
        final IBlockState state;
        @Nullable
        final Block filter;
        @Nullable
        final NBTTagCompound nbt;

        Ready(int index, CommandOps.Op op, @Nullable IBlockState state, @Nullable Block filter,
              @Nullable NBTTagCompound nbt) {
            this.index = index;
            this.op = op;
            this.state = state;
            this.filter = filter;
            this.nbt = nbt;
        }
    }

    /** Counts for the reply. Written on the game thread, read after the last slice. */
    private static final class Tally {
        long written;
        long unchanged;
        long unloaded;
        long conflicts;
        long refused;
        long byPartner;
        int nbtApplied;
        int nbtUnchanged;
        int nbtNoTileEntity;
        final JsonArray refusedAt = new JsonArray();
        final JsonArray conflictAt = new JsonArray();
        final JsonArray sample = new JsonArray();

        void refuse(BlockPos pos, String reason) {
            refused++;
            if (refusedAt.size() < LISTED) {
                JsonObject item = GameJson.blockPos(pos);
                item.addProperty("reason", reason);
                refusedAt.add(item);
            }
        }
    }

    private static ToolResult run(final ToolContext context) throws Exception {
        String denied = context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
            "Direct world edits are disabled by permissions.allowWorldEdits in the MCMCP config.");
        if (denied != null) {
            return ToolResult.error(denied);
        }
        List<String> lines = Json.getStringList(context.getArguments(), "ops");
        if (lines.isEmpty() || lines.size() > MAX_OPS) {
            return ToolResult.error("'ops' takes 1 to " + MAX_OPS + " command lines; it had " + lines.size() + ".");
        }
        final int dimension = context.getInt("dimension", 0);
        final boolean dryRun = context.getBoolean("dryRun", false);
        final int flags = context.getBoolean("neighbourUpdates", false) ? 3 : 2 | 16;
        final boolean holdPhysics = context.getBoolean("holdPhysics", false);
        final boolean stopOnRefusal = context.getBoolean("stopOnRefusal", false);
        final Long since;
        try {
            since = context.has("since") ? Long.parseLong(context.getString("since", "0").trim()) : null;
        }
        catch (NumberFormatException e) {
            return ToolResult.error("'since' is a token from server_changes_since, a whole number.");
        }

        // Parse and resolve. Each refusal names its line, so a generator bug is findable.
        CommandOps.Parsed parsed = CommandOps.parseAll(lines);
        final JsonArray refusedOps = new JsonArray();
        for (int i = 0; i < parsed.errors.size(); i++) {
            refusedOp(refusedOps, parsed.errorLines.get(i), lines.get(parsed.errorLines.get(i)), parsed.errors.get(i));
        }
        final List<Ready> ready = new ArrayList<>();
        long reach = 0L;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        Set<Long> chunks = new LinkedHashSet<>();
        for (int i = 0; i < parsed.ops.size(); i++) {
            CommandOps.Op op = parsed.ops.get(i);
            int line = parsed.lineOf.get(i);
            String why = null;
            IBlockState state = null;
            Block filter = null;
            NBTTagCompound nbt = null;
            if (op.y1 < 0 || op.y2 > 255) {
                why = "y must be 0-255.";
            }
            if (why == null && op.block != null) {
                Block block = BlockIds.resolve(op.block);
                if (block == null) {
                    why = BlockIds.describeUnknown(op.block, "place");
                }
                else {
                    try {
                        state = op.data == null ? block.getDefaultState() : CommandBase.convertArgToBlockState(block, op.data);
                    }
                    catch (Exception e) {
                        why = "'" + op.data + "' is not a state of " + op.block + ".";
                    }
                }
            }
            if (why == null && op.filterBlock != null) {
                filter = BlockIds.resolve(op.filterBlock);
                if (filter == null) {
                    why = BlockIds.describeUnknown(op.filterBlock, "use as a replace filter");
                }
            }
            if (why == null && op.nbt != null) {
                try {
                    nbt = TileEntityNbt.parse(new JsonPrimitive(op.nbt));
                }
                catch (IllegalArgumentException e) {
                    why = e.getMessage();
                }
            }
            if (why != null) {
                refusedOp(refusedOps, line, lines.get(line), why);
                continue;
            }
            ready.add(new Ready(line, op, state, filter, nbt));
            reach += op.cells();
            minX = Math.min(minX, op.x1);
            minY = Math.min(minY, op.y1);
            minZ = Math.min(minZ, op.z1);
            maxX = Math.max(maxX, op.x2);
            maxY = Math.max(maxY, op.y2);
            maxZ = Math.max(maxZ, op.z2);
            chunks.addAll(ChunkKeys.box(op.x1, op.z1, op.x2, op.z2));
        }
        if (stopOnRefusal && refusedOps.size() > 0) {
            JsonObject json = new JsonObject();
            json.addProperty("aborted", true);
            json.addProperty("note", "stopOnRefusal: nothing was written because " + refusedOps.size()
                + " line(s) were refused.");
            json.add("refusedOps", refusedOps);
            return ToolResult.structured(json);
        }
        if (ready.isEmpty()) {
            return ToolResult.error("No line could be replayed.").withStructured(Json.obj("refusedOps", refusedOps));
        }
        long cap = (long) McmcpConfig.getMaxWriteCells() * 4L;
        if (reach > cap) {
            return ToolResult.error("These ops reach " + reach + " cells; one call replays at most " + cap
                + " (4 x limits.maxWriteCells). Split the list, or submit it as a job with server_job_submit.");
        }
        if (!dryRun) {
            ToolResult overBudget = ServerBulkTools.chargeWrites(context, reach);
            if (overBudget != null) {
                return overBudget;
            }
        }
        final int[] box = {minX, minY, minZ, maxX, maxY, maxZ};
        final Set<Long> touchedChunks = chunks;

        return ServerChunkTools.withChunks(context, dimension, touchedChunks, outcome -> {
            final CellOverlay overlay = new CellOverlay();
            final StatePalette palette = new StatePalette();
            final Map<Long, NBTTagCompound> tags = new LinkedHashMap<>();
            final long[] unloadedReads = {0L};
            replay(context, dimension, ready, overlay, palette, tags, unloadedReads);

            // Cells someone else changed since the caller's token are theirs, not this call's.
            final Set<Long> protectedCells = new HashSet<>();
            if (since != null) {
                ChangeRing ring = ChangeTracker.ring();
                if (ring == null) {
                    return ToolResult.error("Change tracking is not running on this server, so 'since' cannot be honoured.");
                }
                ChangeRing.Result changed = ring.since(since, dimension, box[0], box[1], box[2], box[3], box[4], box[5],
                    Integer.MAX_VALUE, null);
                if (changed.overflow) {
                    return ToolResult.error("The change record no longer reaches back to token " + since + ", so "
                        + "changes since then cannot be ruled out. Read the area again and take a new token.");
                }
                for (ChangeRing.Change change : changed.changes) {
                    protectedCells.add(new BlockPos(change.x, change.y, change.z).toLong());
                }
            }

            final Tally tally = new Tally();
            final UndoPoints.Recorder recorder = dryRun ? null : ServerUndoTools.start(context, false);
            final int[] area = recorder == null ? null : UndoPoints.imageArea(box[0], box[2], box[3], box[5]);
            final BufferedImage beforeImage = recorder == null ? null : ServerUndoTools.drawImage(context, dimension, area);
            final PhysicsHold hold = holdPhysics && !dryRun ? new PhysicsHold(box, (flags & 1) != 0) : null;
            JsonObject physics = null;
            TwoPartBlocks.Writer pairs;
            try {
                pairs = write(context, dimension, overlay, palette, protectedCells, flags, dryRun, tally, recorder,
                    hold);
                if (!dryRun) {
                    mergeTags(context, dimension, tags, protectedCells, tally);
                }
            }
            finally {
                if (hold != null) {
                    physics = hold.finish(context, dimension);
                }
            }

            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("ops", ready.size());
            json.addProperty("dryRun", dryRun);
            json.addProperty("attempted", overlay.cells());
            json.addProperty(dryRun ? "wouldWrite" : "written", tally.written);
            json.addProperty("unchanged", tally.unchanged);
            json.addProperty("conflicts", tally.conflicts);
            json.addProperty("unloaded", tally.unloaded);
            json.addProperty("refused", tally.refused);
            if (tally.byPartner > 0) {
                json.addProperty("byPartner", tally.byPartner);
            }
            pairs.report(json);
            if (unloadedReads[0] > 0) {
                json.addProperty("unloadedReads", unloadedReads[0]);
            }
            if (!tags.isEmpty()) {
                JsonObject nbt = new JsonObject();
                nbt.addProperty("cells", tags.size());
                if (!dryRun) {
                    nbt.addProperty("applied", tally.nbtApplied);
                    nbt.addProperty("unchanged", tally.nbtUnchanged);
                    nbt.addProperty("noTileEntity", tally.nbtNoTileEntity);
                }
                json.add("nbt", nbt);
            }
            JsonObject bbox = new JsonObject();
            bbox.add("from", GameJson.blockPos(new BlockPos(box[0], box[1], box[2])));
            bbox.add("to", GameJson.blockPos(new BlockPos(box[3], box[4], box[5])));
            json.add("bbox", bbox);
            if (refusedOps.size() > 0) {
                json.add("refusedOps", refusedOps);
            }
            if (tally.refusedAt.size() > 0) {
                json.add("refusedAt", tally.refusedAt);
            }
            if (tally.conflictAt.size() > 0) {
                json.add("conflictAt", tally.conflictAt);
            }
            if (dryRun) {
                json.add("sample", tally.sample);
            }
            if (physics != null) {
                json.add("physics", physics);
            }
            json.add("chunks", context.onGameThread(() -> {
                ChunkLoadGovernor.touch(dimension, touchedChunks);
                return outcome != null ? outcome.toJson()
                    : ChunkLoadGovernor.describeUnloaded(ServerWorldTools.requireWorld(dimension), touchedChunks);
            }));
            if (recorder != null) {
                ServerUndoTools.finish(context, recorder, "server_apply_ops", dimension, area, beforeImage, json, null);
            }
            return ToolResult.structured(json);
        });
    }

    private static void refusedOp(JsonArray refusedOps, int line, String text, String why) {
        if (refusedOps.size() < 100) {
            JsonObject item = new JsonObject();
            item.addProperty("line", line);
            item.addProperty("op", text.length() > 120 ? text.substring(0, 120) + "..." : text);
            item.addProperty("reason", why);
            refusedOps.add(item);
        }
    }

    /**
     * Replays every op into the overlay. A fill without a filter needs nothing from the world; replace
     * with a filter and keep read the block underneath, which is the overlay's if an earlier op set it.
     */
    private static void replay(ToolContext context, int dimension, List<Ready> ready, CellOverlay overlay,
                               StatePalette palette, Map<Long, NBTTagCompound> tags, long[] unloadedReads) {
        final int[] cursor = {0, 0};  // op, cell within it
        while (cursor[0] < ready.size()) {
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long started = System.nanoTime();
                while (cursor[0] < ready.size() && System.nanoTime() - started < SLICE_NANOS) {
                    Ready op = ready.get(cursor[0]);
                    cursor[1] = replayOp(world, op, overlay, palette, tags, cursor[1], started, unloadedReads);
                    if (cursor[1] < 0) {
                        cursor[0]++;
                        cursor[1] = 0;
                    }
                }
                return null;
            });
            if (ready.size() > 200) {
                context.reportProgress(cursor[0], ready.size() * 3.0D, "Replayed " + cursor[0] + " of " + ready.size()
                    + " ops");
            }
        }
    }

    /** Game thread. @return the next cell to do, or -1 when the op is done. */
    private static int replayOp(WorldServer world, Ready ready, CellOverlay overlay, StatePalette palette,
                                Map<Long, NBTTagCompound> tags, int from, long started, long[] unloadedReads) {
        CommandOps.Op op = ready.op;
        if (op.kind == CommandOps.Kind.BLOCKDATA) {
            long key = new BlockPos(op.x1, op.y1, op.z1).toLong();
            NBTTagCompound pending = tags.get(key);
            if (pending == null) {
                tags.put(key, ready.nbt.copy());
            }
            else {
                pending.merge(ready.nbt);
            }
            return -1;
        }
        int sizeX = op.x2 - op.x1 + 1;
        int sizeZ = op.z2 - op.z1 + 1;
        long total = op.cells();
        int airId = palette.value(Blocks.AIR.getDefaultState());
        int stateId = palette.value(ready.state);
        for (long i = from; i < total; i++) {
            if ((i & 1023) == 0 && i > from && System.nanoTime() - started >= SLICE_NANOS) {
                return (int) i;
            }
            int x = op.x1 + (int) (i % sizeX);
            int z = op.z1 + (int) ((i / sizeX) % sizeZ);
            int y = op.y1 + (int) (i / ((long) sizeX * sizeZ));
            int value = stateId;
            boolean shell = op.onShell(x, y, z);
            if (op.mode == CommandOps.Mode.OUTLINE && !shell) {
                continue;
            }
            if (op.mode == CommandOps.Mode.HOLLOW && !shell) {
                value = airId;
            }
            if (op.mode == CommandOps.Mode.KEEP || ready.filter != null) {
                IBlockState current = current(world, overlay, palette, x, y, z);
                if (current == null) {
                    unloadedReads[0]++;
                    continue;
                }
                if (op.mode == CommandOps.Mode.KEEP && current.getBlock() != Blocks.AIR) {
                    continue;
                }
                if (ready.filter != null && (current.getBlock() != ready.filter
                    || op.filterMeta >= 0 && current.getBlock().getMetaFromState(current) != op.filterMeta)) {
                    continue;
                }
            }
            overlay.set(x, y, z, value);
            long key = new BlockPos(x, y, z).toLong();
            // A block written over drops any tag an earlier op left for that cell, as a real /setblock does.
            if (ready.nbt != null && value == stateId) {
                tags.put(key, ready.nbt.copy());
            }
            else {
                tags.remove(key);
            }
        }
        return -1;
    }

    /** The block a cell holds so far: the overlay's, or the world's; null in an unloaded chunk. */
    @Nullable
    private static IBlockState current(WorldServer world, CellOverlay overlay, StatePalette palette, int x, int y,
                                       int z) {
        int value = overlay.get(x, y, z);
        if (value != 0) {
            return palette.state(value);
        }
        BlockPos pos = new BlockPos(x, y, z);
        return world.isBlockLoaded(pos) ? world.getBlockState(pos) : null;
    }

    /**
     * The states this call's overlay holds, by a value of its own (1-based; 0 is the overlay's "unset").
     *
     * <p>Not {@link Block#getStateId}: that is the block id plus {@code getMetaFromState}, and back is
     * {@code getStateFromMeta}. A block whose two mappings disagree (Logistical Automation's conveyor
     * stairs) came back as a different state, often the one already standing there, so the write was
     * diffed away as "unchanged" and nothing was placed (#120). This keeps the exact state asked for.
     */
    static final class StatePalette {
        private final List<IBlockState> states = new ArrayList<>();
        private final Map<IBlockState, Integer> values = new java.util.IdentityHashMap<>();

        int value(IBlockState state) {
            Integer value = values.get(state);
            if (value == null) {
                states.add(state);
                value = states.size();
                values.put(state, value);
            }
            return value;
        }

        IBlockState state(int value) {
            return states.get(value - 1);
        }
    }

    /**
     * Diffs the overlay against the world and writes the difference, bottom section first. Returns the
     * pair writer, for the reply's {@code pairs}.
     */
    private static TwoPartBlocks.Writer write(ToolContext context, int dimension, CellOverlay overlay,
                                              StatePalette palette, Set<Long> protectedCells, int flags,
                                              boolean dryRun, Tally tally, @Nullable UndoPoints.Recorder recorder,
                                              @Nullable PhysicsHold hold) {
        final List<Long> sections = overlay.sectionsBottomUp();
        final Map<Long, IBlockState> twoPart = new HashMap<>();
        final TwoPartBlocks.Writer pairs = new TwoPartBlocks.Writer(twoPart, flags);
        pairs.pairing = context.getBoolean("pairTwoPart", true);
        for (long key : sections) {
            overlay.visit(key, (x, y, z, value) -> {
                IBlockState state = palette.state(value);
                TwoPartRule.Verdict verdict = TwoPartBlocks.verdict(state);
                if (verdict == TwoPartRule.Verdict.PAIR) {
                    twoPart.put(new BlockPos(x, y, z).toLong(), state);
                }
                else if (verdict == TwoPartRule.Verdict.SUSPECTED) {
                    pairs.suspect(state);
                }
            });
        }
        final int[] cursor = {0};
        while (cursor[0] < sections.size()) {
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                TwoPartBlocks.Recorder undo = TwoPartBlocks.undo(world, recorder);
                long started = System.nanoTime();
                try {
                    while (cursor[0] < sections.size() && (System.nanoTime() - started < SLICE_NANOS)) {
                        overlay.visit(sections.get(cursor[0]++), (x, y, z, value) -> {
                            BlockPos pos = new BlockPos(x, y, z);
                            IBlockState target = palette.state(value);
                            try {
                                writeCell(world, pos, target, protectedCells, flags, dryRun, pairs, undo, tally,
                                    recorder, hold);
                            }
                            catch (RuntimeException e) {
                                tally.refuse(pos, BlockWriteFaults.reason(e, pos,
                                    ServerRegionTools.describe(Block.getStateId(palette.state(value)))));
                            }
                        });
                    }
                }
                finally {
                    // Before the world ticks again: the ticks this slice scheduled are where sand falls.
                    if (hold != null) {
                        hold.capture(world);
                    }
                }
                return null;
            });
            context.reportProgress(sections.size() + cursor[0], sections.size() * 3.0D, (dryRun ? "Diffed " : "Wrote ")
                + cursor[0] + " of " + sections.size() + " sections");
        }
        return pairs;
    }

    /** Game thread: one cell's diff, and its write unless this is a dry run. */
    private static void writeCell(WorldServer world, BlockPos pos, IBlockState target, Set<Long> protectedCells,
                                  int flags, boolean dryRun, TwoPartBlocks.Writer pairs,
                                  @Nullable TwoPartBlocks.Recorder undo, Tally tally,
                                  @Nullable UndoPoints.Recorder recorder, @Nullable PhysicsHold hold) {
        if (!world.isBlockLoaded(pos)) {
            tally.unloaded++;
            return;
        }
        if (protectedCells.contains(pos.toLong())) {
            tally.conflicts++;
            if (tally.conflictAt.size() < LISTED) {
                tally.conflictAt.add(GameJson.blockPos(pos));
            }
            return;
        }
        if (!dryRun && TwoPartBlocks.isTwoPart(target)) {
            switch (pairs.write(world, pos, target, null, undo)) {
                case WRITTEN:
                    tally.written++;
                    if (hold != null) {
                        hold.changed(pos);
                    }
                    return;
                case UNCHANGED:
                    tally.unchanged++;
                    return;
                case DONE_BY_PARTNER:
                    tally.byPartner++;
                    return;
                case FAILED:
                    tally.refuse(pos, "the pair could not be placed: its other cell is unloaded or outside the world");
                    return;
                default:
                    break;
            }
        }
        IBlockState actual = world.getBlockState(pos);
        if (actual == target) {
            tally.unchanged++;
            return;
        }
        if (dryRun) {
            tally.written++;
            if (tally.sample.size() < LISTED) {
                JsonArray change = new JsonArray();
                change.add(pos.getX());
                change.add(pos.getY());
                change.add(pos.getZ());
                change.add(ServerRegionTools.describe(Block.getStateId(actual)));
                change.add(ServerRegionTools.describe(Block.getStateId(target)));
                tally.sample.add(change);
            }
            return;
        }
        UndoPoints.Before was = recorder == null ? null : recorder.capture(world, pos);
        if (BlockWriteFaults.set(world, pos, target, flags, recorder, was)) {
            tally.written++;
            if (hold != null) {
                hold.changed(pos);
            }
            if (recorder != null) {
                recorder.record(pos, was, world.getBlockState(pos));
            }
        }
        else {
            tally.refuse(pos, "the world did not accept " + ServerRegionTools.describe(Block.getStateId(target)));
        }
    }

    /** Merges the tags the ops left, in op order, after every block is in place. */
    private static void mergeTags(ToolContext context, int dimension, Map<Long, NBTTagCompound> tags,
                                  Set<Long> protectedCells, Tally tally) {
        if (tags.isEmpty()) {
            return;
        }
        final List<Map.Entry<Long, NBTTagCompound>> entries = new ArrayList<>(tags.entrySet());
        final int[] cursor = {0};
        while (cursor[0] < entries.size()) {
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long started = System.nanoTime();
                while (cursor[0] < entries.size() && System.nanoTime() - started < SLICE_NANOS) {
                    Map.Entry<Long, NBTTagCompound> entry = entries.get(cursor[0]++);
                    if (protectedCells.contains(entry.getKey())) {
                        continue;
                    }
                    BlockPos pos = BlockPos.fromLong(entry.getKey());
                    if (!world.isBlockLoaded(pos)) {
                        continue;
                    }
                    switch (TileEntityNbt.merge(world, pos, entry.getValue())) {
                        case APPLIED:
                            tally.nbtApplied++;
                            break;
                        case UNCHANGED:
                            tally.nbtUnchanged++;
                            break;
                        default:
                            tally.nbtNoTileEntity++;
                            break;
                    }
                }
                return null;
            });
        }
    }
}
