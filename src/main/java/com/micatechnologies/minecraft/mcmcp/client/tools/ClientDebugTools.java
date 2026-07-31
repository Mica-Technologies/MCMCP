package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.client.ClientChatRecorder;
import com.micatechnologies.minecraft.mcmcp.client.ClientInputScheduler;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpContent;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Debugging and observation tools for the client: screenshots, GUI state, chat history and runtime
 * diagnostics.
 *
 * <h2>Screenshots return a path, not an image</h2>
 *
 * By default {@code client_screenshot} writes the PNG, returns its absolute path and attaches an MCP
 * resource link — it does not inline the image. That is a deliberate default and worth understanding
 * before overriding it: a 1080p PNG is roughly 1–2 MB, which base64-encodes to 1.4–2.7 MB of JSON
 * <em>per call</em>, and it is spent whether or not the model ends up looking at the picture. The
 * path alone is enough for a developer to open the file, and the resource link lets a client fetch
 * the bytes on demand through {@code resources/read}. Pass {@code inline: true} when the model
 * genuinely needs to see the frame in the same turn.
 *
 * <p>Screenshots run on the client thread because reading the framebuffer requires a live OpenGL
 * context, which only that thread has. That is also why they capture the last rendered frame rather
 * than forcing a fresh render: forcing one from a scheduled task would draw a frame outside the
 * normal render loop, which is a good way to corrupt GL state.
 */
@SideOnly(Side.CLIENT)
public final class ClientDebugTools {

    /** Resource template through which a client can fetch a screenshot's bytes. */
    public static final String SCREENSHOT_URI_TEMPLATE =
        McmcpConstants.RESOURCE_SCHEME + "://client/screenshot/{name}";

    private ClientDebugTools() {
    }

    public static void register() {
        ClientChatRecorder.register();
        registerScreenshot();
        registerGuiState();
        registerReadChat();
        registerRuntimeInfo();
    }

    // ------------------------------------------------------------------
    // Screenshots
    // ------------------------------------------------------------------

