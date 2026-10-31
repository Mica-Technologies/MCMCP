package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.chunkload.ReadTiles;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpContent;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Keeping a region loaded without a player in it (#54), and the shared {@code load: true} path for
 * reads and writes. Every load goes through {@link ChunkLoadGovernor}.
 */
public final class ServerChunkTools {

    private ServerChunkTools() {
    }

    public static void register() {
        registerKeepLoaded();
        registerReleaseLoaded();
    }

    /** A lease for one call's chunks, released when the call is done with them. */
    public interface WithChunks<T> {

        T run(@Nullable ChunkLoadGovernor.Outcome outcome) throws Exception;
    }

    /**
     * Runs {@code body} with {@code chunks} held loaded when the call asked for {@code load: true}, and
     * releases them afterwards; otherwise runs it as it is, loading nothing.
     *
     * @return the body's result, or the governor's {@code busy} tool error
     */
    public static ToolResult withChunks(ToolContext context, int dimension, Set<Long> chunks,
                                        WithChunks<ToolResult> body) throws Exception {
        return withChunks(context, dimension, chunks, context.getBoolean("load", false), body);
    }

    /** As above, with whether to load decided by the caller rather than the 'load' argument. */
    public static ToolResult withChunks(ToolContext context, int dimension, Set<Long> chunks, boolean load,
                                        WithChunks<ToolResult> body) throws Exception {
        if (!load) {
            return body.run(null);
        }
        ChunkLoadGovernor.Outcome outcome;
        try {
            outcome = ChunkLoadGovernor.acquire(context, dimension, chunks, true,
                McmcpConfig.getChunkHoldIdleSeconds() * 1000L, McmcpConfig.getChunkHoldMaxSeconds() * 1000L);
        }
        catch (ChunkLoadGovernor.Busy busy) {
            return busy.toResult();
        }
        try {
            return body.run(outcome);
        }
        finally {
            ChunkLoadGovernor.release(outcome, "its call ended");
        }
    }

    /** One piece of a tiled read. */
    public interface TileReader {

        /** Reads the box's cells in {@code tile}, its chunks held. Returns false to stop early (a full page). */
        boolean read(ReadTiles.Tile tile) throws Exception;

        /** Whether the read still needs {@code tile}: false skips it without loading it (a page's cursor). */
        default boolean needs(ReadTiles.Tile tile) {
            return true;
        }
    }

    /** Builds the result once every piece is read, given the call's {@code chunks} block. */
    public interface TileFinish {

        ToolResult finish(JsonObject chunks) throws Exception;
    }

    /**
     * A read-only walk over a block box, with {@code load: true} cut into pieces that fit what the
     * caller may hold (#167): each piece is loaded, read and given back before the next, so the hold
     * caps pace a large read instead of refusing it. Each piece's reading still goes through the game
     * thread in slices, so the server ticks between them. Without {@code load}, or when the whole box
     * fits, it is one piece, as before.
     *
     * <p>For reads only. A write must hold all it touches until its undo point is taken.
     *
     * @param margin blocks round the box that must be loaded too ({@code near}'s radius); 0 for none
     */
    public static ToolResult walk(ToolContext context, final int dimension, int minX, int minZ, int maxX, int maxZ,
                                  int margin, TileReader reader, TileFinish finish) throws Exception {
        final ReadTiles.Tile whole = ReadTiles.whole(minX, minZ, maxX, maxZ, margin);
        final Set<Long> allChunks = whole.holdChunks();
        if (!context.getBoolean("load", false)) {
            if (reader.needs(whole)) {
                reader.read(whole);
            }
            return finish.finish(context.onGameThread(() -> {
                ChunkLoadGovernor.touch(dimension, allChunks);
                return ChunkLoadGovernor.describeUnloaded(ServerWorldTools.requireWorld(dimension), allChunks);
            }));
        }
        final String caller = ChunkLoadGovernor.holdCallerOf(context);
        int room = context.onGameThread(() -> ChunkLoadGovernor.roomFor(caller));
        List<ReadTiles.Tile> tiles = room < ReadTiles.MIN_ROOM ? null
            : ReadTiles.plan(minX, minZ, maxX, maxZ, margin, room);
        if (tiles == null || tiles.size() == 1) {
            // It fits, or there is too little room to cut it into: one hold, and a refusal names the cap.
            return withChunks(context, dimension, allChunks, true, outcome -> {
                if (reader.needs(whole)) {
                    reader.read(whole);
                }
                touch(context, dimension, allChunks);
                return finish.finish(outcome.toJson());
            });
        }
        ChunkLoadGovernor.Tally tally = new ChunkLoadGovernor.Tally(allChunks.size(), tiles.size(), room);
        for (ReadTiles.Tile tile : tiles) {
            if (!reader.needs(tile)) {
                continue;
            }
            ChunkLoadGovernor.Outcome outcome;
            try {
                outcome = ChunkLoadGovernor.acquire(context, dimension, tile.holdChunks(), true,
                    McmcpConfig.getChunkHoldIdleSeconds() * 1000L, McmcpConfig.getChunkHoldMaxSeconds() * 1000L);
            }
            catch (ChunkLoadGovernor.Busy busy) {
                ToolResult refused = busy.toResult();
                refused.withContent(McpContent.text("This read was "
                    + "cut into " + tiles.size() + " pieces of up to " + room + " chunks to fit your hold cap, and "
                    + "piece " + (tile.index + 1) + " could not be loaded; nothing of the read is returned."));
                return refused;
            }
            boolean more;
            try {
                more = reader.read(tile);
                tally.add(outcome);
            }
            finally {
                ChunkLoadGovernor.releaseAndWait(context, outcome, "its piece of a tiled read was read");
            }
            context.reportProgress(tile.index + 1, tiles.size(), "Read piece " + (tile.index + 1) + " of "
                + tiles.size());
            if (!more) {
                break;
            }
        }
        touch(context, dimension, allChunks);
        return finish.finish(tally.toJson());
    }

    /** Renews the idle timer of any lasting hold the read used: reading in a hold keeps it. */
    private static void touch(ToolContext context, int dimension, Set<Long> chunks) {
        context.onGameThread(() -> {
            ChunkLoadGovernor.touch(dimension, chunks);
            return null;
        });
    }

    /** The argument every read and write that can load takes. */
    public static JsonSchema loadArgument(JsonSchema schema) {
        return schema.bool("load", "Load the chunks this call needs that are not loaded, and release them "
            + "afterwards. Default false: cells in unloaded chunks are reported, not read or written. Loads "
            + "are paced; when the server is busy the call returns a tool error whose structured 'busy' "
            + "block has a retryAfterMs. Chunks never generated are never loaded.");
    }

    private static void registerKeepLoaded() {
        McpRegistry.registerTool(McpTool.named("server_keep_loaded")
            .title("Keep a region loaded")
            .description("Keep the chunks under a block box loaded with no player nearby, so reads and "
                + "writes there need no teleport. op 'hold' (default) loads what is not loaded, at a "
                + "rationed pace, and returns once it is all in memory, with a hold id. Chunks never "
                + "generated are listed and left alone. A hold ends when nothing has used it for its "
                + "idleSeconds, at the end of its lease (maxSeconds, up to chunks.holdLongestLeaseSeconds, 4 "
                + "hours by default), on server_release_loaded, or when you leave the server. Reading or "
                + "writing in it renews the idle timer but not the lease; op 'renew' renews both. A read "
                + "inside a hold whose lease ends within two minutes says so (holdLeaseEndsInSeconds). For a "
                + "long test nothing reads during, pass a long idleSeconds. op 'list' shows the holds, who "
                + "holds what (byCaller) and when each ends.\n\n"
                + "Limited per caller (chunks.maxHeldChunksPerCaller, 512 by default, about 360 x 360 "
                + "blocks; each agent and each job is its own caller) and for the server "
                + "(chunks.maxHeldChunks). A refusal names the cap, the count held and by how many callers, "
                + "and yours. When the server is busy the call fails with a structured 'busy' block carrying "
                + "retryAfterMs: wait that long and retry.")
            .schema(JsonSchema.object()
                .enumeration("op", "hold (default), renew or list.", "hold", "renew", "list")
                .integer("x", "hold: X of one corner of the block box.")
                .integer("z", "hold: Z of one corner.")
                .integer("toX", "hold: X of the opposite corner. Defaults to x.")
                .integer("toZ", "hold: Z of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .string("id", "renew: the hold to extend.")
                .integer("maxSeconds", "hold, renew: the lease, from now, in seconds. Defaults to "
                    + "chunks.holdMaxSeconds (900); at most chunks.holdLongestLeaseSeconds.", 60, 86400)
                .integer("idleSeconds", "hold, renew: release the hold after this long with no read or "
                    + "write in it. Defaults to chunks.holdIdleSeconds (120); at most the lease.", 10, 86400)
                .build())
            .serverOnly()
            .handler(context -> {
                String op = context.getString("op", "hold");
                String caller = ChunkLoadGovernor.holdCallerOf(context);
                boolean everyone = !context.getPrincipal().isCompanion();
                if ("list".equals(op)) {
                    JsonObject status = context.onGameThread(() -> ChunkLoadGovernor.status(everyone ? null : caller, caller));
                    return ToolResult.structured(status);
                }
                int longest = McmcpConfig.getChunkHoldLongestLeaseSeconds();
                int requested = context.getInt("maxSeconds", McmcpConfig.getChunkHoldMaxSeconds());
                long maxMillis = Math.max(60, Math.min(longest, requested)) * 1000L;
                long idleMillis = Math.max(10L, Math.min(maxMillis / 1000L,
                    context.getInt("idleSeconds", McmcpConfig.getChunkHoldIdleSeconds()))) * 1000L;
                if ("renew".equals(op)) {
                    final String id = context.getString("id", null);
                    if (id == null) {
                        return ToolResult.error("op 'renew' needs 'id': a hold id from op 'hold' or 'list'.");
                    }
                    String refusal = context.onGameThread(() -> ChunkLoadGovernor.renew(id, caller, maxMillis,
                        context.has("idleSeconds") ? idleMillis : -1L));
                    if (refusal != null) {
                        return ToolResult.error(refusal);
                    }
                    JsonObject json = new JsonObject();
                    json.addProperty("id", id);
                    json.addProperty("renewedForSeconds", maxMillis / 1000L);
                    if (requested > longest) {
                        json.addProperty("maxSecondsRequested", requested);
                    }
                    return ToolResult.structured(json);
                }
                if (!context.has("x") || !context.has("z")) {
                    return ToolResult.error("op 'hold' needs x and z (and toX, toZ for a box).");
                }
                int x1 = context.requireInt("x");
                int z1 = context.requireInt("z");
                int x2 = context.getInt("toX", x1);
                int z2 = context.getInt("toZ", z1);
                long count = ChunkKeys.boxCount(x1, z1, x2, z2);
                int perCaller = McmcpConfig.getChunkMaxHeldPerCaller();
                boolean callerBound = perCaller > 0 && perCaller < McmcpConfig.getChunkMaxHeld();
                int most = callerBound ? perCaller : McmcpConfig.getChunkMaxHeld();
                if (count > most) {
                    return ToolResult.error("That box covers " + count + " chunks; " + (callerBound
                        ? "one caller may hold " + most + " (chunks.maxHeldChunksPerCaller)"
                        : "MCMCP may hold " + most + " (chunks.maxHeldChunks)") + ". Hold it in "
                        + "pieces, releasing each when you are done with it.");
                }
                int dimension = context.getInt("dimension", 0);
                ChunkLoadGovernor.Outcome outcome;
                try {
                    outcome = ChunkLoadGovernor.acquire(context, dimension, ChunkKeys.box(x1, z1, x2, z2), false,
                        idleMillis, maxMillis);
                }
                catch (ChunkLoadGovernor.Busy busy) {
                    return busy.toResult();
                }
                JsonObject json = new JsonObject();
                json.addProperty("id", outcome.holdId);
                json.addProperty("dimension", dimension);
                json.add("chunks", outcome.toJson());
                json.addProperty("idleSeconds", idleMillis / 1000L);
                json.addProperty("leaseSeconds", maxMillis / 1000L);
                if (requested > longest) {
                    json.addProperty("maxSecondsRequested", requested);
                }
                return ToolResult.structured(json);
            })
            .build());
    }

    private static void registerReleaseLoaded() {
        McpRegistry.registerTool(McpTool.named("server_release_loaded")
            .title("Release a held region")
            .description("End a hold from server_keep_loaded, or all of yours. Chunks no other hold needs are "
                + "handed back to the server, and the ones MCMCP loaded are unloaded straight away unless a "
                + "player can see them. Returns the ids released and how many chunks you still hold.")
            .schema(JsonSchema.object()
                .string("id", "The hold to release.")
                .bool("all", "Release every server_keep_loaded hold of yours. Loads still running for your other calls are left alone.")
                .build())
            .serverOnly()
            .idempotent()
            .handler(context -> {
                String caller = ChunkLoadGovernor.holdCallerOf(context);
                if (context.getBoolean("all", false)) {
                    return ToolResult.structured(context.onGameThread(() -> released(caller,
                        ChunkLoadGovernor.releaseCallerNow(caller, "server_release_loaded all"))));
                }
                final String id = context.getString("id", null);
                if (id == null) {
                    return ToolResult.error("Pass 'id', or all: true.");
                }
                String owner = context.onGameThread(() -> ChunkLoadGovernor.ownerOf(id));
                if (owner == null) {
                    return ToolResult.error("There is no hold '" + id + "'.");
                }
                if (context.getPrincipal().isCompanion() && !owner.equals(caller)) {
                    return ToolResult.error("Hold '" + id + "' is not yours.");
                }
                return ToolResult.structured(context.onGameThread(() -> {
                    ChunkLoadGovernor.releaseNow(id, "server_release_loaded");
                    return released(caller, Collections.singletonList(id));
                }));
            })
            .build());
    }

    /** What server_release_loaded did. Server thread. */
    private static JsonObject released(String caller, List<String> ids) {
        JsonObject json = new JsonObject();
        json.add("released", Json.arrayOfStrings(ids));
        json.addProperty("heldChunks", ChunkLoadGovernor.heldChunks(caller));
        return json;
    }
}
