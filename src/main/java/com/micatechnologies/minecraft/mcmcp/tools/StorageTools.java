package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.Arrays;

/** {@code game_storage}: what MCMCP keeps in this game's folder, and clearing the old. See {@link GameStorage}. */
public final class StorageTools {

    private StorageTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("game_storage")
            .title("MCMCP's files in this game")
            .description("How much room MCMCP's own files take in this game's folder — dumps, the "
                + "screenshots and maps it named, undo points, marks — and removing those older than "
                + "a number of days. The orchestrator app uses this for its Data tab.\n\n"
                + "op 'usage' (default) lists each kind's size, count and oldest item. op 'expire' "
                + "removes one kind's items older than older_than_days.")
            .schema(JsonSchema.object()
                .enumeration("op", "What to do. Defaults to 'usage'.", "usage", "expire")
                .enumeration("kind", "expire: which kind.", GameStorage.KINDS)
                .integer("older_than_days", "expire: remove what is older than this.", 1, 3650)
                .build())
            .destructive()
            .offGameThread()
            .handler(context -> {
                String op = context.getString("op", "usage");
                if ("usage".equals(op)) {
                    JsonObject json = new JsonObject();
                    json.addProperty("gameDirectory", McmcpPaths.gameDirectory().getAbsolutePath());
                    json.add("kinds", GameStorage.usage(McmcpPaths.gameDirectory()));
                    return ToolResult.structured(json);
                }
                if (!"expire".equals(op)) {
                    return ToolResult.error("Unknown op '" + op + "'; use usage or expire.");
                }
                String kind = context.getString("kind", null);
                if (kind == null || !Arrays.asList(GameStorage.KINDS).contains(kind)) {
                    return ToolResult.error("op 'expire' needs 'kind': dumps, screenshots, undo or marks.");
                }
                if (!context.has("older_than_days")) {
                    return ToolResult.error("op 'expire' needs 'older_than_days'.");
                }
                int days = context.getBoundedInt("older_than_days", 30, 1, 3650);
                return ToolResult.structured(GameStorage.expire(McmcpPaths.gameDirectory(), kind, days,
                    System.currentTimeMillis()));
            })
            .build());
    }
}
