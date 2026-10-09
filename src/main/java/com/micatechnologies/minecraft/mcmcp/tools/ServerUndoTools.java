package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * {@code server_undo}, and the bookkeeping the write tools share to leave undo points behind. See
 * {@link UndoPoints} for what an undo point is and why.
 */
public final class ServerUndoTools {

    /** The most changed blocks a restore's refusal names; the count beyond this stays exact. */
    private static final int MAX_LISTED_CONFLICTS = 5;

    private ServerUndoTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_undo")
            .title("Undo a block write")
            .description("List the undo points block writes left, or put one back.\n\n"
                + "Each server_set_blocks call records what every block it changed was before "
                + "(server_set_block too, when undo.recordSingleBlockWrites is on). op 'list' "
                + "shows them newest first, with the tool, time, block count and area. op 'restore' "
                + "puts the old blocks and their tile-entity data back. It refuses when any of those "
                + "blocks has changed since the write, naming where, unless 'force' is true. A "
                + "restore leaves an undo point of its own, so it can be undone too.")
            .schema(JsonSchema.object()
                .enumeration("op", "What to do. Defaults to 'list'.", "list", "restore")
                .string("id", "restore: the undo point's id, as op 'list' gives it, e.g. 'u1791392863587'.")
                .bool("force", "restore: put the blocks back even where they have changed since.")
                .integer("limit", "list: most points returned. Default 20.", 1, 200)
                .bool("load", "restore: load the point's chunks that are not loaded, and release them "
                    + "afterwards. Without it, a point whose area is not loaded is refused.")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerUndoTools::handle)
            .build());
    }

    private static ToolResult handle(ToolContext context) throws Exception {
        String op = context.getString("op", "list");
        File folder = context.onGameThread(() -> UndoPoints.folder(ServerWorldTools.requireWorld(0)));
        if ("list".equals(op)) {
            int limit = context.getBoundedInt("limit", 20, 1, 200);
            List<JsonObject> points = UndoPoints.list(folder);
            JsonArray array = new JsonArray();
            for (JsonObject point : points.subList(0, Math.min(limit, points.size()))) {
                array.add(point);
            }
            JsonObject json = new JsonObject();
            json.addProperty("enabled", McmcpConfig.isUndoPointsEnabled());
            json.addProperty("total", points.size());
            json.add("points", array);
            return ToolResult.structured(json);
        }
        if (!"restore".equals(op)) {
            return ToolResult.error("Unknown op '" + op + "'; use list or restore.");
        }
        String denied = context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
            "Restoring writes to the world, which is disabled by "
                + "permissions.allowWorldEdits in the MCMCP config.");
        if (denied != null) {
            return ToolResult.error(denied);
        }
        String id = context.getString("id", null);
        JsonObject point = id == null ? null : UndoPoints.find(folder, id);
        if (point == null) {
            return ToolResult.error("There is no undo point '" + id + "' in this world. op 'list' "
                + "shows the ones there are.");
        }
        final File pointFolder = folder;
        final JsonObject found = point;
        JsonObject from = Json.getObject(point, "from");
        JsonObject to = Json.getObject(point, "to");
        if (from == null || to == null) {
            return restore(context, folder, point, context.getBoolean("force", false));
        }
        java.util.Set<Long> chunks = com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys.box(
            Json.getInt(from, "x", 0), Json.getInt(from, "z", 0), Json.getInt(to, "x", 0), Json.getInt(to, "z", 0));
        return ServerChunkTools.withChunks(context, Json.getInt(point, "dimension", 0), chunks,
            outcome -> restore(context, pointFolder, found, context.getBoolean("force", false)));
    }

    private static ToolResult restore(ToolContext context, File folder, JsonObject point, boolean force)
        throws IOException {
        final String id = Json.getString(point, "id", "");
        final int dimension = Json.getInt(point, "dimension", 0);
        final NBTTagCompound record = UndoPoints.read(folder, id);
        final int[] positions = record.getIntArray("positions");
        final int[] before = record.getIntArray("before");
        final int[] after = record.getIntArray("after");
        final int total = before.length;
        final int batch = McmcpConfig.getMaxBlockVolume();

        // What has changed since the write, read before anything is written.
        final JsonArray changedAt = new JsonArray();
        final int[] changed = {0};
        final int[] unloaded = {0};
        for (int from = 0; from < total; from += batch) {
            final int start = from;
            final int end = Math.min(total, from + batch);
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                IBlockState[] states = UndoPoints.resolveStates(record);
                for (int i = start; i < end; i++) {
                    BlockPos pos = new BlockPos(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
                    if (!world.isBlockLoaded(pos)) {
                        unloaded[0]++;
                        continue;
                    }
                    if (world.getBlockState(pos) != states[after[i]]) {
                        changed[0]++;
                        if (changedAt.size() < MAX_LISTED_CONFLICTS) {
                            changedAt.add(GameJson.blockPos(pos));
                        }
                    }
                }
                return null;
            });
        }
        if (unloaded[0] > 0) {
            // Restoring would load those chunks, or generate them. Hold the area first.
            return ToolResult.error(unloaded[0] + " of the " + total + " blocks this would put back are in "
                + "chunks that are not loaded, so nothing was restored. Hold the area with server_keep_loaded "
                + "(op 'list' gives this point's bounds) and restore again.");
        }
        if (changed[0] > 0 && !force) {
            return ToolResult.error(changed[0] + " of the " + total + " blocks this would put back "
                + "have changed since the write, for example at " + Json.write(changedAt) + ". "
                + "Restoring would overwrite that. Pass force: true to restore anyway.");
        }

        final int[] area = areaOf(point);
        final BufferedImage beforeImage = drawImage(context, dimension, area);
        final UndoPoints.Recorder recorder = start(context, false);
        final Map<Integer, NBTTagCompound> tileEntities = UndoPoints.tileEntities(record);
        for (int from = 0; from < total; from += batch) {
            final int start = from;
            final int end = Math.min(total, from + batch);
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                IBlockState[] states = UndoPoints.resolveStates(record);
                for (int i = start; i < end; i++) {
                    BlockPos pos = new BlockPos(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
                    UndoPoints.Before was = recorder == null ? null : recorder.capture(world, pos);
                    boolean wrote = world.setBlockState(pos, states[before[i]], 3);
                    NBTTagCompound tileEntity = tileEntities.get(i);
                    if (tileEntity != null
                        && TileEntityNbt.merge(world, pos, tileEntity) == TileEntityNbt.Outcome.APPLIED) {
                        wrote = true;
                    }
                    if (recorder != null && wrote) {
                        recorder.record(pos, was, world.getBlockState(pos));
                    }
                }
                return null;
            });
            if (total > batch) {
                context.reportProgress(end, total, "Restored " + end + " of " + total + " blocks");
            }
        }

        JsonObject json = new JsonObject();
        json.addProperty("restored", id);
        json.addProperty("blocks", total);
        json.addProperty("changedSince", changed[0]);
        json.addProperty("forced", force && changed[0] > 0);
        if (recorder != null) {
            String undoneBy = finish(context, recorder, "server_undo", dimension, area, beforeImage, json, id);
            if (undoneBy != null) {
                try {
                    UndoPoints.markRestored(folder, id, undoneBy);
                }
                catch (IOException e) {
                    Mcmcp.LOGGER.warn("MCMCP could not mark undo point " + id + " restored", e);
                }
            }
        }
        return ToolResult.structured(json);
    }

    // ------------------------------------------------------------------
    // Shared with the write tools
    // ------------------------------------------------------------------

    /**
     * A recorder for a write about to happen, or null when this write leaves no undo point:
     * {@code undo.enableUndoPoints} off, or a single-block write without
     * {@code undo.recordSingleBlockWrites}.
     */
    @Nullable
    static UndoPoints.Recorder start(ToolContext context, boolean singleBlock) {
        // A write a player makes through the companion always leaves a point, single block or not:
        // on a shared server it is the way back from a mistake and the record of who made it.
        if (context.getPrincipal().isCompanion()) {
            return new UndoPoints.Recorder();
        }
        if (!McmcpConfig.isUndoPointsEnabled()) {
            return null;
        }
        if (singleBlock && !McmcpConfig.isUndoSingleBlockWrites()) {
            return null;
        }
        return new UndoPoints.Recorder();
    }

    /** A map of {@code area} as it is now, or null when there is no area to draw. */
    @Nullable
    static BufferedImage drawImage(ToolContext context, int dimension, @Nullable int[] area) {
        if (area == null) {
            return null;
        }
        try {
            return context.onGameThread(() -> UndoPoints.drawArea(ServerWorldTools.requireWorld(dimension), area));
        }
        catch (RuntimeException e) {
            // A picture is a nicety; it must never cost the write.
            Mcmcp.LOGGER.debug("MCMCP could not draw an undo map", e);
            return null;
        }
    }

    /**
     * Saves what {@code recorder} holds as an undo point and names it in {@code result}. A failure is
     * reported in the result and logged, never thrown: the write it records has already happened.
     *
     * @return the new point's id, or null when nothing was recorded or it could not be saved
     */
    @Nullable
    static String finish(ToolContext context, UndoPoints.Recorder recorder, String tool, int dimension,
                         @Nullable int[] area, @Nullable BufferedImage beforeImage, JsonObject result,
                         @Nullable String undoes) {
        if (recorder.count() == 0) {
            return null;
        }
        final String id = UndoPoints.newId();
        try {
            Object[] taken = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                return new Object[] {UndoPoints.folder(world), recorder.toNbt(), recorder.bounds()};
            });
            File folder = (File) taken[0];
            JsonObject meta = UndoPoints.meta(id, tool, dimension, recorder.count(), (JsonObject) taken[2]);
            if (undoes != null) {
                meta.addProperty("undoes", undoes);
            }
            if (context.getPrincipal().isCompanion()) {
                // Who made the write, on a server where several players' agents may be building.
                meta.addProperty("by", context.getPrincipal().getPlayerName());
            }
            BufferedImage afterImage = beforeImage == null ? null : drawImage(context, dimension, area);
            UndoPoints.save(folder, meta, (NBTTagCompound) taken[1], beforeImage, afterImage);
            result.addProperty("undoPoint", id);
            return id;
        }
        catch (IOException | RuntimeException e) {
            Mcmcp.LOGGER.warn("MCMCP could not save an undo point for " + tool, e);
            result.addProperty("undoPointError", String.valueOf(e.getMessage()));
            return null;
        }
    }

    /** The map area of an undo point, from its listing's bounds. */
    @Nullable
    private static int[] areaOf(JsonObject point) {
        JsonObject from = Json.getObject(point, "from");
        JsonObject to = Json.getObject(point, "to");
        if (from == null || to == null) {
            return null;
        }
        return UndoPoints.imageArea(Json.getInt(from, "x", 0), Json.getInt(from, "z", 0),
            Json.getInt(to, "x", 0), Json.getInt(to, "z", 0));
    }
}
