package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.client.ClientMarks;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.io.File;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** {@code client_marks}: the spots a person (or an agent) marked in this world. See {@link ClientMarks}. */
@SideOnly(Side.CLIENT)
public final class ClientMarkTools {

    private ClientMarkTools() {
    }

    public static void register() {
        ClientMarks.register();
        McpRegistry.registerTool(McpTool.named("client_marks")
            .title("Marked spots")
            .description("The spots marked in this world for you to look at: the player types "
                + "/mark [note] while looking at a block, or drops a pin on the map in the "
                + "orchestrator app. Each mark has x, y, z, the block, the note, who made it and when. "
                + "A new mark also arrives as a log message the moment it is made.\n\n"
                + "op 'list' (default) returns them newest first. 'add' marks a spot for the player "
                + "to see, with a note; without y it marks the surface of that column. 'clear' "
                + "removes one by id, or all with all: true.")
            .schema(JsonSchema.object()
                .enumeration("op", "What to do. Defaults to 'list'.", "list", "add", "clear")
                .integer("x", "add: X of the spot.")
                .integer("y", "add: Y of the spot. Omit for the surface of the column.", 0, 255)
                .integer("z", "add: Z of the spot.")
                .string("note", "add: what the spot is, for the player.")
                .string("id", "clear: the mark to remove.")
                .bool("all", "clear: remove every mark in this world.")
                .bool("as_player", "add: record it as the player's mark, not yours. The orchestrator "
                    + "app uses this for a pin a person drops on its map.")
                .build())
            .clientOnly()
            .handler(context -> {
                String op = context.getString("op", "list");
                JsonObject result = context.onGameThread(() -> {
                    Minecraft mc = ClientStateTools.requireInWorld();
                    File file = ClientMarks.file();
                    JsonObject json = new JsonObject();
                    if ("list".equals(op)) {
                        List<JsonObject> marks = ClientMarks.current();
                        JsonArray array = new JsonArray();
                        for (JsonObject mark : marks) {
                            array.add(mark);
                        }
                        json.add("marks", array);
                        return json;
                    }
                    if ("add".equals(op)) {
                        if (!context.has("x") || !context.has("z")) {
                            throw new IllegalArgumentException("op 'add' needs x and z.");
                        }
                        int x = context.getInt("x", 0);
                        int z = context.getInt("z", 0);
                        BlockPos pos;
                        if (context.has("y")) {
                            pos = new BlockPos(x, context.getInt("y", 0), z);
                        }
                        else if (GameJson.isLoaded(mc.world, new BlockPos(x, 0, z))) {
                            // The highest block in the column, which is what a pin on a map means.
                            pos = new BlockPos(x, Math.max(0, mc.world.getHeight(x, z) - 1), z);
                        }
                        else {
                            throw new IllegalArgumentException("The column at " + x + " " + z + " is not "
                                + "loaded, so its surface is unknown; pass y.");
                        }
                        ResourceLocation name = mc.world.getBlockState(pos).getBlock().getRegistryName();
                        json.add("mark", ClientMarks.add(file, pos, mc.player.dimension,
                            context.getString("note", ""),
                            context.getBoolean("as_player", false) ? "player" : "agent",
                            name == null ? null : name.toString()));
                        return json;
                    }
                    if ("clear".equals(op)) {
                        boolean all = context.getBoolean("all", false);
                        String id = context.getString("id", null);
                        if (!all && id == null) {
                            throw new IllegalArgumentException("op 'clear' needs an id, or all: true.");
                        }
                        json.addProperty("removed", ClientMarks.clear(file, all ? null : id));
                        return json;
                    }
                    throw new IllegalArgumentException("Unknown op '" + op + "'; use list, add or clear.");
                });
                return ToolResult.structured(result);
            })
            .build());
    }
}
