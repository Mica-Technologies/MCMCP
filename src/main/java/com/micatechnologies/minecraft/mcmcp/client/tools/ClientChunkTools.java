package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.client.ChunkRenderHealth;
import com.micatechnologies.minecraft.mcmcp.client.ClientFrameClock;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * {@code client_reload_chunks} (#207): what F3+A does, for an agent, with a look first at whether it
 * is needed. After a long teleport the world round the player sometimes stays empty until a person
 * presses F3+A, and an unattended agent saw only a void screenshot. See {@link ChunkRenderHealth}.
 */
@SideOnly(Side.CLIENT)
public final class ClientChunkTools {

    private static final int MAX_RADIUS = 6;
    private static final int MAX_WAIT_SECONDS = 30;

    private ClientChunkTools() {
    }

    public static void register() {
        ChunkRenderHealth.register();
        McpRegistry.registerTool(McpTool.named("client_reload_chunks")
            .title("Reload the chunks round the player")
            .description("Check that the chunks round the player arrived and were drawn, and fix what did not: "
                + "rebuild the chunk meshes, as F3+A does, when sections holding blocks were never built, and ask "
                + "the server (through the companion) to send again the chunks that never arrived. Use it when "
                + "a screenshot after a teleport shows void where the world should be. Returns the check before "
                + "and after, what was done, and the client's own check after its last teleport. 'force' "
                + "rebuilds whatever the check finds.")
            .schema(JsonSchema.object()
                .integer("radius", "Chunks each side of the player's to check. Default 2.", 1, MAX_RADIUS)
                .bool("force", "Rebuild the meshes even when the check finds nothing wrong. Default false.")
                .bool("resend", "Ask the server to resend chunks that are absent. Default true.")
                .integer("waitSeconds", "Wait up to this long afterwards for the chunks to arrive and be built. "
                    + "Default 10.", 0, MAX_WAIT_SECONDS)
                .build())
            .clientOnly()
            .idempotent()
            .handler(ClientChunkTools::reload)
            .build());
    }

    private static ToolResult reload(final ToolContext context) throws Exception {
        final int radius = context.getBoundedInt("radius", 2, 1, MAX_RADIUS);
        final boolean force = context.getBoolean("force", false);
        final boolean resend = context.getBoolean("resend", true);
        final int waitSeconds = context.getBoundedInt("waitSeconds", 10, 0, MAX_WAIT_SECONDS);

        final Object[] first = context.onGameThread(() -> {
            Minecraft mc = ClientStateTools.requireInWorld();
            ChunkRenderHealth.Report report = ChunkRenderHealth.check(mc, radius);
            boolean rebuilt = force || report.needsRebuild();
            if (rebuilt) {
                ChunkRenderHealth.rebuild(mc);
            }
            boolean multiplayer = mc.getIntegratedServer() == null && mc.getSession() != null;
            return new Object[] {report.toJson(), rebuilt, report.absent, multiplayer,
                multiplayer ? mc.getSession().getUsername() : null,
                MathHelper.floor(mc.player.posX), MathHelper.floor(mc.player.posZ)};
        });
        JsonObject json = new JsonObject();
        json.add("before", (JsonObject) first[0]);
        JsonArray actions = new JsonArray();
        if ((Boolean) first[1]) {
            actions.add("rebuilt chunk meshes (F3+A)");
        }
        int absent = (Integer) first[2];
        if (resend && absent > 0) {
            if (!(Boolean) first[3]) {
                actions.add("chunks absent in a singleplayer world: nothing to ask a server for");
            }
            else {
                JsonObject resent = ChunkRenderHealth.requestResend((String) first[4], (Integer) first[5],
                    (Integer) first[6], radius, context.getCaller().stamp());
                actions.add(resent == null
                    ? "could not ask the server to resend the absent chunks (no companion access, or it refused); "
                        + "client_rejoin makes the server resend everything"
                    : "asked the server to resend: " + Json.getInt(resent, "resent", 0) + " chunk(s) resent");
            }
        }
        json.add("actions", actions);

        if (actions.size() > 0 && waitSeconds > 0) {
            long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
            int quiet = 0;
            JsonObject after = null;
            while (System.currentTimeMillis() < deadline) {
                ClientFrameClock.await(2, 0, 1000L);
                Object[] now = context.onGameThread(() -> {
                    Minecraft mc = ClientStateTools.requireInWorld();
                    ChunkRenderHealth.Report report = ChunkRenderHealth.check(mc, radius);
                    // Not "nothing queued": sections out of view stay marked for a rebuild that only
                    // happens once they come into view, so that never empties.
                    return new Object[] {report.toJson(), report.healthy() && mc.renderGlobal.hasNoChunkUpdates()};
                });
                after = (JsonObject) now[0];
                quiet = (Boolean) now[1] ? quiet + 1 : 0;
                if (quiet >= 2) {
                    break;
                }
            }
            if (after != null) {
                json.add("after", after);
            }
        }
        JsonObject teleport = ChunkRenderHealth.lastTeleportCheck();
        if (teleport != null) {
            json.add("lastTeleportCheck", teleport);
        }
        return ToolResult.structured(json);
    }
}
