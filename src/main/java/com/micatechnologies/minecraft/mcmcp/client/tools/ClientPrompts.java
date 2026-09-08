package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.mcp.McpPrompt;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.prompts.CommonPrompts;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.util.List;
import java.util.concurrent.Callable;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Prompts that only make sense with a camera and a player to drive.
 *
 * <p>Both of these expand with live state already gathered, which is the point: a user picking
 * "look around and tell me where I am" should not have to watch the model spend three tool calls
 * working out that it is in a world at all.
 */
@SideOnly(Side.CLIENT)
public final class ClientPrompts {

    private ClientPrompts() {
    }

    public static void register() {
        registerSurvey();
        registerVerifyChange();
    }

    private static void registerSurvey() {
        McpRegistry.registerPrompt(McpPrompt.named("survey_surroundings")
            .title("Survey surroundings")
            .description("Look around from the player's current position and describe where they are "
                + "and what is nearby.")
            .clientOnly()
            .generator(context -> {
                JsonObject state = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = Minecraft.getMinecraft();
                        JsonObject json = new JsonObject();
                        if (mc.player == null || mc.world == null) {
                            json.addProperty("inWorld", false);
                            return json;
                        }
                        json.addProperty("inWorld", true);
                        json.add("player", GameJson.player(mc.player));
                        json.add("world", GameJson.world(mc.world));
                        String biome = GameJson.biomeName(mc.world, mc.player.getPosition());
                        if (biome != null) {
                            json.addProperty("biome", biome);
                        }
                        json.add("lookingAt", GameJson.rayTrace(mc.world, mc.objectMouseOver));
                        return json;
                    }
                });

                StringBuilder text = new StringBuilder();
                if (!state.get("inWorld").getAsBoolean()) {
                    text.append("The Minecraft client is not in a world — it is at the main menu or a "
                        + "loading screen. Use client_gui_state to see what is on screen, and "
                        + "client_screenshot if a picture would help.\n");
                    return CommonPrompts.one(McpPrompt.userMessage(text.toString()));
                }

                text.append("Survey the player's surroundings in Minecraft and describe where they "
                    + "are, what is around them, and anything notable or dangerous.\n\n");
                text.append("Current state:\n\n```json\n")
                    .append(com.micatechnologies.minecraft.mcmcp.json.Json.write(state))
                    .append("\n```\n\n");
                text.append("Build on that with the tools rather than restating it: "
                    + "client_nearby_entities for what is moving, client_get_block for specific "
                    + "positions, client_look plus client_looking_at to check a direction, and "
                    + "client_screenshot if a picture would settle a question faster than a query.\n");
                text.append("Note that the client only knows about loaded chunks within its render "
                    + "distance. Positions outside it come back as loaded=false, which means "
                    + "'unknown', not 'empty'.\n");
                return CommonPrompts.one(McpPrompt.userMessage(text.toString()));
            })
            .build());
    }

    /**
     * The screenshot-driven verification loop.
     *
     * <p>This is the workflow the debug tools were built for: do something, capture the screen, look
     * at the picture, decide whether it worked. It exists as a prompt because the sequencing — and
     * particularly "the world needs a moment to react before you capture" — is the part that is easy
     * to get wrong and invisible from the tool descriptions alone.
     */
    private static void registerVerifyChange() {
        McpRegistry.registerPrompt(McpPrompt.named("verify_visually")
            .title("Verify a change visually")
            .description("Perform an action and confirm the result by looking at the screen.")
            .argument("action", "What to do, e.g. 'open the inventory' or 'place a torch on the wall "
                + "in front of me'.", true)
            .clientOnly()
            .generator(context -> {
                String action = context.getString("action", "the requested action");

                StringBuilder text = new StringBuilder();
                text.append("In the running Minecraft client: ").append(action)
                    .append(", then confirm visually that it worked.\n\n");
                text.append("Loop:\n\n");
                text.append("1. Capture the starting state — client_screenshot with inline=true so you "
                    + "can actually see it, plus client_gui_state.\n");
                text.append("2. Act, using client_look, client_move, client_key, client_interact or "
                    + "client_send_chat.\n");
                text.append("3. Let the world react. Input tools return once the input has been "
                    + "applied, not once the result has settled — a block break finishes, a GUI "
                    + "animates, a server round-trip completes. A short client_key with a small tick "
                    + "count is a convenient way to let a moment pass.\n");
                text.append("4. Capture again and compare. client_gui_state and client_read_chat often "
                    + "answer the question more cheaply than a second screenshot.\n");
                text.append("5. If it did not work, say what you observed rather than retrying blindly. "
                    + "A no-op is usually an open GUI swallowing input, the crosshair not on the "
                    + "target, or the server rejecting the action — client_looking_at and "
                    + "client_read_chat distinguish those three.\n\n");
                text.append("Screenshots are large. Use inline=true when you need to look at the frame; "
                    + "otherwise take the path and read the resource link only if a question comes up "
                    + "that the state tools cannot answer.\n");
                return CommonPrompts.one(McpPrompt.userMessage(text.toString()));
            })
            .build());
    }

    /** Kept so both generators return through one path. */
    static List<JsonObject> one(JsonObject message) {
        return CommonPrompts.one(message);
    }
}
