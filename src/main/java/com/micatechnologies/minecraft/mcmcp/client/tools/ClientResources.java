package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.client.ClientChatRecorder;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.McpContent;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpResource;
import com.micatechnologies.minecraft.mcmcp.mcp.UriTemplates;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Client-side MCP resources: live player state, the chat buffer, and screenshot bytes.
 *
 * <p>The screenshot template is what makes {@code client_screenshot}'s cheap default work. The tool
 * returns a path and a {@code resource_link}; a client that decides it wants to look at the picture
 * follows that link through {@code resources/read} and gets the PNG. Nothing pays for the image
 * until something actually needs it.
 */
@SideOnly(Side.CLIENT)
public final class ClientResources {

    /**
     * Largest screenshot served inline through the resource, in bytes.
     *
     * <p>Not a guess at PNG sizes so much as a ceiling on what one {@code resources/read} can make
     * the game allocate and base64-encode on a worker thread. A 4K screenshot lands around 4–6 MB;
     * beyond 16 MB something has gone wrong and the caller is better off opening the file path.
     */
    private static final int MAX_SCREENSHOT_BYTES = 16 * 1024 * 1024;

    private ClientResources() {
    }

    public static void register() {
        registerPlayerState();
        registerChat();
        registerScreenshots();
    }

    private static void registerPlayerState() {
        McpRegistry.registerResource(McpResource
            .at(McmcpConstants.RESOURCE_SCHEME + "://client/player/state")
            .name("player-state")
            .title("Player state")
            .description("The controlled player's live position, orientation, health, hunger and "
                + "surroundings, as the client sees them.")
            .mimeType("application/json")
            .clientOnly()
            .reader((context, uri) -> {
                JsonObject payload = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc.player == null || mc.world == null) {
                            JsonObject empty = new JsonObject();
                            empty.addProperty("inWorld", false);
                            empty.addProperty("note", "The client is at the main menu or a loading "
                                + "screen; there is no player to describe.");
                            return empty;
                        }
                        JsonObject json = GameJson.player(mc.player);
                        json.addProperty("inWorld", true);
                        json.add("world", GameJson.world(mc.world));
                        json.add("lookingAt", GameJson.rayTrace(mc.world, mc.objectMouseOver));
                        return json;
                    }
                });
                return Collections.singletonList(
                    McpContent.textResource(uri, "application/json", Json.writePretty(payload)));
            })
            .build());
    }

    /**
     * Recent chat, subscribable.
     *
     * <p>One of the few genuinely subscribable resources here, and the reason the subscribe machinery
     * exists: {@link ClientChatRecorder} fires a resource-updated notification on every incoming
     * message, so a client can watch chat in real time instead of polling. Command output, another
     * player talking, a mod's error — all arrive without a request.
     */
    private static void registerChat() {
        McpRegistry.registerResource(McpResource
            .at(ClientChatRecorder.RESOURCE_URI)
            .name("recent-chat")
            .title("Recent chat")
            .description("The last few hundred chat lines the client received, oldest first. Subscribe "
                + "to be notified as new messages arrive rather than polling.")
            .mimeType("application/json")
            .clientOnly()
            .subscribable()
            .reader((context, uri) -> {
                JsonArray lines = new JsonArray();
                for (ClientChatRecorder.Entry entry : ClientChatRecorder.recent(200)) {
                    JsonObject line = new JsonObject();
                    line.addProperty("text", entry.getText());
                    line.addProperty("type", entry.getType());
                    line.addProperty("timestamp", entry.getTimestampMillis());
                    lines.add(line);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("count", lines.size());
                payload.add("lines", lines);
                return Collections.singletonList(
                    McpContent.textResource(uri, "application/json", Json.writePretty(payload)));
            })
            .build());
    }

    private static void registerScreenshots() {
        McpRegistry.registerResource(McpResource
            .template(ClientDebugTools.SCREENSHOT_URI_TEMPLATE)
            .name("screenshot")
            .title("Screenshot")
            .description("The PNG bytes of a screenshot in the game's screenshots directory, by file "
                + "name. Take one with the client_screenshot tool, then read this to see it.")
            .mimeType("image/png")
            .clientOnly()
            .reader((context, uri) -> {
                if (!McmcpConfig.isAllowScreenshots()) {
                    throw new IllegalStateException("Screenshots are disabled by "
                        + "permissions.allowScreenshots in the MCMCP config.");
                }

                Map<String, String> variables =
                    UriTemplates.extract(ClientDebugTools.SCREENSHOT_URI_TEMPLATE, uri);
                String name = variables == null ? null : variables.get("name");
                if (name == null || name.isEmpty()) {
                    throw new IllegalArgumentException("Screenshot URIs look like "
                        + ClientDebugTools.SCREENSHOT_URI_TEMPLATE);
                }
                // The template cannot match a '/', so path traversal via separators is already
                // impossible — but ".." contains none, and would still escape the directory.
                if (name.contains("..")) {
                    throw new IllegalArgumentException("Screenshot names may not contain '..'.");
                }

                File file = new File(McmcpPaths.screenshotsDirectory(), name);
                if (!file.isFile()) {
                    throw new IllegalStateException("No screenshot named '" + name + "' exists in "
                        + McmcpPaths.screenshotsDirectory().getAbsolutePath());
                }
                if (file.length() > MAX_SCREENSHOT_BYTES) {
                    throw new IllegalStateException("Screenshot '" + name + "' is "
                        + (file.length() / 1_048_576L) + " MB, over the "
                        + (MAX_SCREENSHOT_BYTES / 1_048_576L) + " MB limit for inline delivery. Open "
                        + "it from disk at " + file.getAbsolutePath() + " instead.");
                }
                return Collections.singletonList(
                    McpContent.binaryResource(uri, "image/png", Files.readAllBytes(file.toPath())));
            })
            .build());
    }
}
