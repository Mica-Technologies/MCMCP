package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.Set;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketUnloadChunk;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * Sends a player's client the chunks it is no longer being sent, so a detached view of them is of the
 * world as it is (#187).
 *
 * <h2>Why a client's copy goes stale</h2>
 *
 * A server sends a player block changes and tile-entity data only for the chunks within its view
 * distance of them. A client can hold chunks farther out than that, as they were when the player was
 * last near, and it draws them without complaint: a {@code client_screenshot} 'from' view showed bare
 * grass where a street of houses had been built, and two doors closed that were open, and an agent
 * reported finished work as not built. Nothing on the client can tell, because the client's copy is
 * all it has.
 *
 * <h2>What is sent</h2>
 *
 * For each loaded chunk in the box that the server is not sending the player: an unload, then the
 * chunk whole, as the server would on the player walking back into range. The unload comes first
 * because a client replaces a chunk it holds without unloading it, which leaves the old chunk's tile
 * entities in the world's lists. Chunks the player is being sent are current and are left alone:
 * unloading one would take its entities off the client until the player left and came back.
 *
 * <p>Nothing in the world changes, so this is read-only. It costs the server a chunk packet each, and
 * the player's connection the bytes.
 */
public final class ServerChunkRefreshTools {

    /** Most chunks one call covers: 11 x 11, a detached view's subject and the ground before it. */
    public static final int MAX_CHUNKS = com.micatechnologies.minecraft.mcmcp.chunkload.ViewRefresh.MAX_CHUNKS;

    private ServerChunkRefreshTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_refresh_chunks")
            .title("Resend chunks to a player's client")
            .description("Send one player's client the chunks under a block box again, where the server has "
                + "stopped sending it changes: beyond the server's view distance of the player, a client can "
                + "still hold chunks as they were when the player was last near, and draws them stale. Each "
                + "such chunk that is loaded is sent whole (resent); chunks the player is being sent anyway "
                + "are current and left alone (live). Changes nothing in the world. client_screenshot does "
                + "this itself for a 'from' view, so call it only before reading the client's world another "
                + "way. Up to " + MAX_CHUNKS + " chunks a call; unloaded chunks are skipped unless load: true. "
                + "Returns the counts and the server's viewDistance in chunks.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .string("player", "The connected player whose client to send to.")
                .integer("x", "X of one corner of the block box.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Default x.")
                .integer("toZ", "Z of the opposite corner. Default z."))
                .required("player", "x", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(ServerChunkRefreshTools::refresh)
            .build());
    }

    private static ToolResult refresh(final ToolContext context) throws Exception {
        final String name = context.requireString("player");
        final int x1 = context.requireInt("x");
        final int z1 = context.requireInt("z");
        final int x2 = context.getInt("toX", x1);
        final int z2 = context.getInt("toZ", z1);
        long count = ChunkKeys.boxCount(x1, z1, x2, z2);
        if (count > MAX_CHUNKS) {
            return ToolResult.error("That box covers " + count + " chunks; one call resends up to " + MAX_CHUNKS
                + ". Send it in pieces.");
        }
        final Set<Long> chunks = ChunkKeys.box(x1, z1, x2, z2);
        final Integer dimension = context.onGameThread(() -> {
            EntityPlayerMP player = playerNamed(name);
            return player == null ? null : player.dimension;
        });
        if (dimension == null) {
            return ToolResult.error("No player named '" + name + "' is connected.");
        }
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            JsonObject json = context.onGameThread(() -> {
                JsonObject result = new JsonObject();
                EntityPlayerMP player = playerNamed(name);
                if (player == null || player.connection == null || player.dimension != dimension) {
                    result.addProperty("error", "'" + name + "' left the server or changed dimension.");
                    return result;
                }
                WorldServer world = player.getServerWorld();
                PlayerChunkMap watching = world.getPlayerChunkMap();
                int resent = 0;
                int live = 0;
                int unloaded = 0;
                for (long key : chunks) {
                    int cx = ChunkKeys.x(key);
                    int cz = ChunkKeys.z(key);
                    Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                    if (chunk == null) {
                        unloaded++;
                    }
                    else if (watching.isPlayerWatchingChunk(player, cx, cz)) {
                        live++;
                    }
                    else {
                        player.connection.sendPacket(new SPacketUnloadChunk(cx, cz));
                        player.connection.sendPacket(new SPacketChunkData(chunk, 65535));
                        resent++;
                    }
                }
                result.addProperty("player", player.getName());
                result.addProperty("chunks", chunks.size());
                result.addProperty("resent", resent);
                result.addProperty("live", live);
                result.addProperty("unloaded", unloaded);
                MinecraftServer server = ServerThreadBridge.server();
                if (server != null) {
                    result.addProperty("viewDistance", server.getPlayerList().getViewDistance());
                }
                return result;
            });
            if (json.has("error")) {
                return ToolResult.error(json.get("error").getAsString());
            }
            if (json.get("unloaded").getAsInt() > 0 && outcome == null) {
                json.addProperty("note", "Unloaded chunks were not sent: the client still has its old copy of "
                    + "them. Pass load: true.");
            }
            return ToolResult.structured(json);
        });
    }

    private static EntityPlayerMP playerNamed(String name) {
        MinecraftServer server = ServerThreadBridge.server();
        return server == null ? null : server.getPlayerList().getPlayerByUsername(name);
    }
}
