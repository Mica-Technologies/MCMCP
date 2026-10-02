package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.client.ClientChatRecorder;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.util.Locale;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Waiting: for time to pass, or for the game to reach a state.
 *
 * <h2>Why a tool exists purely to do nothing</h2>
 *
 * Almost everything interesting in Minecraft happens over ticks rather than instantly. A world takes
 * seconds to load, a command's reply arrives asynchronously in chat, a redstone-triggered machine
 * moves over the following second, a screen appears a frame after the click that opened it. A model
 * driving the game has to be able to let time pass between acting and observing.
 *
 * <p>Without this, the only way to wait was to abuse an input tool for its duration — holding sneak
 * for sixty ticks purely because {@code client_key} blocks until the hold finishes. That works, and
 * it is awful: it presses a key nobody asked to press, it cannot express "wait until the world has
 * loaded", and it silently couples "how long do I wait" to "what input am I willing to send".
 *
 * <h2>Conditions beat fixed delays</h2>
 *
 * A fixed sleep is a guess, and a guess that is too short fails intermittently on a slow machine
 * while a guess that is too long wastes every run. Prefer {@code waitFor}: it returns the moment the
 * condition holds, and reports honestly when it timed out instead of pretending the wait succeeded.
 */
@SideOnly(Side.CLIENT)
public final class ClientSyncTools {

    /**
     * How often a condition is re-checked. Each poll hops to the client thread, so this trades
     * responsiveness against the cost of interrupting the game loop; 100ms is two ticks, which is
     * far finer than any condition here changes at.
     */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** Hard ceiling on any wait, so a condition that never becomes true cannot hold a worker forever. */
    private static final int MAX_TIMEOUT_TICKS = 6000;

    /** Widest chunk wait: 32 chunks either way is past any render distance a client runs. */
    private static final int MAX_CHUNK_RADIUS = 512;

    /** Missing chunks named in a result; the count beyond this stays exact. */
    private static final int MAX_LISTED_CHUNKS = 32;

    /**
     * How often a long wait reports progress.
     *
     * <p>This is what lets a wait outlast the orchestrator's per-call limit: the orchestrator times a
     * call out after a stretch with neither an answer nor progress, not after a fixed time, so a
     * wait that reports regularly can run to its full {@link #MAX_TIMEOUT_TICKS}. Well inside that
     * stretch, and rare enough to cost nothing.
     */
    private static final long PROGRESS_INTERVAL_MILLIS = 5_000L;

    private ClientSyncTools() {
    }

    /** Reports progress if {@link #PROGRESS_INTERVAL_MILLIS} has passed; returns when it last did. */
    private static long heartbeat(ToolContext context, long start, long lastProgress, long budgetMillis) {
        long now = System.currentTimeMillis();
        if (now - lastProgress < PROGRESS_INTERVAL_MILLIS) {
            return lastProgress;
        }
        context.reportProgress(now - start, budgetMillis, null);
        return now;
    }

    public static void register() {
        registerWait();
    }

