package com.micatechnologies.minecraft.mcmcp.spike;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.server.permission.PermissionAPI;

/** PHASE 0 SPIKE — throwaway server tools. See {@link Spike}. */
final class SpikeServerTools {

    @Nullable
    private static ForgeChunkManager.Ticket ticket;

    private SpikeServerTools() {
    }

    static void register() {
        McpRegistry.registerTool(McpTool.named("spike_server")
            .description("PHASE 0 SPIKE. op = tick_window | tickets | setblock_unloaded | flags | right_click "
                + "| container | permission.")
            .schema(JsonSchema.object()
                .string("op", "operation")
                .bool("reset", "tick_window: reset after reading")
                .string("sub", "tickets: request | force | status | release")
                .integer("x", "x / chunk x0").integer("y", "y").integer("z", "z / chunk z0")
                .integer("x1", "chunk x1").integer("z1", "chunk z1")
                .integer("flags", "setBlockState flags")
                .string("block", "block id")
                .string("player", "player name")
                .string("face", "face")
                .bool("sneak", "sneak")
                .string("node", "permission node")
                .build())
            .serverOnly()
            .offGameThread()
            .handler(SpikeServerTools::handle)
            .build());
    }

    private static ToolResult handle(final ToolContext ctx) {
        final String op = ctx.requireString("op");
        if ("tick_window".equals(op)) {
            JsonObject json = Spike.tickWindow();
            if (ctx.getBoolean("reset", false)) {
                Spike.resetTickWindow();
            }
            return ToolResult.structured(json);
        }
        JsonObject result = ctx.onGameThread(() -> {
            MinecraftServer server = ServerThreadBridge.server();
            WorldServer world = DimensionManager.getWorld(0);
            switch (op) {
                case "tickets":
                    return tickets(ctx, world);
                case "setblock_unloaded":
                    return setblockUnloaded(ctx, world);
                case "flags":
                    return flags(ctx, world);
                case "right_click":
                    return rightClick(ctx, server, world);
                case "container":
                    return container(ctx, server);
                case "permission":
                    return permission(ctx, server);
                default:
                    JsonObject e = new JsonObject();
                    e.addProperty("error", "unknown op " + op);
                    return e;
            }
        });
        return ToolResult.structured(result);
    }

