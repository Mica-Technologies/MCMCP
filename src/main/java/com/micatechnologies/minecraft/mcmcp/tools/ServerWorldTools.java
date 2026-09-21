package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.List;
import java.util.concurrent.Callable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * World inspection and mutation on the server endpoint.
 *
 * <p>Server-only by construction. These read authoritative world state across every loaded
 * dimension, which a client simply does not have — a client knows about the chunks streamed to it
 * and nothing else. The client endpoint gets its own, narrower world tools that are honest about
 * that limit.
 *
 * <p>Every scan here is bounded by {@code limits.maxScanRadius} and runs on the server thread, where
 * time spent is time the server is not ticking. The cost of a cubic scan grows with the cube of the
 * radius: the default 32 already means a quarter of a million block reads for one call.
 */
public final class ServerWorldTools {

    private ServerWorldTools() {
    }

    public static void register() {
        registerGetBlock();
        registerFindBlocks();
        registerNearbyEntities();
        registerWorldInfo();
        registerSetBlock();
        registerSaveWorld();
    }

    /**
     * Resolves a dimension id to its loaded world.
     *
     * @throws IllegalStateException if the dimension is not loaded — distinct from "does not exist",
     *                               and the message says so, because a model asking about the Nether
     *                               before anyone has been there needs to know it can be loaded
     *                               rather than that it is absent
     */
    static WorldServer requireWorld(int dimension) {
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null) {
            throw new IllegalStateException("No Minecraft server is running");
        }
        WorldServer world = server.getWorld(dimension);
        if (world == null) {
            throw new IllegalStateException("Dimension " + dimension + " is not currently loaded");
        }
        return world;
    }

    private static void registerGetBlock() {
        McpRegistry.registerTool(McpTool.named("server_get_block")
            .title("Get block")
            .description("Read the block at a world position, including its block state properties, "
                + "light levels and hardness. Reports loaded=false without reading if the chunk is not "
                + "loaded, since an unloaded position would otherwise read as empty air.\n\n"
                + "Two fields appear only when they have something to say. 'actualState' is the state "
                + "the block is really drawn and interacted with, present when it differs from the "
                + "stored 'state' — blocks that connect or mount to their neighbours keep placeholder "
                + "values in the chunk, commonly 'every connection present', so 'state' alone "
                + "describes a lone fence as connected on all four sides and cannot distinguish two "
                + "cases being compared. 'boundingBox' is the block-relative selection box, present "
                + "when the block is not a full cube.")
            .schema(JsonSchema.object()
                .integer("x", "Block X coordinate.")
                .integer("y", "Block Y coordinate, 0-255.")
                .integer("z", "Block Z coordinate.")
                .integer("dimension", "Dimension id: 0 overworld, -1 nether, 1 end. Defaults to 0.")
                .bool("nbt", "Also return 'blockEntity': the tile entity's full saved NBT, or "
                    + "present=false when the block has none. Off by default; a machine's tag can "
                    + "be kilobytes.")
                .required("x", "y", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                final int x = context.requireInt("x");
                final int y = context.requireInt("y");
                final int z = context.requireInt("z");
                final int dimension = context.getInt("dimension", 0);
                final boolean nbt = context.getBoolean("nbt", false);

                JsonObject block = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        WorldServer world = requireWorld(dimension);
                        BlockPos pos = new BlockPos(x, y, z);
                        JsonObject json = GameJson.block(world, pos);
                        if (nbt && json.get("loaded").getAsBoolean()) {
                            json.add("blockEntity", GameJson.blockEntity(world, pos));
                        }
                        json.addProperty("dimension", dimension);
                        String biome = GameJson.biomeName(world, pos);
                        if (biome != null) {
                            json.addProperty("biome", biome);
                        }
                        return json;
                    }
                });
                return ToolResult.structured(block);
            })
            .build());
    }

    private static void registerFindBlocks() {
        McpRegistry.registerTool(McpTool.named("server_find_blocks")
            .title("Find blocks")
            .description("Search a cubic area for blocks of a given type and return their positions, "
                + "nearest first. Use this to locate ores, structures or a specific machine rather than "
                + "reading positions one at a time. Unloaded chunks are skipped, never force-loaded.")
            .schema(JsonSchema.object()
                .string("block", "Namespaced block id to search for, e.g. 'minecraft:diamond_ore'.")
                .integer("x", "Centre X coordinate.")
                .integer("y", "Centre Y coordinate.")
                .integer("z", "Centre Z coordinate.")
                .integer("radius", "Search radius in blocks. Cost grows with the cube of this value.",
                    1, 128)
                .integer("dimension", "Dimension id. Defaults to 0.")
                .integer("limit", "Maximum number of positions to return.", 1, 500)
                .required("block", "x", "y", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                final String blockId = context.requireString("block");
                final Block target = BlockIds.resolve(blockId);
                if (target == null) {
                    return ToolResult.error(BlockIds.describeUnknown(blockId, "search for"));
                }

                final int centreX = context.requireInt("x");
                final int centreY = context.requireInt("y");
                final int centreZ = context.requireInt("z");
                final int radius = context.getBoundedInt("radius", 16, 1, McmcpConfig.getMaxScanRadius());
                final int dimension = context.getInt("dimension", 0);
                final int limit = context.getBoundedInt("limit", 64, 1, 500);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        WorldServer world = requireWorld(dimension);
                        JsonArray found = new JsonArray();
                        int scanned = 0;

                        // Expanding shells outward from the centre, so results come out
                        // nearest-first and hitting the limit early returns the closest matches
                        // rather than an arbitrary corner of the box.
                        for (int shell = 0; shell <= radius && found.size() < limit; shell++) {
                            for (int dx = -shell; dx <= shell && found.size() < limit; dx++) {
                                for (int dy = -shell; dy <= shell && found.size() < limit; dy++) {
                                    for (int dz = -shell; dz <= shell && found.size() < limit; dz++) {
                                        // Only the surface of each shell; interior points were
                                        // covered by a previous, smaller shell.
                                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)))
                                            != shell) {
                                            continue;
                                        }
                                        int y = centreY + dy;
                                        if (y < 0 || y > 255) {
                                            continue;
                                        }
                                        BlockPos pos = new BlockPos(centreX + dx, y, centreZ + dz);
                                        if (!world.isBlockLoaded(pos)) {
                                            continue;
                                        }
                                        scanned++;
                                        if (world.getBlockState(pos).getBlock() == target) {
                                            JsonObject hit = GameJson.blockPos(pos);
                                            hit.addProperty("distance",
                                                Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0D)
                                                    / 100.0D);
                                            found.add(hit);
                                        }
                                    }
                                }
                            }
                        }

                        JsonObject json = new JsonObject();
                        json.addProperty("block", blockId);
                        json.addProperty("dimension", dimension);
                        json.addProperty("searchRadius", radius);
                        json.addProperty("blocksScanned", scanned);
                        json.addProperty("matches", found.size());
                        json.addProperty("truncated", found.size() >= limit);
                        json.add("positions", found);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerNearbyEntities() {
        McpRegistry.registerTool(McpTool.named("server_nearby_entities")
            .title("Nearby entities")
            .description("List entities within a radius of a position: players, mobs, items on the "
                + "ground, vehicles. Optionally filter by namespaced entity type.")
            .schema(JsonSchema.object()
                .integer("x", "Centre X coordinate.")
                .integer("y", "Centre Y coordinate.")
                .integer("z", "Centre Z coordinate.")
                .integer("radius", "Search radius in blocks.", 1, 128)
                .integer("dimension", "Dimension id. Defaults to 0.")
                .string("type", "Namespaced entity type to filter by, e.g. 'minecraft:zombie'. "
                    + "Omit to return every entity.")
                .required("x", "y", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                final double centreX = context.requireDouble("x");
                final double centreY = context.requireDouble("y");
                final double centreZ = context.requireDouble("z");
                final int radius = context.getBoundedInt("radius", 16, 1, McmcpConfig.getMaxScanRadius());
                final int dimension = context.getInt("dimension", 0);
                final String typeFilter = context.getString("type", null);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        WorldServer world = requireWorld(dimension);
                        AxisAlignedBB box = new AxisAlignedBB(
                            centreX - radius, centreY - radius, centreZ - radius,
                            centreX + radius, centreY + radius, centreZ + radius);
                        List<Entity> entities = world.getEntitiesWithinAABB(Entity.class, box);

                        JsonArray array = new JsonArray();
                        for (Entity entity : entities) {
                            JsonObject json = GameJson.entity(entity);
                            if (typeFilter != null
                                && !typeFilter.equals(json.get("type").getAsString())) {
                                continue;
                            }
                            double dx = entity.posX - centreX;
                            double dy = entity.posY - centreY;
                            double dz = entity.posZ - centreZ;
                            json.addProperty("distance",
                                Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0D) / 100.0D);
                            array.add(json);
                        }

                        JsonObject json = new JsonObject();
                        json.addProperty("dimension", dimension);
                        json.addProperty("radius", radius);
                        json.addProperty("count", array.size());
                        json.add("entities", array);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerWorldInfo() {
        McpRegistry.registerTool(McpTool.named("server_world_info")
            .title("Server and world info")
            .description("Report server identity, tick performance, and the state of every loaded "
                + "dimension: time of day, weather, difficulty, loaded chunk and entity counts. Check "
                + "tick performance here before running expensive scans.")
            .schema(JsonSchema.noArguments())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        if (server == null) {
                            throw new IllegalStateException("No Minecraft server is running");
                        }

                        JsonObject json = new JsonObject();
                        json.addProperty("serverName", server.getServerModName());
                        json.addProperty("motd", server.getMOTD());
                        json.addProperty("dedicated", server.isDedicatedServer());
                        json.addProperty("players", server.getCurrentPlayerCount());
                        json.addProperty("maxPlayers", server.getMaxPlayers());
                        json.addProperty("uptimeTicks", server.getTickCounter());

                        // Mean tick time across the rolling 100-tick window the server keeps.
                        // Ticks are 50 ms apart, so anything above 50 ms per tick means the server
                        // is behind and every scheduled MCP task will be waiting on it.
                        long total = 0L;
                        for (long sample : server.tickTimeArray) {
                            total += sample;
                        }
                        double meanTickMillis = (total / (double) server.tickTimeArray.length) / 1_000_000.0D;
                        json.addProperty("meanTickMillis", Math.round(meanTickMillis * 100.0D) / 100.0D);
                        json.addProperty("ticksPerSecond",
                            Math.round(Math.min(20.0D, 1000.0D / Math.max(meanTickMillis, 0.001D)) * 100.0D)
                                / 100.0D);

                        JsonArray dimensions = new JsonArray();
                        for (WorldServer world : server.worlds) {
                            if (world == null) {
                                continue;
                            }
                            JsonObject dimension = GameJson.world(world);
                            dimension.addProperty("loadedChunks",
                                world.getChunkProvider().getLoadedChunkCount());
                            dimension.addProperty("entities", world.loadedEntityList.size());
                            dimension.add("spawnPoint", GameJson.blockPos(world.getSpawnPoint()));
                            dimensions.add(dimension);
                        }
                        json.add("dimensions", dimensions);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    /**
     * Writes a block directly into the world.
     *
     * <p>Gated behind {@code permissions.allowWorldEdits}, which is off by default, and marked
     * destructive so clients that require confirmation for destructive tools ask before running it.
     * A direct world write is a fundamentally different grant from "act as the player": it bypasses
     * claim protection, block-place events and every other mod's hooks, so a plot-protection mod
     * will not see it and a machine that expected an onBlockPlaced callback will not get one.
     */
    private static void registerSetBlock() {
        McpRegistry.registerTool(McpTool.named("server_set_block")
            .title("Set block")
            .description("Place or replace a block directly in the world. This bypasses block-place "
                + "events, so other mods' protection and machinery hooks do not run — prefer having a "
                + "player place the block where that matters. Disabled unless "
                + "permissions.allowWorldEdits is enabled in the MCMCP config.")
            .schema(JsonSchema.object()
                .string("block", "Namespaced block id, e.g. 'minecraft:stone'.")
                .integer("x", "Block X coordinate.")
                .integer("y", "Block Y coordinate, 0-255.")
                .integer("z", "Block Z coordinate.")
                .integer("metadata", "Block metadata / state value. Defaults to 0.", 0, 15)
                .property("nbt", ServerBuildTools.nbtSchema("Tile-entity data merged into the block "
                    + "after it is placed, as /blockdata does."))
                .integer("dimension", "Dimension id. Defaults to 0.")
                .required("block", "x", "y", "z")
                .build())
            .serverOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowWorldEdits()) {
                    return ToolResult.error("Direct world edits are disabled by "
                        + "permissions.allowWorldEdits in the MCMCP config. Ask the server operator to "
                        + "enable it, or achieve the change through player actions or a command.");
                }

                final String blockId = context.requireString("block");
                final Block target = BlockIds.resolve(blockId);
                if (target == null) {
                    return ToolResult.error(BlockIds.describeUnknown(blockId, "place"));
                }

                final int x = context.requireInt("x");
                final int y = context.requireInt("y");
                final int z = context.requireInt("z");
                final int metadata = context.getBoundedInt("metadata", 0, 0, 15);
                final int dimension = context.getInt("dimension", 0);

                if (y < 0 || y > 255) {
                    return ToolResult.error("y must be between 0 and 255; got " + y + ".");
                }
                final net.minecraft.nbt.NBTTagCompound nbt;
                try {
                    nbt = context.has("nbt") ? TileEntityNbt.parse(context.getArguments().get("nbt")) : null;
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        WorldServer world = requireWorld(dimension);
                        BlockPos pos = new BlockPos(x, y, z);
                        JsonObject previous = GameJson.block(world, pos);

                        IBlockState state = target.getStateFromMeta(metadata);
                        // Flag 3 = update neighbours + send to clients. Anything less leaves
                        // connected players seeing the old block until they reload the chunk.
                        boolean changed = world.setBlockState(pos, state, 3);

                        JsonObject json = new JsonObject();
                        json.addProperty("changed", changed);
                        if (nbt != null) {
                            json.addProperty("nbt", TileEntityNbt.merge(world, pos, nbt).name()
                                .toLowerCase(java.util.Locale.ROOT));
                        }
                        json.add("previous", previous);
                        json.add("current", GameJson.block(world, pos));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    /**
     * Forces a world save.
     *
     * <p>An integrated server has no {@code /save-all} command — that one is registered by the
     * dedicated server only — and its autosave interval is long enough that a model finishing a
     * task has no reason to believe its changes reached disk. The alternative that does work,
     * leaving the world, unloads it and leaves a client wedged at the main menu, which is a heavy
     * price for a flush.
     */
    private static void registerSaveWorld() {
        McpRegistry.registerTool(McpTool.named("server_save_world")
            .title("Save world")
            .description("Flush every loaded dimension to disk. Use this before ending a session or "
                + "killing the game, since anything written since the last autosave is otherwise "
                + "lost — an integrated server has no /save-all command and autosaves infrequently, "
                + "so changes a tool reported as applied can still not be on disk. Saving blocks the "
                + "server thread for as long as it takes, which on a large world is noticeable.")
            .schema(JsonSchema.object()
                .bool("players", "Also write player data. Defaults to true; there is rarely a "
                    + "reason to save the world without it.")
                .build())
            .serverOnly()
            .idempotent()
            .handler(context -> {
                final boolean players = context.getBoolean("players", true);
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        if (server == null) {
                            throw new IllegalStateException("No Minecraft server is running");
                        }
                        JsonArray dimensions = new JsonArray();
                        for (WorldServer world : server.worlds) {
                            if (world == null) {
                                continue;
                            }
                            JsonObject entry = new JsonObject();
                            entry.addProperty("dimension", world.provider.getDimension());
                            entry.addProperty("name", world.getWorldInfo().getWorldName());
                            dimensions.add(entry);
                        }

                        // The same call /save-all makes. Passing false rather than true leaves the
                        // familiar "Saving chunks for level ..." line in the log, which is the only
                        // externally visible confirmation the save happened.
                        server.saveAllWorlds(false);
                        if (players) {
                            server.getPlayerList().saveAllPlayerData();
                        }

                        JsonObject json = new JsonObject();
                        json.addProperty("saved", dimensions.size());
                        json.addProperty("players", players);
                        json.add("dimensions", dimensions);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }
}
