package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * {@code server_chisel_block}: Chisels &amp; Bits blocks built and written on the server (#97).
 *
 * <p>{@code client_chisel_block} works only where the client has the block loaded, through a command
 * block placed above the player, so every one of about a hundred shop signs across a district needed
 * the shared player parked beside it. The session encoded the blobs in Python instead and wrote them
 * with {@code server_apply_ops}, which took reverse-engineering C&amp;B's bit stream and the modded
 * blocks' numeric state ids, and its first try wrote unreadable blobs into the live world.
 *
 * <p>This builds the tag with {@link ChiselNbt}, the same code the client tool uses, and writes it
 * directly: no client, no command block, no player moved. C&amp;B is never named, so MCMCP keeps no
 * dependency on it; without it installed the tool says so.
 */
public final class ServerChiselTools {

    /** Most blocks one call chisels. */
    private static final int MAX_BLOCKS = 256;

    /** Positions listed with a problem in a result. */
    private static final int MAX_LISTED = 20;

    private ServerChiselTools() {
    }

    public static void register() {
        JsonObject coord = Json.obj("type", new com.google.gson.JsonPrimitive("integer"));
        JsonObject box = JsonSchema.object()
            .array("from", "[x, y, z], each 0-15.", coord)
            .array("to", "[x, y, z], each 0-15.", coord)
            .string("key", "A palette key, or '.' for air.")
            .required("from", "to", "key")
            .build();
        JsonObject face = JsonSchema.object()
            .enumeration("face", "The side, by the direction it faces. 'up' and 'down' are drawn as on a "
                + "map: top row north, left column west.", ChiselGrid.FACES)
            .stringArray("rows", "Up to 16 strings of up to 16 characters, top row first, left to right as "
                + "seen from outside.")
            .integer("depth", "How many bits deep each character goes. Default 1.", 1, 16)
            .required("face", "rows")
            .build();
        JsonObject block = JsonSchema.object()
            .integer("x", "Block X.")
            .integer("y", "Block Y, 0-255.")
            .integer("z", "Block Z.")
            .string("start", "This block's start, overriding the call's.")
            .array("layers", "This block's layers, as the call's 'layers'.", Json.obj("type", ChiselGrid.STRING_ARRAY))
            .array("boxes", "This block's boxes.", box)
            .array("faces", "This block's faces.", face)
            .required("x", "y", "z")
            .build();

        McpRegistry.registerTool(McpTool.named("server_chisel_block")
            .title("Chisel blocks on the server")
            .description("Set the bits of Chisels & Bits blocks on the server, with no client, command block or "
                + "player near: lettering, signage, relief. The same grids as client_chisel_block. A block is "
                + "16x16x16 bits; in every grid string ' ' leaves a bit as it was, '.' makes it air, and any "
                + "other character is a key of 'palette'. Edits apply in order: start, layers, boxes, faces. "
                + "'faces' draws on a side: 16 rows, top row first, as seen standing outside it, 'depth' bits "
                + "deep.\n\n"
                + "One block at x, y, z, or many in 'blocks', each with its own start, layers, boxes and faces "
                + "and the call's palette, under one undo point. Each block is read back and every bit "
                + "checked. Needs Chisels & Bits in the game, and permissions.allowWorldEdits.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .integer("x", "One block: X.")
                .integer("y", "One block: Y, 0-255.")
                .integer("z", "One block: Z.")
                .array("blocks", "Several blocks, at most " + MAX_BLOCKS + ", instead of x, y, z.", block)
                .integer("dimension", "Dimension id. Default 0.")
                .property("palette", ChiselGrid.paletteSchema())
                .string("start", "What the bits are before edits: 'empty' (air, default), 'existing' (the block "
                    + "there now: its bits if chiseled, or all of it if a plain block), or a palette key to fill "
                    + "with.")
                .array("layers", "Every bit: 16 layers, y 0 (bottom) first, each 16 strings, z 0 (north) first, "
                    + "each 16 characters, x 0 (west) first. Shorter rows and missing layers leave the rest "
                    + "unchanged.", Json.obj("type", ChiselGrid.STRING_ARRAY))
                .array("boxes", "Fill boxes of bits, inclusive corners.", box)
                .array("faces", "Draw on a side of the block.", face)
                .bool("dry_run", "Build and size everything, write nothing. Default false."))
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerChiselTools::chisel)
            .build());
    }

    /** One block to chisel: where, and its own drawing arguments. */
    private static final class Job {
        final BlockPos pos;
        final JsonObject args;
        final String start;

        Job(BlockPos pos, JsonObject args, String start) {
            this.pos = pos;
            this.args = args;
            this.start = start;
        }
    }

    private static ToolResult chisel(ToolContext context) throws Exception {
        String denied = context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
            "Chiseling writes to the world, which is disabled by permissions.allowWorldEdits in the MCMCP config.");
        if (denied != null) {
            return ToolResult.error(denied);
        }
        final JsonObject args = context.getArguments();
        final String defaultStart = context.getString("start", "empty");
        final List<Job> jobs = new ArrayList<>();
        JsonArray listed = Json.getArray(args, "blocks");
        if (listed != null && listed.size() > 0) {
            if (listed.size() > MAX_BLOCKS) {
                return ToolResult.error("'blocks' has " + listed.size() + " blocks; one call chisels at most "
                    + MAX_BLOCKS + ".");
            }
            for (JsonElement element : listed) {
                if (!element.isJsonObject()) {
                    return ToolResult.error("Each of 'blocks' is an object with x, y, z.");
                }
                JsonObject one = element.getAsJsonObject();
                if (!one.has("x") || !one.has("y") || !one.has("z")) {
                    return ToolResult.error("Each of 'blocks' needs x, y and z.");
                }
                jobs.add(new Job(new BlockPos(Json.getInt(one, "x", 0), Json.getInt(one, "y", 0), Json.getInt(one, "z", 0)),
                    one, Json.getString(one, "start", defaultStart)));
            }
        }
        else {
            if (!context.has("x") || !context.has("y") || !context.has("z")) {
                return ToolResult.error("Give x, y and z for one block, or 'blocks' for several.");
            }
            jobs.add(new Job(new BlockPos(context.requireInt("x"), context.requireInt("y"), context.requireInt("z")),
                args, defaultStart));
        }
        for (Job job : jobs) {
            if (job.pos.getY() < 0 || job.pos.getY() > 255) {
                return ToolResult.error("y must be 0-255; got " + job.pos.getY() + " at " + job.pos + ".");
            }
        }
        final int dimension = context.getInt("dimension", 0);
        final boolean dryRun = context.getBoolean("dry_run", false);
        final Set<Long> chunks = new LinkedHashSet<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Job job : jobs) {
            chunks.add(ChunkKeys.ofBlock(job.pos.getX(), job.pos.getZ()));
            minX = Math.min(minX, job.pos.getX());
            minZ = Math.min(minZ, job.pos.getZ());
            maxX = Math.max(maxX, job.pos.getX());
            maxZ = Math.max(maxZ, job.pos.getZ());
        }
        final int[] area = UndoPoints.imageArea(minX, minZ, maxX, maxZ);

        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final UndoPoints.Recorder recorder = dryRun ? null : ServerUndoTools.start(context, jobs.size() == 1);
            final BufferedImage beforeImage = recorder == null ? null : ServerUndoTools.drawImage(context, dimension, area);
            JsonObject json = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                if (!Block.REGISTRY.containsKey(new ResourceLocation(ChiselNbt.MOD_ID, "chiseled_rock"))) {
                    throw new IllegalStateException("Chisels & Bits is not installed in this game.");
                }
                Map<Character, IBlockState> palette = ChiselGrid.palette(args);
                JsonArray results = new JsonArray();
                JsonArray problems = new JsonArray();
                int written = 0;
                int verified = 0;
                for (Job job : jobs) {
                    JsonObject result;
                    try {
                        result = one(world, job, palette, dryRun, recorder);
                    }
                    catch (IllegalArgumentException e) {
                        result = GameJson.blockPos(job.pos);
                        result.addProperty("error", e.getMessage());
                    }
                    catch (RuntimeException e) {
                        // A mod hook threw (#81): this block is refused, the rest still go in.
                        result = GameJson.blockPos(job.pos);
                        result.addProperty("error", BlockWriteFaults.reason(e, job.pos, "a chiseled block"));
                    }
                    if (result.has("error") && problems.size() < MAX_LISTED) {
                        problems.add(result);
                    }
                    written += Json.getBoolean(result, "written", false) ? 1 : 0;
                    verified += Json.getBoolean(result, "verified", false) ? 1 : 0;
                    if (jobs.size() == 1) {
                        results.add(result);
                    }
                }
                JsonObject out = jobs.size() == 1 ? results.get(0).getAsJsonObject() : new JsonObject();
                if (jobs.size() > 1) {
                    out.addProperty("blocks", jobs.size());
                    out.addProperty(dryRun ? "wouldWrite" : "written", dryRun ? jobs.size() - problems.size() : written);
                    if (!dryRun) {
                        out.addProperty("verified", verified);
                    }
                    if (problems.size() > 0) {
                        out.add("problems", problems);
                    }
                }
                if (dryRun) {
                    out.addProperty("dryRun", true);
                }
                ChunkLoadGovernor.touch(dimension, chunks);
                out.add("chunks", outcome != null ? outcome.toJson() : ChunkLoadGovernor.describeUnloaded(world, chunks));
                return out;
            });
            if (recorder != null) {
                ServerUndoTools.finish(context, recorder, "server_chisel_block", dimension, area, beforeImage, json, null);
            }
            return json.has("error") && jobs.size() == 1 ? ToolResult.error(json.get("error").getAsString())
                .withStructured(json) : ToolResult.structured(json);
        });
    }

    /** Chisels one block. Game thread. */
    private static JsonObject one(WorldServer world, Job job, Map<Character, IBlockState> palette, boolean dryRun,
                                  @Nullable UndoPoints.Recorder recorder) {
        BlockPos pos = job.pos;
        JsonObject result = GameJson.blockPos(pos);
        if (!world.isBlockLoaded(pos)) {
            result.addProperty("error", "Its chunk is not loaded. Pass load: true, or hold it with server_keep_loaded.");
            return result;
        }
        IBlockState here = world.getBlockState(pos);
        String hereId = String.valueOf(here.getBlock().getRegistryName());
        IBlockState[] cells = new IBlockState[ChiselBlob.VOXELS];
        String start = job.start;
        if ("existing".equals(start)) {
            NBTTagCompound tag = ChiselNbt.isChiseledBlock(hereId) ? tileEntityTag(world, pos) : null;
            if (tag != null && ChiselNbt.hasBlob(tag)) {
                IBlockState[] existing = ChiselNbt.states(tag.getByteArray(ChiselNbt.TAG_BLOB));
                for (IBlockState state : existing) {
                    if (state == null) {
                        throw new IllegalArgumentException("Its bits include a state this server does not know; "
                            + "start from 'empty' instead.");
                    }
                }
                System.arraycopy(existing, 0, cells, 0, cells.length);
            }
            else {
                Arrays.fill(cells, here.getBlock() == Blocks.AIR || ChiselNbt.isChiseledBlock(hereId)
                    ? Blocks.AIR.getDefaultState() : here);
            }
        }
        else if ("empty".equals(start)) {
            Arrays.fill(cells, Blocks.AIR.getDefaultState());
        }
        else if (start.length() == 1 && palette.containsKey(start.charAt(0))) {
            Arrays.fill(cells, palette.get(start.charAt(0)));
        }
        else {
            throw new IllegalArgumentException("start must be 'empty', 'existing' or a palette key; got '" + start + "'.");
        }
        String drawn = ChiselGrid.draw(job.args, palette, cells);
        if (drawn != null) {
            throw new IllegalArgumentException(drawn);
        }

        List<IBlockState> states = new ArrayList<>();
        int[] indices = ChiselGrid.index(cells, states);
        ChiselNbt.Built built = ChiselNbt.build(states, indices);
        result.addProperty("block", built.blobBytes == 0 ? "minecraft:air" : built.blockId);
        result.addProperty("blobBytes", built.blobBytes);
        result.add("bits", ChiselGrid.counts(states, indices));
        if (dryRun) {
            return result;
        }

        UndoPoints.Before was = recorder == null ? null : recorder.capture(world, pos);
        // Neighbours are told once the bits are in, not of a chiseled block that is still empty.
        DeferredNeighbours deferred = new DeferredNeighbours();
        if (built.blobBytes == 0) {
            BlockWriteFaults.set(world, pos, Blocks.AIR.getDefaultState(), 3, recorder, was, deferred);
        }
        else {
            Block host = Block.getBlockFromName(built.blockId);
            if (host == null) {
                throw new IllegalArgumentException(built.blockId + " is not registered in this game.");
            }
            if (here.getBlock() != host) {
                BlockWriteFaults.set(world, pos, host.getDefaultState(), 3, recorder, was, deferred);
            }
            if (TileEntityNbt.merge(world, pos, built.tag) == TileEntityNbt.Outcome.NO_TILE_ENTITY) {
                deferred.flush(world);
                throw new IllegalArgumentException(built.blockId + " was placed but has no tile entity to hold the bits.");
            }
        }
        deferred.flush(world);
        if (recorder != null && was != null) {
            recorder.record(pos, was, world.getBlockState(pos));
        }
        result.addProperty("written", true);
        if (built.blobBytes > 0) {
            verify(world, pos, cells, result);
        }
        return result;
    }

    /** Reads the bits back and counts any that are not what was written. Game thread. */
    private static void verify(WorldServer world, BlockPos pos, IBlockState[] wanted, JsonObject result) {
        NBTTagCompound tag = tileEntityTag(world, pos);
        if (tag == null || !ChiselNbt.hasBlob(tag)) {
            result.addProperty("verifyError", "The block has no voxel data after the write.");
            return;
        }
        IBlockState[] stored = ChiselNbt.states(tag.getByteArray(ChiselNbt.TAG_BLOB));
        int wrong = 0;
        for (int i = 0; i < wanted.length; i++) {
            if (stored[i] != wanted[i]) {
                wrong++;
            }
        }
        result.addProperty("verified", wrong == 0);
        if (wrong > 0) {
            result.addProperty("bitsDiffering", wrong);
        }
    }

    @Nullable
    private static NBTTagCompound tileEntityTag(WorldServer world, BlockPos pos) {
        TileEntity tile = world.getTileEntity(pos);
        return tile == null ? null : tile.writeToNBT(new NBTTagCompound());
    }
}