    private static JsonObject tickets(ToolContext ctx, WorldServer world) {
        JsonObject json = new JsonObject();
        String sub = ctx.requireString("sub");
        ChunkProviderServer provider = world.getChunkProvider();
        json.addProperty("maxChunksPerTicket", ForgeChunkManager.getMaxChunkDepthFor(McmcpConstants.MOD_NAMESPACE));
        json.addProperty("maxTickets", ForgeChunkManager.getMaxTicketLengthFor(McmcpConstants.MOD_NAMESPACE));
        json.addProperty("ticketsAvailable", ForgeChunkManager.ticketCountAvailableFor(Mcmcp.instance, world));
        json.addProperty("loadedChunksBefore", provider.getLoadedChunkCount());
        if ("request".equals(sub)) {
            if (ticket != null) {
                ForgeChunkManager.releaseTicket(ticket);
            }
            ticket = ForgeChunkManager.requestTicket(Mcmcp.instance, world, ForgeChunkManager.Type.NORMAL);
            json.addProperty("ticket", ticket != null);
            if (ticket != null) {
                json.addProperty("ticketMaxDepth", ticket.getMaxChunkListDepth());
            }
        }
        else if ("force".equals(sub)) {
            int x0 = ctx.requireInt("x");
            int z0 = ctx.requireInt("z");
            int x1 = ctx.getInt("x1", x0);
            int z1 = ctx.getInt("z1", z0);
            JsonArray chunks = new JsonArray();
            long start = System.nanoTime();
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    JsonObject c = new JsonObject();
                    c.addProperty("cx", cx);
                    c.addProperty("cz", cz);
                    c.addProperty("generatedBefore", provider.isChunkGeneratedAt(cx, cz));
                    c.addProperty("loadedBefore", provider.getLoadedChunk(cx, cz) != null);
                    ForgeChunkManager.forceChunk(ticket, new ChunkPos(cx, cz));
                    c.addProperty("loadedAfterForce", provider.getLoadedChunk(cx, cz) != null);
                    c.addProperty("generatedAfterForce", provider.isChunkGeneratedAt(cx, cz));
                    chunks.add(c);
                }
            }
            json.addProperty("forceMs", (System.nanoTime() - start) / 1e6);
            json.add("chunks", chunks);
            json.addProperty("ticketChunkCount", ticket.getChunkList().size());
        }
        else if ("status".equals(sub)) {
            int x0 = ctx.requireInt("x");
            int z0 = ctx.requireInt("z");
            int x1 = ctx.getInt("x1", x0);
            int z1 = ctx.getInt("z1", z0);
            int loaded = 0;
            int generated = 0;
            int total = 0;
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    total++;
                    if (provider.getLoadedChunk(cx, cz) != null) {
                        loaded++;
                    }
                    if (provider.isChunkGeneratedAt(cx, cz)) {
                        generated++;
                    }
                }
            }
            json.addProperty("total", total);
            json.addProperty("loaded", loaded);
            json.addProperty("generated", generated);
            json.addProperty("persistentChunks", ForgeChunkManager.getPersistentChunksFor(world).size());
            json.addProperty("ticketChunkCount", ticket == null ? -1 : ticket.getChunkList().size());
        }
        else if ("release".equals(sub)) {
            if (ticket != null) {
                ForgeChunkManager.releaseTicket(ticket);
                ticket = null;
            }
            json.addProperty("released", true);
            json.addProperty("persistentChunks", ForgeChunkManager.getPersistentChunksFor(world).size());
        }
        json.addProperty("loadedChunksAfter", provider.getLoadedChunkCount());
        return json;
    }

    private static JsonObject setblockUnloaded(ToolContext ctx, WorldServer world) {
        JsonObject json = new JsonObject();
        int x = ctx.requireInt("x");
        int z = ctx.requireInt("z");
        int flags = ctx.getInt("flags", 3);
        BlockPos pos = new BlockPos(x, 100, z);
        ChunkProviderServer provider = world.getChunkProvider();
        int cx = x >> 4;
        int cz = z >> 4;
        json.addProperty("generatedBefore", provider.isChunkGeneratedAt(cx, cz));
        json.addProperty("loadedBefore", provider.getLoadedChunk(cx, cz) != null);
        json.addProperty("isBlockLoaded", world.isBlockLoaded(pos));
        long start = System.nanoTime();
        boolean changed = world.setBlockState(pos, Block.getBlockFromName("minecraft:glass").getDefaultState(), flags);
        json.addProperty("setBlockStateMs", (System.nanoTime() - start) / 1e6);
        json.addProperty("returned", changed);
        json.addProperty("generatedAfter", provider.isChunkGeneratedAt(cx, cz));
        json.addProperty("loadedAfter", provider.getLoadedChunk(cx, cz) != null);
        json.addProperty("blockAfter", String.valueOf(world.getBlockState(pos)));
        return json;
    }

    private static JsonObject flags(ToolContext ctx, WorldServer world) {
        JsonObject json = new JsonObject();
        BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
        int flags = ctx.getInt("flags", 3);
        Block block = Block.getBlockFromName(ctx.requireString("block"));
        final List<String> events = new ArrayList<>();
        IWorldEventListener listener = new Recorder(events);
        world.addEventListener(listener);
        try {
            IBlockState before = world.getBlockState(pos);
            world.setBlockState(pos, block.getDefaultState(), flags);
            json.addProperty("before", String.valueOf(before));
            json.addProperty("after", String.valueOf(world.getBlockState(pos)));
            JsonObject neighbours = new JsonObject();
            for (EnumFacing f : EnumFacing.values()) {
                neighbours.addProperty(f.getName(), String.valueOf(world.getBlockState(pos.offset(f))));
            }
            json.add("neighboursAfter", neighbours);
        }
        finally {
            world.removeEventListener(listener);
        }
        JsonArray arr = new JsonArray();
        for (String e : events) {
            arr.add(e);
        }
        json.add("listenerEvents", arr);
        return json;
    }

    private static JsonObject rightClick(ToolContext ctx, MinecraftServer server, WorldServer world) {
        JsonObject json = new JsonObject();
        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(ctx.requireString("player"));
        if (player == null) {
            json.addProperty("error", "no such player");
            return json;
        }
        BlockPos pos = new BlockPos(ctx.requireInt("x"), ctx.requireInt("y"), ctx.requireInt("z"));
        EnumFacing face = EnumFacing.byName(ctx.getString("face", "up"));
        ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);
        json.addProperty("playerDistance", Math.sqrt(player.getDistanceSq(pos)));
        json.addProperty("held", String.valueOf(stack));
        json.addProperty("blockBefore", String.valueOf(world.getBlockState(pos)));
        json.addProperty("containerBefore", player.openContainer.getClass().getName());
        boolean wasSneaking = player.isSneaking();
        player.setSneaking(ctx.getBoolean("sneak", false));
        EnumActionResult r;
        try {
            r = player.interactionManager.processRightClickBlock(player, world, stack, EnumHand.MAIN_HAND,
                pos, face, 0.5f, 0.5f, 0.5f);
        }
        finally {
            player.setSneaking(wasSneaking);
        }
        json.addProperty("result", String.valueOf(r));
        json.addProperty("blockAfter", String.valueOf(world.getBlockState(pos)));
        json.addProperty("blockAbove", String.valueOf(world.getBlockState(pos.offset(face))));
        json.addProperty("containerAfter", player.openContainer.getClass().getName());
        json.addProperty("heldAfter", String.valueOf(player.getHeldItem(EnumHand.MAIN_HAND)));
        return json;
    }

    private static JsonObject container(ToolContext ctx, MinecraftServer server) {
        JsonObject json = new JsonObject();
        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(ctx.requireString("player"));
        if (player == null) {
            json.addProperty("error", "no such player");
            return json;
        }
        json.addProperty("openContainer", player.openContainer.getClass().getName());
        json.addProperty("isInventory", player.openContainer == player.inventoryContainer);
        json.addProperty("canInteract", player.openContainer.canInteractWith(player));
        return json;
    }

    private static JsonObject permission(ToolContext ctx, MinecraftServer server) {
        JsonObject json = new JsonObject();
        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(ctx.requireString("player"));
        if (player == null) {
            json.addProperty("error", "no such player");
            return json;
        }
        String node = ctx.getString("node", Spike.NODE);
        json.addProperty("node", node);
        json.addProperty("has", PermissionAPI.hasPermission(player, node));
        json.addProperty("handler", PermissionAPI.getPermissionHandler().getClass().getName());
        json.addProperty("opEntry", server.getPlayerList().getOppedPlayers().getEntry(player.getGameProfile()) != null);
        json.addProperty("uuid", player.getUniqueID().toString());
        return json;
    }

    /** Records which IWorldEventListener callbacks a write produces. */
    private static final class Recorder implements IWorldEventListener {

        private final List<String> events;

        Recorder(List<String> events) {
            this.events = events;
        }

        @Override
        public void notifyBlockUpdate(World w, BlockPos pos, IBlockState o, IBlockState n, int flags) {
            events.add("notifyBlockUpdate " + pos + " " + o + " -> " + n + " flags=" + flags);
        }

        @Override
        public void notifyLightSet(BlockPos pos) {
            events.add("notifyLightSet " + pos);
        }

        @Override
        public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
            events.add("markBlockRangeForRenderUpdate");
        }

        @Override
        public void playSoundToAllNearExcept(@Nullable EntityPlayer p, SoundEvent s, SoundCategory c,
                                             double x, double y, double z, float v, float pitch) {
        }

        @Override
        public void playRecord(SoundEvent s, BlockPos pos) {
        }

        @Override
        public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z,
                                  double xs, double ys, double zs, int... p) {
        }

        @Override
        public void spawnParticle(int id, boolean ignoreRange, boolean min, double x, double y, double z,
                                  double xs, double ys, double zs, int... p) {
        }

        @Override
        public void onEntityAdded(Entity e) {
        }

        @Override
        public void onEntityRemoved(Entity e) {
        }

        @Override
        public void broadcastSound(int id, BlockPos pos, int data) {
        }

        @Override
        public void playEvent(@Nullable EntityPlayer p, int type, BlockPos pos, int data) {
        }

        @Override
        public void sendBlockBreakProgress(int id, BlockPos pos, int progress) {
        }
    }
}