    private static void registerWait() {
        McpRegistry.registerTool(McpTool.named("client_wait")
            .title("Wait")
            .description("Let game time pass, either for a fixed number of ticks or until the game "
                + "reaches a given state.\n\n"
                + "Use this between acting and observing: a command's reply arrives in chat "
                + "asynchronously, a world takes seconds to load, and a machine you just powered "
                + "moves over the following ticks. Reading state immediately after acting usually "
                + "reads the state from before.\n\n"
                + "Prefer 'waitFor' over a fixed tick count wherever one fits — it returns as soon as "
                + "the condition holds rather than always burning the full duration, and it tells you "
                + "when it gave up instead of leaving you to infer it.\n\n"
                + "'chunksLoaded' waits until this client holds every chunk within 'radius' of (x, z) "
                + "— the player by default — and 'chunksRendered' also until their meshes are built, "
                + "which is what a screenshot needs. Use one after a teleport, before surveying. On a "
                + "timeout the result lists the chunks still missing.")
            .schema(JsonSchema.object()
                .integer("ticks", "How long to wait, in ticks (20 per second). With 'waitFor' this is "
                    + "the timeout; without it, the exact time to wait. Defaults to 20.",
                    1, MAX_TIMEOUT_TICKS)
                .enumeration("waitFor", "A condition to wait for instead of a fixed delay.",
                    "worldLoaded", "worldUnloaded", "screenOpen", "screenClosed", "chat",
                    "chunksLoaded", "chunksRendered")
                .integer("x", "For the chunk conditions: block X of the area's centre. Defaults to "
                    + "the player's.")
                .integer("z", "For the chunk conditions: block Z of the area's centre. Defaults to "
                    + "the player's.")
                .integer("radius", "For the chunk conditions: blocks around the centre that must be "
                    + "loaded. Default 64. Chunks past the render distance never load.", 0,
                    MAX_CHUNK_RADIUS)
                .string("screenName", "For waitFor 'screenOpen': the screen's simple class name, "
                    + "matched case-insensitively as a substring — 'main' matches GuiMainMenu. Omit to "
                    + "wait for any screen at all.")
                .string("chatContains", "For waitFor 'chat': wait until a chat line containing this "
                    + "text arrives. Only lines received after the wait starts count.")
                .build())
            .clientOnly()
            .readOnly()
            .offGameThread()
            .handler(context -> {
                final int ticks = context.getBoundedInt("ticks", 20, 1, MAX_TIMEOUT_TICKS);
                final String waitFor = context.getString("waitFor", null);
                final String screenName = context.getString("screenName", null);
                final String chatContains = context.getString("chatContains", null);
                final long budgetMillis = ticks * 50L;
                final ChunkArea area = waitFor != null && waitFor.startsWith("chunks")
                    ? new ChunkArea(context.has("x") ? context.getInt("x", 0) : null,
                        context.has("z") ? context.getInt("z", 0) : null,
                        context.getBoundedInt("radius", 64, 0, MAX_CHUNK_RADIUS))
                    : null;

                if (waitFor == null) {
                    // A plain delay. Sleeping on the worker rather than blocking the client thread:
                    // the point is to let the game run, and a scheduled task that sleeps would stop
                    // the very ticks being waited for.
                    long start = System.currentTimeMillis();
                    long remaining = budgetMillis;
                    long lastProgress = start;
                    while (remaining > 0) {
                        if (context.getCancellation().isCancelled()) {
                            return ToolResult.text("Wait cancelled after "
                                + (System.currentTimeMillis() - start) + "ms.");
                        }
                        Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, remaining));
                        lastProgress = heartbeat(context, start, lastProgress, budgetMillis);
                        remaining = budgetMillis - (System.currentTimeMillis() - start);
                    }
                    JsonObject json = new JsonObject();
                    json.addProperty("waitedTicks", ticks);
                    json.addProperty("waitedMillis", System.currentTimeMillis() - start);
                    json.addProperty("conditionMet", true);
                    return ToolResult.text("Waited " + ticks + " tick(s).").withStructured(json);
                }

                if ("chat".equals(waitFor) && (chatContains == null || chatContains.isEmpty())) {
                    return ToolResult.error("waitFor 'chat' needs 'chatContains' to say what to "
                        + "wait for.");
                }

                // Only chat that arrives from here on counts. Matching against the backlog would
                // return instantly on a line from minutes ago, which is the opposite of waiting. A
                // capture rather than a count of the buffer: the buffer stops growing once full, and
                // a wait keyed on its size then never saw another line.
                try (ClientChatRecorder.Capture chat = "chat".equals(waitFor)
                    ? ClientChatRecorder.capture() : null) {
                    return waitForCondition(context, waitFor, ticks, budgetMillis, screenName, area,
                        chatContains, chat);
                }
            })
            .build());
    }

    private static ToolResult waitForCondition(ToolContext context,
        String waitFor, int ticks, long budgetMillis, String screenName, ChunkArea area,
        String chatContains,
        ClientChatRecorder.Capture chat) throws Exception {
        long start = System.currentTimeMillis();
        long lastProgress = start;
        while (System.currentTimeMillis() - start < budgetMillis) {
            lastProgress = heartbeat(context, start, lastProgress, budgetMillis);
            if (context.getCancellation().isCancelled()) {
                JsonObject json = new JsonObject();
                json.addProperty("conditionMet", false);
                json.addProperty("cancelled", true);
                json.addProperty("waitedMillis", System.currentTimeMillis() - start);
                return ToolResult.text("Wait cancelled.").withStructured(json);
            }

            JsonObject state = context.onGameThread(new Callable<JsonObject>() {
                @Override
                public JsonObject call() {
                    return snapshot(area);
                }
            });

            if (isSatisfied(waitFor, state, screenName, chatContains, chat)) {
                state.addProperty("conditionMet", true);
                state.addProperty("waitedMillis", System.currentTimeMillis() - start);
                state.addProperty("waitFor", waitFor);
                return ToolResult.text("Condition '" + waitFor + "' met after "
                    + (System.currentTimeMillis() - start) + "ms."
                    + ("worldLoaded".equals(waitFor) && state.has("pausesOnLostFocus")
                        ? ClientStateTools.LOST_FOCUS_PAUSE_WARNING : ""))
                    .withStructured(state);
            }

            Thread.sleep(POLL_INTERVAL_MILLIS);
        }

        JsonObject timedOut = context.onGameThread(new Callable<JsonObject>() {
            @Override
            public JsonObject call() {
                return snapshot(area);
            }
        });
        timedOut.addProperty("conditionMet", false);
        timedOut.addProperty("waitedMillis", System.currentTimeMillis() - start);
        timedOut.addProperty("waitFor", waitFor);
        return ToolResult.text("Timed out after " + ticks + " tick(s) waiting for '"
            + waitFor + "'. The state above is what it looks like now.")
            .withStructured(timedOut);
    }

    /** Client state relevant to every supported condition, read in one hop to the game thread. */
    private static JsonObject snapshot(@Nullable ChunkArea area) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        JsonObject json = new JsonObject();
        json.addProperty("worldLoaded", mc.world != null);
        json.addProperty("playerPresent", mc.player != null);
        json.addProperty("guiOpen", screen != null);
        json.addProperty("screenName", screen == null ? "none" : screen.getClass().getSimpleName());
        json.addProperty("paused", mc.isGamePaused());
        // Tested by type rather than by class name: the name is obfuscated in a released jar, so a
        // string comparison would quietly stop matching in exactly the build nobody tests this in.
        json.addProperty("terrainReady", !(screen instanceof GuiDownloadTerrain));
        if (mc.world != null && ClientStateTools.pausesOnLostFocus(mc)) {
            json.addProperty("pausesOnLostFocus", true);
        }
        if (area != null && mc.world != null && mc.player != null) {
            json.add("chunks", area.describe(mc));
            json.add("window", ClientStateTools.windowState(mc));
        }
        return json;
    }

    /**
     * The chunks a {@code chunksLoaded} wait covers. Resolved against the player each poll when no
     * centre was given, so "around me" stays around the player if they move.
     */
    private static final class ChunkArea {

        @Nullable
        private final Integer x;
        @Nullable
        private final Integer z;
        private final int radius;

        ChunkArea(@Nullable Integer x, @Nullable Integer z, int radius) {
            this.x = x;
            this.z = z;
            this.radius = radius;
        }

        /** Client thread only. */
        JsonObject describe(Minecraft mc) {
            BlockPos player = GameJson.blockPosOf(mc.player);
            int centreX = x == null ? player.getX() : x;
            int centreZ = z == null ? player.getZ() : z;
            int minChunkX = (centreX - radius) >> 4;
            int maxChunkX = (centreX + radius) >> 4;
            int minChunkZ = (centreZ - radius) >> 4;
            int maxChunkZ = (centreZ + radius) >> 4;

            int total = 0;
            int missing = 0;
            JsonArray missingList = new JsonArray();
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    total++;
                    Chunk chunk = mc.world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
                    if (chunk == null || chunk.isEmpty()) {
                        missing++;
                        if (missingList.size() < MAX_LISTED_CHUNKS) {
                            JsonArray pair = new JsonArray();
                            pair.add(chunkX);
                            pair.add(chunkZ);
                            missingList.add(pair);
                        }
                    }
                }
            }

            JsonObject json = new JsonObject();
            json.addProperty("centreX", centreX);
            json.addProperty("centreZ", centreZ);
            json.addProperty("radius", radius);
            json.addProperty("total", total);
            json.addProperty("missing", missing);
            if (missing > 0) {
                json.add("missingChunks", missingList);
            }
            // The meshes, not the data: a chunk the client holds but has not built is in a survey
            // and missing from a screenshot.
            json.addProperty("renderQueueEmpty", mc.renderGlobal.hasNoChunkUpdates());
            int renderReach = mc.gameSettings.renderDistanceChunks * 16;
            if (Math.abs(centreX - player.getX()) + radius > renderReach
                || Math.abs(centreZ - player.getZ()) + radius > renderReach) {
                json.addProperty("beyondRenderDistance", true);
            }
            return json;
        }
    }

    private static boolean isSatisfied(String waitFor, JsonObject state, String screenName,
        String chatContains, ClientChatRecorder.Capture chat) {

        if ("worldLoaded".equals(waitFor)) {
            // Three conditions, all learned the hard way. The world exists briefly before the player
            // spawns into it, and every player-facing tool fails in that window. Then the player
            // exists while GuiDownloadTerrain is still up and the client is still receiving chunks —
            // during which a screenshot captures the loading screen and a block read reports
            // loaded=false for terrain that is merely late.
            return state.get("worldLoaded").getAsBoolean()
                && state.get("playerPresent").getAsBoolean()
                && state.get("terrainReady").getAsBoolean();
        }
        if ("chunksLoaded".equals(waitFor) || "chunksRendered".equals(waitFor)) {
            JsonObject chunks = Json.getObject(state, "chunks");
            if (chunks == null || chunks.get("missing").getAsInt() > 0) {
                return false;
            }
            return "chunksLoaded".equals(waitFor) || chunks.get("renderQueueEmpty").getAsBoolean();
        }
        if ("worldUnloaded".equals(waitFor)) {
            return !state.get("worldLoaded").getAsBoolean();
        }
        if ("screenClosed".equals(waitFor)) {
            return !state.get("guiOpen").getAsBoolean();
        }
        if ("screenOpen".equals(waitFor)) {
            if (!state.get("guiOpen").getAsBoolean()) {
                return false;
            }
            if (screenName == null || screenName.isEmpty()) {
                return true;
            }
            return state.get("screenName").getAsString().toLowerCase(Locale.ROOT)
                .contains(screenName.toLowerCase(Locale.ROOT));
        }
        if ("chat".equals(waitFor)) {
            String needle = chatContains.toLowerCase(Locale.ROOT);
            for (ClientChatRecorder.Entry entry; (entry = chat.poll()) != null; ) {
                if (entry.getText().toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