    private static void registerScreenshot() {
        McpRegistry.registerTool(McpTool.named("client_screenshot")
            .title("Take screenshot")
            .description("Capture the game window and save it as a PNG under the game's screenshots "
                + "directory. Returns the absolute file path and a resource link.\n\n"
                + "The image is NOT included in the response unless you pass inline=true — a full "
                + "screenshot costs one to three megabytes of base64 per call. Take the path when you "
                + "just need a record of what happened; take the image when you actually need to look "
                + "at the frame.\n\n"
                + "Captures the most recently rendered frame, so anything drawn over the game — an "
                + "open GUI, a chat window, a debug overlay — appears in it.")
            .schema(JsonSchema.object()
                .string("name", "File name for the screenshot, without a path. '.png' is appended if "
                    + "missing. Defaults to a timestamped name.")
                .bool("inline", "Include the PNG in the response as base64 image content. Expensive; "
                    + "defaults to false.")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowScreenshots()) {
                    return ToolResult.error("Screenshots are disabled by permissions.allowScreenshots "
                        + "in the MCMCP config.");
                }

                final String requestedName = context.getString("name", null);
                final boolean inline = context.getBoolean("inline", false);

                final String fileName = buildScreenshotName(requestedName);
                if (fileName == null) {
                    return ToolResult.error("The 'name' argument must be a plain file name with no "
                        + "path separators.");
                }

                // The whole capture happens on the client thread: ScreenShotHelper reads pixels back
                // out of the framebuffer, which needs the GL context that only that thread holds.
                String savedPath = context.onGameThread(new Callable<String>() {
                    @Override
                    public String call() {
                        Minecraft mc = Minecraft.getMinecraft();
                        File gameDirectory = McmcpPaths.gameDirectory();
                        ScreenShotHelper.saveScreenshot(gameDirectory, fileName,
                            mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                        return new File(new File(gameDirectory, "screenshots"), fileName)
                            .getAbsolutePath();
                    }
                });

                File file = new File(savedPath);
                JsonObject structured = new JsonObject();
                structured.addProperty("path", savedPath);
                structured.addProperty("name", fileName);
                structured.addProperty("exists", file.isFile());
                structured.addProperty("bytes", file.isFile() ? file.length() : 0L);
                structured.addProperty("resourceUri",
                    McmcpConstants.RESOURCE_SCHEME + "://client/screenshot/" + fileName);

                ToolResult result = ToolResult.text("Screenshot saved to " + savedPath)
                    .withContent(McpContent.resourceLink(
                        structured.get("resourceUri").getAsString(),
                        fileName,
                        "Screenshot captured from the Minecraft client.",
                        "image/png"))
                    .withStructured(structured);

                if (inline) {
                    if (!file.isFile()) {
                        return ToolResult.error("The screenshot was requested but no file appeared at "
                            + savedPath + ". ScreenShotHelper writes asynchronously on some drivers; "
                            + "retry, or read the resource link instead.");
                    }
                    result.withContent(McpContent.image(Files.readAllBytes(file.toPath()), "image/png"));
                }
                return result;
            })
            .build());
    }

    /**
     * Validates and normalises a screenshot file name.
     *
     * @return the name to use, or null if the caller supplied something with path syntax in it
     */
    static String buildScreenshotName(String requested) {
        if (requested == null || requested.trim().isEmpty()) {
            // Sortable, unique, and distinguishable from the player's own F2 screenshots.
            return "mcmcp-" + System.currentTimeMillis() + ".png";
        }
        String name = requested.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? name : name + ".png";
    }

    // ------------------------------------------------------------------
    // GUI state
    // ------------------------------------------------------------------

    /**
     * What is on screen.
     *
     * <p>The tool that makes menu automation possible. Without it a model driving input is blind
     * between "I pressed E" and "did an inventory open" — and, worse, cannot tell an open GUI (where
     * movement keys do nothing) from a game that has simply stopped responding.
     */
    private static void registerGuiState() {
        McpRegistry.registerTool(McpTool.named("client_gui_state")
            .title("GUI state")
            .description("Report what is currently on screen: whether a GUI is open and which one, "
                + "whether the game has input focus, and whether a world is loaded. Call this when an "
                + "input tool appears to have had no effect — an open GUI swallows movement keys.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen screen = mc.currentScreen;

                        JsonObject json = new JsonObject();
                        json.addProperty("guiOpen", screen != null);
                        json.addProperty("screenClass", screen == null ? null
                            : screen.getClass().getName());
                        // The simple name is what a model can actually pattern-match on:
                        // GuiInventory, GuiChat, GuiMainMenu, GuiContainerCreative.
                        json.addProperty("screenName", screen == null ? "none"
                            : screen.getClass().getSimpleName());
                        json.addProperty("inGameFocus", mc.inGameHasFocus);
                        json.addProperty("worldLoaded", mc.world != null);
                        json.addProperty("playerPresent", mc.player != null);
                        json.addProperty("paused", mc.isGamePaused());
                        json.addProperty("displayWidth", mc.displayWidth);
                        json.addProperty("displayHeight", mc.displayHeight);
                        json.addProperty("pendingSyntheticKeys", ClientInputScheduler.pendingCount());
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Chat history
    // ------------------------------------------------------------------

    private static void registerReadChat() {
        McpRegistry.registerTool(McpTool.named("client_read_chat")
            .title("Read chat")
            .description("Read recent chat the client has received, oldest first: command responses, "
                + "other players' messages, death messages, and anything mods print to chat.\n\n"
                + "This is how you see the result of a command sent with client_send_chat. The server "
                + "replies asynchronously, so allow a moment — or a tick or two of any other tool — "
                + "between sending and reading.")
            .schema(JsonSchema.object()
                .integer("lines", "How many recent lines to return.", 1, 300)
                .string("filter", "Case-insensitive substring; only matching lines are returned.")
                .build())
            .clientOnly()
            .readOnly()
            .offGameThread()
            .handler(context -> {
                final int lineCount = context.getBoundedInt("lines", 50, 1, 300);
                final String filter = context.getString("filter", null);
                final String needle = filter == null || filter.isEmpty()
                    ? null
                    : filter.toLowerCase(java.util.Locale.ROOT);

                List<ClientChatRecorder.Entry> entries =
                    ClientChatRecorder.recent(needle == null ? lineCount : 300);

                JsonArray lines = new JsonArray();
                StringBuilder text = new StringBuilder();
                for (ClientChatRecorder.Entry entry : entries) {
                    if (needle != null
                        && !entry.getText().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                        continue;
                    }
                    JsonObject line = new JsonObject();
                    line.addProperty("text", entry.getText());
                    line.addProperty("type", entry.getType());
                    line.addProperty("timestamp", entry.getTimestampMillis());
                    lines.add(line);
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(entry.getText());
                }

                JsonObject structured = new JsonObject();
                structured.addProperty("returned", lines.size());
                structured.addProperty("buffered", ClientChatRecorder.size());
                structured.add("lines", lines);

                return ToolResult.text(text.length() == 0 ? "(no chat messages match)" : text.toString())
                    .withStructured(structured);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Runtime diagnostics
    // ------------------------------------------------------------------

    /**
     * The information the F3 overlay shows, in a form a model can read.
     *
     * <p>Frame rate and heap usage are how a model notices it is asking too much of the client —
     * a screenshot every tick or a scan every second will show up here as a frame rate collapse
     * before it shows up as an error.
     */
    private static void registerRuntimeInfo() {
        McpRegistry.registerTool(McpTool.named("client_runtime_info")
            .title("Client runtime info")
            .description("Report client performance and environment: frame rate, heap usage, render "
                + "distance, graphics settings and the game directory. Roughly what the F3 debug "
                + "overlay shows. Check frame rate here if the client seems to be struggling.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = Minecraft.getMinecraft();
                        Runtime runtime = Runtime.getRuntime();
                        long maxMemory = runtime.maxMemory();
                        long totalMemory = runtime.totalMemory();
                        long freeMemory = runtime.freeMemory();

                        JsonObject json = new JsonObject();
                        json.addProperty("fps", Minecraft.getDebugFPS());
                        json.addProperty("renderDistanceChunks", mc.gameSettings.renderDistanceChunks);
                        json.addProperty("fancyGraphics", mc.gameSettings.fancyGraphics);
                        json.addProperty("guiScale", mc.gameSettings.guiScale);
                        json.addProperty("displayWidth", mc.displayWidth);
                        json.addProperty("displayHeight", mc.displayHeight);
                        json.addProperty("gameDirectory", McmcpPaths.gameDirectory().getAbsolutePath());
                        json.addProperty("screenshotDirectory",
                            McmcpPaths.screenshotsDirectory().getAbsolutePath());

                        JsonObject memory = new JsonObject();
                        memory.addProperty("maxMegabytes", maxMemory / 1_048_576L);
                        memory.addProperty("allocatedMegabytes", totalMemory / 1_048_576L);
                        memory.addProperty("usedMegabytes", (totalMemory - freeMemory) / 1_048_576L);
                        memory.addProperty("usedPercentOfMax",
                            Math.round((totalMemory - freeMemory) * 1000.0D / maxMemory) / 10.0D);
                        json.add("memory", memory);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }
}
