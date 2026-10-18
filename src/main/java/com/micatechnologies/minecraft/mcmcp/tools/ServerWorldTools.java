package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.NbtProjection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
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
                + "light levels and hardness. When the chunk is not loaded it reads nothing and says "
                + "loaded=false, with no block or metadata, since an unloaded position would otherwise "
                + "read as empty air; pass load: true to load it for the read.\n\n"
                + "Two fields appear only when they have something to say. 'actualState' is the state "
                + "the block is really drawn and interacted with, present when it differs from the "
                + "stored 'state' — blocks that connect or mount to their neighbours keep placeholder "
                + "values in the chunk, commonly 'every connection present', so 'state' alone "
                + "describes a lone fence as connected on all four sides and cannot distinguish two "
                + "cases being compared. 'boundingBox' is the block-relative selection box, present "
                + "when the block is not a full cube.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .integer("x", "Block X coordinate.")
                .integer("y", "Block Y coordinate, 0-255.")
                .integer("z", "Block Z coordinate.")
                .integer("dimension", "Dimension id: 0 overworld, -1 nether, 1 end. Defaults to 0."))
                .bool("nbt", "Also return 'blockEntity':the tile entity's full saved NBT, or "
                    + "present=false when the block has none. Off by default; a machine's tag can "
                    + "be kilobytes. A Chisels & Bits block's voxel blob comes decoded, as "
                    + "'chisel': its bit counts by state and the box they fill.")
                .bool("chisel_grid", "With nbt, on a Chisels & Bits block: also return chisel.grid, every bit as "
                    + "16 layers (y 0 up) of 16 rows (z 0 = north first) of 16 characters (x 0 = "
                    + "west first), with a legend; about 5 KB. The bit counts and bounds come "
                    + "without it.")
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
                final boolean chiselGrid = context.getBoolean("chisel_grid", false);

                final boolean load = context.getBoolean("load", false);
                return ServerChunkTools.withChunks(context, dimension,
                    java.util.Collections.singleton(com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys.ofBlock(x, z)),
                    outcome -> {
                    JsonObject block = context.onGameThread(new Callable<JsonObject>() {
                        @Override
                        public JsonObject call() {
                            WorldServer world = requireWorld(dimension);
                            BlockPos pos = new BlockPos(x, y, z);
                            JsonObject json = GameJson.block(world, pos);
                            boolean loaded = json.get("loaded").getAsBoolean();
                            if (nbt && loaded) {
                                json.add("blockEntity", GameJson.blockEntity(world, pos, chiselGrid));
                            }
                            json.addProperty("dimension", dimension);
                            if (!loaded) {
                                // Said in words as well: a script that defaults a missing metadata to 0
                                // writes that 0 back (#61).
                                json.addProperty("note", load
                                    ? "The chunk could not be loaded (it may never have been generated); nothing was read."
                                    : "The chunk is not loaded, so nothing was read: there is no block or metadata "
                                        + "here. Pass load: true to load it for the read.");
                                return json;
                            }
                            String biome = GameJson.biomeId(world, pos);
                            if (biome != null) {
                                json.addProperty("biome", biome);
                            }
                            return json;
                        }
                    });
                    return ToolResult.structured(block);
                    });
            })
            .build());
    }

    private static void registerFindBlocks() {
        McpRegistry.registerTool(McpTool.named("server_find_blocks")
            .title("Find blocks")
            .description("Find blocks and return their positions. Two shapes: a cube round x, y, z with "
                + "'radius', nearest first; or a box, x, y, z to toX, toY, toZ (a building, a district), "
                + "listing every match with its metadata as [x, y, z, palette index], paged with 'cursor'. "
                + "'block' is one id; 'blocks' takes several ids, namespaces ending in ':' or prefixes "
                + "ending in '*', e.g. ['csm:toilet_*', 'csm:urinal_*']. Unloaded chunks are skipped; in box "
                + "mode pass load: true to search them too.")
            .schema(JsonSchema.object()
                .string("block", "Namespaced block id to search for, e.g. 'minecraft:diamond_ore'.")
                .array("blocks", "Several blocks to search for at once: ids, namespaces ending in ':', or "
                    + "prefixes ending in '*'.", Json.obj("type", "string"))
                .integer("x", "Centre X, or in box mode one corner's X.")
                .integer("y", "Centre Y, or in box mode one corner's Y (default 0).")
                .integer("z", "Centre Z, or in box mode one corner's Z.")
                .integer("toX", "Box mode: the opposite corner's X.")
                .integer("toY", "Box mode: the opposite corner's Y. Defaults to 255, or y if y is given.")
                .integer("toZ", "Box mode: the opposite corner's Z.")
                .integer("radius", "Cube mode: search radius in blocks. Cost grows with the cube of this value.",
                    1, 128)
                .integer("dimension", "Dimension id. Defaults to 0.")
                .integer("limit", "Positions to return: cube mode up to 500 (default 64); box mode a page, up to "
                    + ServerAreaTools.MAX_FIND_LIMIT + " (default 1000).", 1, ServerAreaTools.MAX_FIND_LIMIT)
                .string("cursor", "Box mode: the 'cursor' a previous page returned, to continue from it.")
                .bool("load", "Box mode: load the box's unloaded chunks for this call, and release them afterwards.")
                .required("x", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                List<String> patterns = new ArrayList<>(Json.getStringList(context.getArguments(), "blocks"));
                String single = context.getString("block", null);
                if (single != null && !single.trim().isEmpty()) {
                    patterns.add(single.trim());
                }
                if (patterns.isEmpty()) {
                    return ToolResult.error("Name what to find with 'block', or several with 'blocks'.");
                }
                // An exact id that names nothing is a typo; a namespace or prefix may simply match nothing.
                // Exact ids are matched by their full registry name, so 'stone' finds minecraft:stone.
                for (int i = 0; i < patterns.size(); i++) {
                    String pattern = patterns.get(i).trim();
                    if (pattern.endsWith(":") || pattern.endsWith("*")) {
                        continue;
                    }
                    Block block = BlockIds.resolve(pattern);
                    if (block == null || block.getRegistryName() == null) {
                        return ToolResult.error(BlockIds.describeUnknown(pattern, "search for"));
                    }
                    patterns.set(i, block.getRegistryName().toString());
                }
                if (context.has("toX") || context.has("toY") || context.has("toZ")) {
                    return ServerAreaTools.findInBox(context, patterns);
                }
                if (!context.has("y")) {
                    return ToolResult.error("Give y with x and z for a cube round a point, or toX/toY/toZ for a box.");
                }
                final List<String> targets = patterns;
                final String blockId = String.join(", ", patterns);
                final Map<Integer, Boolean> matching = new HashMap<>();

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
                                        IBlockState state = world.getBlockState(pos);
                                        Boolean match = matching.get(Block.getStateId(state));
                                        if (match == null) {
                                            ResourceLocation name = state.getBlock().getRegistryName();
                                            match = state.getBlock() != Blocks.AIR && NbtProjection.matches(
                                                name == null ? "unknown" : name.toString(), targets);
                                            matching.put(Block.getStateId(state), match);
                                        }
                                        if (match) {
                                            JsonObject hit = GameJson.blockPos(pos);
                                            if (targets.size() > 1 || targets.get(0).endsWith(":")
                                                || targets.get(0).endsWith("*")) {
                                                ResourceLocation name = state.getBlock().getRegistryName();
                                                hit.addProperty("block", name == null ? "unknown" : name.toString());
                                            }
                                            hit.addProperty("metadata", state.getBlock().getMetaFromState(state));
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
                .integer("radius", "Search radius in blocks. Default 16.", 1, GameJson.MAX_ENTITY_RADIUS)
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
                final int requested = context.getInt("radius", 16);
                final int radius = context.getBoundedInt("radius", 16, 1, GameJson.MAX_ENTITY_RADIUS);
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
                        if (requested != radius) {
                            json.addProperty("radiusRequested", requested);
                        }
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
                String denied = context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
                    "Direct world edits are disabled by "
                        + "permissions.allowWorldEdits in the MCMCP config. Ask the server operator to "
                        + "enable it, or achieve the change through player actions or a command.");
                if (denied != null) {
                    return ToolResult.error(denied);
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

                // Only with undo.recordSingleBlockWrites: one block is one file, which is an audit
                // trail somebody chose rather than a default.
                final UndoPoints.Recorder recorder = ServerUndoTools.start(context, true);
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        WorldServer world = requireWorld(dimension);
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!world.isBlockLoaded(pos)) {
                            // Writing would load the chunk, or generate it; never this tool's call.
                            JsonObject refused = new JsonObject();
                            refused.addProperty("unloaded", true);
                            return refused;
                        }
                        JsonObject previous = GameJson.block(world, pos);
                        UndoPoints.Before was = recorder == null ? null : recorder.capture(world, pos);

                        IBlockState state = target.getStateFromMeta(metadata);
                        // Flag 3 = update neighbours + send to clients. Anything less leaves
                        // connected players seeing the old block until they reload the chunk.
                        boolean changed = SkyLight.setBlockState(world, pos, state, 3);
                        boolean wrote = changed;

                        JsonObject json = new JsonObject();
                        json.addProperty("changed", changed);
                        if (nbt != null) {
                            TileEntityNbt.Outcome outcome = TileEntityNbt.merge(world, pos, nbt);
                            wrote |= outcome == TileEntityNbt.Outcome.APPLIED;
                            json.addProperty("nbt", outcome.name().toLowerCase(java.util.Locale.ROOT));
                        }
                        if (recorder != null && wrote) {
                            recorder.record(pos, was, world.getBlockState(pos));
                        }
                        json.add("previous", previous);
                        json.add("current", GameJson.block(world, pos));
                        return json;
                    }
                });
                if (recorder != null) {
                    ServerUndoTools.finish(context, recorder, "server_set_block", dimension, null, null,
                        result, null);
                }
                if (result.has("unloaded")) {
                    return ToolResult.error("x=" + x + ", z=" + z + " is in a chunk that is not loaded, so nothing "
                        + "was written. Hold it with server_keep_loaded first, or write with server_set_blocks "
                        + "and load: true.");
                }
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
