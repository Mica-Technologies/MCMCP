package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
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
        if (!context.getBoolean("load", false)) {
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
            ChunkLoadGovernor.release(outcome.holdId);
        }
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
                + "generated are listed and left alone. A hold ends when nothing has used it for "
                + "chunks.holdIdleSeconds, at its lease, on server_release_loaded, or when you leave the "
                + "server; reading or writing in it, or op 'renew', keeps it. op 'list' shows your holds.\n\n"
                + "Limited per caller (chunks.maxHeldChunksPerCaller, 512 by default, about 360 x 360 "
                + "blocks) and for the server. When the server is busy the call fails with a structured "
                + "'busy' block carrying retryAfterMs: wait that long and retry.")
            .schema(JsonSchema.object()
                .enumeration("op", "hold (default), renew or list.", "hold", "renew", "list")
                .integer("x", "hold: X of one corner of the block box.")
                .integer("z", "hold: Z of one corner.")
                .integer("toX", "hold: X of the opposite corner. Defaults to x.")
                .integer("toZ", "hold: Z of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .string("id", "renew: the hold to extend.")
                .integer("maxSeconds", "hold, renew: the lease, from now, in seconds. Defaults to "
                    + "chunks.holdMaxSeconds.", 60, 3600)
                .build())
            .serverOnly()
            .handler(context -> {
                String op = context.getString("op", "hold");
                String caller = ChunkLoadGovernor.callerOf(context.getPrincipal());
                boolean everyone = !context.getPrincipal().isCompanion();
                if ("list".equals(op)) {
                    JsonObject status = context.onGameThread(() -> ChunkLoadGovernor.status(everyone ? null : caller));
                    return ToolResult.structured(status);
                }
                long maxMillis = Math.min(McmcpConfig.getChunkHoldMaxSeconds(),
                    context.getBoundedInt("maxSeconds", McmcpConfig.getChunkHoldMaxSeconds(), 60, 3600)) * 1000L;
                if ("renew".equals(op)) {
                    final String id = context.getString("id", null);
                    if (id == null) {
                        return ToolResult.error("op 'renew' needs 'id': a hold id from op 'hold' or 'list'.");
                    }
                    String refusal = context.onGameThread(() -> ChunkLoadGovernor.renew(id, caller, maxMillis));
                    if (refusal != null) {
                        return ToolResult.error(refusal);
                    }
                    JsonObject json = new JsonObject();
                    json.addProperty("id", id);
                    json.addProperty("renewedForSeconds", maxMillis / 1000L);
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
                if (count > McmcpConfig.getChunkMaxHeldPerCaller()) {
                    return ToolResult.error("That box covers " + count + " chunks; one caller may hold "
                        + McmcpConfig.getChunkMaxHeldPerCaller() + " (chunks.maxHeldChunksPerCaller). Hold it in "
                        + "pieces, releasing each when you are done with it.");
                }
                int dimension = context.getInt("dimension", 0);
                ChunkLoadGovernor.Outcome outcome;
                try {
                    outcome = ChunkLoadGovernor.acquire(context, dimension, ChunkKeys.box(x1, z1, x2, z2), false,
                        McmcpConfig.getChunkHoldIdleSeconds() * 1000L, maxMillis);
                }
                catch (ChunkLoadGovernor.Busy busy) {
                    return busy.toResult();
                }
                JsonObject json = new JsonObject();
                json.addProperty("id", outcome.holdId);
                json.addProperty("dimension", dimension);
                json.add("chunks", outcome.toJson());
                json.addProperty("idleSeconds", McmcpConfig.getChunkHoldIdleSeconds());
                json.addProperty("leaseSeconds", maxMillis / 1000L);
                return ToolResult.structured(json);
            })
            .build());
    }

    private static void registerReleaseLoaded() {
        McpRegistry.registerTool(McpTool.named("server_release_loaded")
            .title("Release a held region")
            .description("End a hold from server_keep_loaded, or all of yours. Chunks no other hold needs are "
                + "handed back to the server, and the ones MCMCP loaded are unloaded straight away unless a "
                + "player can see them.")
            .schema(JsonSchema.object()
                .string("id", "The hold to release.")
                .bool("all", "Release every hold of yours.")
                .build())
            .serverOnly()
            .idempotent()
            .handler(context -> {
                String caller = ChunkLoadGovernor.callerOf(context.getPrincipal());
                if (context.getBoolean("all", false)) {
                    ChunkLoadGovernor.releaseCaller(caller);
                    return ToolResult.text("Released every hold of yours.");
                }
                final String id = context.getString("id", null);
                if (id == null) {
                    return ToolResult.error("Pass 'id', or all: true.");
                }
                String owner = context.onGameThread(() -> {
                    JsonObject status = ChunkLoadGovernor.status(null);
                    for (com.google.gson.JsonElement hold : status.getAsJsonArray("holds")) {
                        if (id.equals(hold.getAsJsonObject().get("id").getAsString())) {
                            return hold.getAsJsonObject().get("owner").getAsString();
                        }
                    }
                    return null;
                });
                if (owner == null) {
                    return ToolResult.error("There is no hold '" + id + "'.");
                }
                if (context.getPrincipal().isCompanion() && !owner.equals(caller)) {
                    return ToolResult.error("Hold '" + id + "' is not yours.");
                }
                ChunkLoadGovernor.release(id);
                return ToolResult.text("Released " + id + ".");
            })
            .build());
    }
}
