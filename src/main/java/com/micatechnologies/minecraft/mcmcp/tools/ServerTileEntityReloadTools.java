package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Puts tile entities through what a chunk reload does to them, without unloading anything (#141).
 *
 * <p>Some blocks only finish setting up when their chunk loads: CSM's HVAC vents register with their
 * system in their load hooks (CSM #243), and a vent placed into a loaded chunk stays inert until that
 * chunk unloads and comes back. On a shared server that can be never, because a chunk near any player
 * does not unload. This does the reload's part for the tile entities in a box: unload hook, removed,
 * re-created from their own NBT, added back (which runs {@code onLoad}), and the block resent to the
 * players watching it. The block itself is never changed, so nothing breaks or drops.
 */
public final class ServerTileEntityReloadTools {

    static final int MAX_TILE_ENTITIES = 4096;
    static final long MAX_CELLS = 4_000_000L;

    private ServerTileEntityReloadTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_reload_tile_entities")
            .title("Reload tile entities in place")
            .description("Put the tile entities in a box through what a chunk reload does to them, without "
                + "unloading anything: their unload hook runs, they are removed, re-created from their own NBT "
                + "and added back (running their load hook), and the block is resent to watching players. For "
                + "blocks that only finish setting up when their chunk loads (e.g. CSM HVAC vents), where an "
                + "actual reload is impossible because a player is near. The blocks themselves are not changed. "
                + "'blocks' (patterns, '*road*', 'csm:hvac_*') limits it to some. dryRun lists what it would "
                + "reload. Needs permissions.allowWorldEdits.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .integer("x", "X of one corner.")
                .integer("y", "Y of one corner.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Defaults to x.")
                .integer("toY", "Y of the opposite corner. Defaults to y.")
                .integer("toZ", "Z of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id. Default 0.")
                .stringArray("blocks", "Only tile entities of these blocks (patterns).")
                .bool("dryRun", "Count what would be reloaded; change nothing."))
                .required("x", "y", "z")
                .build())
            .alias("include", "blocks")
            .serverOnly()
            .destructive()
            .idempotent()
            .handler(ServerTileEntityReloadTools::reload)
            .build());
    }

    private static ToolResult reload(final ToolContext context) throws Exception {
        String denied = context.refusal(com.micatechnologies.minecraft.mcmcp.mcp.Capability.WORLD_EDITS,
            McmcpConfig.isAllowWorldEdits(), "Changing the world is disabled by permissions.allowWorldEdits in "
                + "the MCMCP config.");
        if (denied != null) {
            return ToolResult.error(denied);
        }
        int x1 = context.requireInt("x");
        int y1 = context.requireInt("y");
        int z1 = context.requireInt("z");
        final int minX = Math.min(x1, context.getInt("toX", x1));
        final int maxX = Math.max(x1, context.getInt("toX", x1));
        final int minY = Math.max(0, Math.min(y1, context.getInt("toY", y1)));
        final int maxY = Math.min(255, Math.max(y1, context.getInt("toY", y1)));
        final int minZ = Math.min(z1, context.getInt("toZ", z1));
        final int maxZ = Math.max(z1, context.getInt("toZ", z1));
        long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells > MAX_CELLS) {
            return ToolResult.error("That box is " + cells + " cells; at most " + MAX_CELLS + ".");
        }
        final int dimension = context.getInt("dimension", 0);
        final boolean dryRun = context.getBoolean("dryRun", false);
        final BlockPatterns blocks = BlockPatterns.of(Json.getStringList(context.getArguments(), "blocks"));

        return ServerChunkTools.withChunks(context, dimension, ChunkKeys.box(minX, minZ, maxX, maxZ), outcome ->
            context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                // Collected first: re-adding changes the loaded list being walked.
                List<BlockPos> found = new ArrayList<>();
                for (TileEntity tile : new ArrayList<>(world.loadedTileEntityList)) {
                    if (tile == null || tile.isInvalid()) {
                        continue;
                    }
                    BlockPos pos = tile.getPos();
                    if (pos.getX() < minX || pos.getX() > maxX || pos.getY() < minY || pos.getY() > maxY
                        || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    if (!blocks.isEmpty() && !blocks.matches(idOf(world.getBlockState(pos)))) {
                        continue;
                    }
                    found.add(pos);
                }
                JsonObject json = new JsonObject();
                if (found.size() > MAX_TILE_ENTITIES) {
                    json.addProperty("error", found.size() + " tile entities in the box; at most "
                        + MAX_TILE_ENTITIES + " a call. Give a smaller box or 'blocks'.");
                    return ToolResult.error(json.get("error").getAsString());
                }
                Map<String, Integer> byBlock = new TreeMap<>();
                JsonArray failed = new JsonArray();
                int reloaded = 0;
                for (BlockPos pos : found) {
                    String id = idOf(world.getBlockState(pos));
                    if (dryRun) {
                        byBlock.merge(id, 1, Integer::sum);
                        continue;
                    }
                    String problem = reloadOne(world, pos);
                    if (problem == null) {
                        reloaded++;
                        byBlock.merge(id, 1, Integer::sum);
                    }
                    else if (failed.size() < 20) {
                        JsonObject row = GameJson.blockPos(pos);
                        row.addProperty("block", id);
                        row.addProperty("why", problem);
                        failed.add(row);
                    }
                }
                json.addProperty(dryRun ? "wouldReload" : "reloaded", dryRun ? found.size() : reloaded);
                JsonObject kinds = new JsonObject();
                for (Map.Entry<String, Integer> entry : byBlock.entrySet()) {
                    kinds.addProperty(entry.getKey(), entry.getValue());
                }
                json.add("byBlock", kinds);
                if (failed.size() > 0) {
                    json.add("failed", failed);
                }
                return ToolResult.structured(json);
            }));
    }

    /**
     * One tile entity through the reload cycle. Game thread. Returns why it failed, or null. On a
     * failure after removal the old object is put back, so a tile entity is never lost.
     */
    private static String reloadOne(WorldServer world, BlockPos pos) {
        TileEntity old = world.getTileEntity(pos);
        if (old == null) {
            return "no tile entity there any more";
        }
        NBTTagCompound nbt;
        try {
            nbt = old.writeToNBT(new NBTTagCompound());
        }
        catch (RuntimeException e) {
            return "could not save its NBT: " + e;
        }
        try {
            old.onChunkUnload();
        }
        catch (RuntimeException e) {
            // Its unload hook failing is no reason not to reload it; the chunk would have gone on too.
        }
        world.removeTileEntity(pos);
        TileEntity fresh;
        try {
            fresh = TileEntity.create(world, nbt);
        }
        catch (RuntimeException e) {
            fresh = null;
        }
        if (fresh == null) {
            old.validate();
            world.setTileEntity(pos, old);
            return "could not be re-created from its NBT (id " + nbt.getString("id") + "); the old one was put back";
        }
        world.setTileEntity(pos, fresh);
        world.markChunkDirty(pos, fresh);
        IBlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 3);
        return null;
    }

    private static String idOf(IBlockState state) {
        ResourceLocation id = state.getBlock().getRegistryName();
        return id == null ? "unknown" : id.toString();
    }
}
