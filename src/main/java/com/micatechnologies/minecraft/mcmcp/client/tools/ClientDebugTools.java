package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.client.ClientChatRecorder;
import com.micatechnologies.minecraft.mcmcp.client.ClientInputScheduler;
import com.micatechnologies.minecraft.mcmcp.client.ScreenSampler;
import com.micatechnologies.minecraft.mcmcp.client.WindowFocus;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.game.McmcpProcess;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpContent;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.ScreenshotImages;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import javax.imageio.ImageIO;
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
 * before overriding it: an inline frame costs a model something like a thousand tokens, and it is
 * spent whether or not it ends up looking at the picture. The path alone is enough for a developer
 * to open the file, and the resource link lets a client fetch the bytes on demand through
 * {@code resources/read}. Pass {@code inline: true} when the model genuinely needs to see the frame
 * in the same turn.
 *
 * <p>When it does, {@code max_dimension} decides what that costs. Without it the price of a
 * screenshot was set by how large the player happened to have dragged the game window — the same
 * question costing three times as much on a 1080p client as on a 720p one, for no more answer. See
 * {@link ScreenshotImages} for the arithmetic. The file on disk is never scaled; only the copy in the
 * response is.
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
        registerScreenStats();
        registerGuiState();
        registerReadChat();
        registerRuntimeInfo();
        registerReloadResources();
    }

    // ------------------------------------------------------------------
    // Screenshots
    // ------------------------------------------------------------------

    private static void registerScreenshot() {
        McpRegistry.registerTool(McpTool.named("client_screenshot")
            .title("Take screenshot")
            .description("Capture the game window and save it as a PNG under the game's screenshots "
                + "directory. Returns the absolute file path and a resource link.\n\n"
                + "The image is NOT included in the response unless you pass inline=true. Take the "
                + "path when you just need a record of what happened; take the image when you "
                + "actually need to look at the frame.\n\n"
                + "An inline image costs you roughly width x height / 750 tokens, so its price is set "
                + "by the size of the game window unless you say otherwise. Use max_dimension to say "
                + "otherwise: it caps the long edge of the inline copy only, and the file on disk is "
                + "always saved at full resolution.\n\n"
                + "Captures the most recently rendered frame, so anything drawn over the game — an "
                + "open GUI, a chat window, a debug overlay — appears in it.")
            .schema(JsonSchema.object()
                .string("name", "File name for the screenshot, without a path. '.png' is appended if "
                    + "missing. Defaults to a timestamped name.")
                .bool("inline", "Include the PNG in the response as base64 image content. Costs "
                    + "tokens in proportion to its area; defaults to false.")
                .integer("max_dimension", "Longest edge of the INLINE copy, in pixels; the saved file "
                        + "keeps its full resolution. Useful values: 640 (~550 tokens) to check which "
                        + "screen is open or roughly where the player is looking, 1280 (~1,230, the "
                        + "default) to read GUI labels and the F3 overlay, 1568 (~1,850) for fine "
                        + "detail. Above 1568 nothing is gained — the image is downscaled to that "
                        + "before it reaches you either way. Ignored unless inline is true.",
                    ScreenshotImages.MIN_MAX_DIMENSION, ScreenshotImages.PROVIDER_CEILING)
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowScreenshots()) {
                    return ToolResult.error("Screenshots are disabled by permissions.allowScreenshots "
                        + "in the MCMCP config.");
                }

                final String requestedName = context.getString("name", null);
                final boolean inline = context.getBoolean("inline", false);
                final int maxDimension = context.getBoundedInt("max_dimension",
                    ScreenshotImages.DEFAULT_MAX_DIMENSION,
                    ScreenshotImages.MIN_MAX_DIMENSION, ScreenshotImages.PROVIDER_CEILING);

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

                    // Read the frame back off disk rather than capturing it a second time. Three
                    // things fall out of that and all of them matter: the framebuffer is not read
                    // twice, no image object crosses back off the game thread, and the inline copy
                    // is provably the same picture as the file — including any change a mod's
                    // ScreenshotEvent handler made to it on the way out.
                    BufferedImage full = ImageIO.read(file);
                    if (full == null) {
                        return ToolResult.error("The screenshot at " + savedPath + " could not be "
                            + "decoded as an image. Read the resource link instead.");
                    }
                    BufferedImage sent = ScreenshotImages.fitWithin(full, maxDimension);
                    byte[] png = sent == full
                        ? Files.readAllBytes(file.toPath())
                        : ScreenshotImages.toPng(sent);

                    structured.addProperty("capturedWidth", full.getWidth());
                    structured.addProperty("capturedHeight", full.getHeight());
                    structured.addProperty("inlineWidth", sent.getWidth());
                    structured.addProperty("inlineHeight", sent.getHeight());
                    structured.addProperty("inlineBytes", png.length);
                    structured.addProperty("approximateImageTokens",
                        ScreenshotImages.approximateTokens(sent.getWidth(), sent.getHeight()));

                    result.withContent(McpContent.image(png, "image/png"));
                }
                return result;
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Screen statistics
    // ------------------------------------------------------------------

    /** Longest a screen sample may run: well inside the orchestrator's two-minute call timeout. */
    private static final long SCREEN_STATS_MAX_MILLIS = 60_000L;

    private static void registerScreenStats() {
        ScreenSampler.register();
        McpRegistry.registerTool(McpTool.named("client_screen_stats")
            .title("Sample screen brightness")
            .description("Read the rendered frame many times in a row — every frame, or on an "
                + "interval — and return each read's mean brightness (luminance, 0-255) and, "
                + "optionally, mean red, green and blue, without saving or sending any image. Use it "
                + "to catch a short visual effect a screenshot would miss: a strobe, a flicker, a "
                + "light that should blink. Crop to the part of the screen the effect is in, or it "
                + "is diluted by everything else in view.\n\n"
                + "'changed' counts reads that differ from the first by more than threshold. The "
                + "crop is in window pixels from the top-left, as in a screenshot. Blocks for "
                + "count x interval, at most 60 seconds.")
            .schema(JsonSchema.object()
                .integer("count", "How many reads. Default 20.", 1, 600)
                .integer("interval_ms", "Time between reads. 0 (default) reads every rendered frame.",
                    0, 5000)
                .integer("x", "Crop: left edge in window pixels. Default 0.", 0, 16384)
                .integer("y", "Crop: top edge in window pixels. Default 0.", 0, 16384)
                .integer("width", "Crop width in pixels. Default: to the right edge.", 1, 16384)
                .integer("height", "Crop height in pixels. Default: to the bottom edge.", 1, 16384)
                .number("threshold", "Luminance difference from the first read that counts as "
                    + "changed. Default 8.", 0.0D, 255.0D)
                .bool("per_channel", "Also return mean red, green and blue per read. Default false.")
                .build())
            .clientOnly()
            .readOnly()
            .closedWorld()
            .offGameThread()
            .handler(context -> {
                if (!McmcpConfig.isAllowScreenshots()) {
                    return ToolResult.error("Reading the screen is disabled by "
                        + "permissions.allowScreenshots in the MCMCP config.");
                }
                int count = context.getBoundedInt("count", 20, 1, 600);
                int intervalMillis = context.getBoundedInt("interval_ms", 0, 0, 5000);
                if ((long) count * intervalMillis > SCREEN_STATS_MAX_MILLIS) {
                    return ToolResult.error("count x interval_ms is " + (long) count * intervalMillis
                        + " ms, over the " + SCREEN_STATS_MAX_MILLIS + " ms one call may take. Lower "
                        + "one of them.");
                }
                double threshold = Math.max(0.0D, Math.min(255.0D, context.getDouble("threshold", 8.0D)));
                boolean perChannel = context.getBoolean("per_channel", false);

                java.util.concurrent.CompletableFuture<ScreenSampler.Result> future = ScreenSampler.start(
                    context.getBoundedInt("x", 0, 0, 16384), context.getBoundedInt("y", 0, 0, 16384),
                    context.getBoundedInt("width", 0, 0, 16384), context.getBoundedInt("height", 0, 0, 16384),
                    count, intervalMillis * 1_000_000L);
                if (future == null) {
                    return ToolResult.error("Another client_screen_stats call is already sampling; wait "
                        + "for it.");
                }

                ScreenSampler.Result sampled;
                long deadline = System.currentTimeMillis() + SCREEN_STATS_MAX_MILLIS + 5_000L;
                try {
                    while (true) {
                        context.getCancellation().throwIfCancelled();
                        try {
                            sampled = future.get(100L, java.util.concurrent.TimeUnit.MILLISECONDS);
                            break;
                        } catch (java.util.concurrent.TimeoutException notYet) {
                            if (System.currentTimeMillis() > deadline) {
                                return ToolResult.error("The screen was not sampled " + count + " times in "
                                    + "time. The game may not be rendering: minimised, paused or loading.");
                            }
                        }
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    return ToolResult.error("Reading the screen failed: " + e.getCause());
                } finally {
                    ScreenSampler.cancel();
                }

                List<ScreenSampler.Sample> samples = sampled.samples;
                long firstNanos = samples.get(0).nanos;
                double first = samples.get(0).luminance;
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                double sum = 0.0D;
                double maxDelta = 0.0D;
                int changed = 0;
                com.google.gson.JsonArray reads = new com.google.gson.JsonArray();
                for (ScreenSampler.Sample sample : samples) {
                    min = Math.min(min, sample.luminance);
                    max = Math.max(max, sample.luminance);
                    sum += sample.luminance;
                    double delta = Math.abs(sample.luminance - first);
                    maxDelta = Math.max(maxDelta, delta);
                    if (delta > threshold) {
                        changed++;
                    }
                    JsonObject read = new JsonObject();
                    read.addProperty("ms", Math.round((sample.nanos - firstNanos) / 1.0e6D));
                    read.addProperty("lum", round1(sample.luminance));
                    if (perChannel) {
                        read.addProperty("r", round1(sample.red));
                        read.addProperty("g", round1(sample.green));
                        read.addProperty("b", round1(sample.blue));
                    }
                    reads.add(read);
                }

                JsonObject region = new JsonObject();
                region.addProperty("x", sampled.x);
                region.addProperty("y", sampled.y);
                region.addProperty("width", sampled.width);
                region.addProperty("height", sampled.height);

                JsonObject json = new JsonObject();
                json.add("region", region);
                json.addProperty("reads", samples.size());
                json.addProperty("spanMs", Math.round((samples.get(samples.size() - 1).nanos - firstNanos) / 1.0e6D));
                json.addProperty("minLum", round1(min));
                json.addProperty("maxLum", round1(max));
                json.addProperty("meanLum", round1(sum / samples.size()));
                json.addProperty("maxDeltaFromFirst", round1(maxDelta));
                json.addProperty("changed", changed);
                json.add("samples", reads);
                return ToolResult.structured(json);
            })
            .build());
    }

    private static double round1(double value) {
        return Math.round(value * 10.0D) / 10.0D;
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
                        // Whether a person is using this window, which inGameFocus does not say.
                        json.addProperty("windowFocused", WindowFocus.isFocused());
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
                + "between sending and reading.\n\n"
                + "Each line reads '[HH:MM:SS] message'. Anything that is not ordinary player chat "
                + "is tagged after the time: '(SYSTEM)' for command output and server messages, "
                + "'(GAME_INFO)' for the action bar above the hotbar.")
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

                // One rendering of each line, not two. This used to send every message as text and
                // again as a JSON object carrying the same string plus its type and timestamp,
                // which doubled the cost of a chat read to carry two fields. Both now live in the
                // text, where they cost about eleven characters instead of a fifty-byte object
                // wrapper around a string that was already there.
                int returned = 0;
                StringBuilder text = new StringBuilder();
                for (ClientChatRecorder.Entry entry : entries) {
                    if (needle != null
                        && !entry.getText().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                        continue;
                    }
                    returned++;
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(String.format(java.util.Locale.ROOT, "[%tT] ",
                        entry.getTimestampMillis()));
                    // Only when it is not ordinary chat. CHAT is the overwhelming majority, so
                    // naming it on every line would spend more than the field is worth; SYSTEM
                    // (command output, the reason this tool is usually called) and GAME_INFO (the
                    // action bar, which is not chat at all) are the ones worth distinguishing.
                    if (!"CHAT".equals(entry.getType())) {
                        text.append('(').append(entry.getType()).append(") ");
                    }
                    text.append(entry.getText());
                }

                // The counts go in a header line, not in structured content. They used to be
                // {returned, buffered} alongside the text, and a client that reads
                // structuredContent as the whole answer — which it is entitled to — showed
                // "returned: 6" and none of the six. See ToolResult.withStructured.
                String header = "# " + returned + " of " + ClientChatRecorder.size() + " buffered "
                    + "line(s)\n";
                return ToolResult.text(header
                    + (text.length() == 0 ? "(no chat messages match)" : text.toString()));
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
            .description("Report client performance and environment: frame rate, heap and off-heap "
                + "(direct buffer) memory against their limits, render "
                + "distance, graphics settings and the game directory. Roughly what the F3 debug "
                + "overlay shows. Check frame rate here if the client seems to be struggling.\n\n"
                + "Also reports this game's process id and start time. Two clients launched from one "
                + "directory are identical in every other field MCMCP reports, and only the one that "
                + "won the port answers — so if a freshly built change appears to have had no "
                + "effect, check 'process.startedAt' against when you built it before doubting the "
                + "change.")
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

                        // The pid and start time of this game, so a caller holding two clients from
                        // one directory can tell which one it is actually talking to. See
                        // McmcpProcess for the failure this exists to make visible.
                        json.add("process", McmcpProcess.toJson());

                        JsonObject memory = new JsonObject();
                        memory.addProperty("maxMegabytes", maxMemory / 1_048_576L);
                        memory.addProperty("allocatedMegabytes", totalMemory / 1_048_576L);
                        memory.addProperty("usedMegabytes", (totalMemory - freeMemory) / 1_048_576L);
                        memory.addProperty("usedPercentOfMax",
                            Math.round((totalMemory - freeMemory) * 1000.0D / maxMemory) / 10.0D);
                        // Off-heap: what ran out when a dense chunk rebuild killed a client with the
                        // heap half empty. See DirectMemory.
                        memory.add("offHeap", com.micatechnologies.minecraft.mcmcp.perf.DirectMemory.toJson());
                        json.add("memory", memory);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Resource reload
    // ------------------------------------------------------------------

    private static void registerReloadResources() {
        McpRegistry.registerTool(McpTool.named("client_reload_resources")
            .title("Reload client resources")
            .description("Reload every client resource from disk — textures, models, sounds, "
                + "language files and shaders — exactly as pressing F3+T does. Use this to pick "
                + "up an edit to a resource without restarting the game, which is the only way to "
                + "re-run a mod's resource-reload listeners in a session.\n\n"
                + "The reload is started and not waited for. A full reload takes seconds and grows "
                + "with the pack, so blocking would report a timeout for a reload that is proceeding "
                + "perfectly well. The game thread is busy for the duration, so the next tool call "
                + "queues behind it and returns once the reload has finished — which is also how "
                + "to wait for it.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .handler(context -> {
                // Fire-and-forget for the same reason client_world_create is: refreshResources
                // re-reads every pack on the client thread and blocks it for far longer than a
                // scheduled task is allowed to take.
                context.onGameThreadAsync(new Runnable() {
                    @Override
                    public void run() {
                        Minecraft.getMinecraft().refreshResources();
                    }
                });

                JsonObject result = new JsonObject();
                result.addProperty("reloadStarted", true);
                return ToolResult.text("Reloading client resources. This takes a few seconds; the "
                    + "next tool call will wait for it to finish.")
                    .withStructured(result);
            })
            .build());
    }
}
