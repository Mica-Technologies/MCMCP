package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.client.ClientChatRecorder;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiScreen;
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

    private ClientSyncTools() {
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
                + "when it gave up instead of leaving you to infer it.")
            .schema(JsonSchema.object()
                .integer("ticks", "How long to wait, in ticks (20 per second). With 'waitFor' this is "
                    + "the timeout; without it, the exact time to wait. Defaults to 20.",
                    1, MAX_TIMEOUT_TICKS)
                .enumeration("waitFor", "A condition to wait for instead of a fixed delay.",
                    "worldLoaded", "worldUnloaded", "screenOpen", "screenClosed", "chat")
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

                if (waitFor == null) {
                    // A plain delay. Sleeping on the worker rather than blocking the client thread:
                    // the point is to let the game run, and a scheduled task that sleeps would stop
                    // the very ticks being waited for.
                    long start = System.currentTimeMillis();
                    long remaining = budgetMillis;
                    while (remaining > 0) {
                        if (context.getCancellation().isCancelled()) {
                            return ToolResult.text("Wait cancelled after "
                                + (System.currentTimeMillis() - start) + "ms.");
                        }
                        Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, remaining));
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
                // return instantly on a line from minutes ago, which is the opposite of waiting.
                final int chatBaseline = ClientChatRecorder.size();

                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < budgetMillis) {
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
                            return snapshot();
                        }
                    });

                    if (isSatisfied(waitFor, state, screenName, chatContains, chatBaseline)) {
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
                        return snapshot();
                    }
                });
                timedOut.addProperty("conditionMet", false);
                timedOut.addProperty("waitedMillis", System.currentTimeMillis() - start);
                timedOut.addProperty("waitFor", waitFor);
                return ToolResult.text("Timed out after " + ticks + " tick(s) waiting for '"
                    + waitFor + "'. The state above is what it looks like now.")
                    .withStructured(timedOut);
            })
            .build());
    }

    /** Client state relevant to every supported condition, read in one hop to the game thread. */
    private static JsonObject snapshot() {
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
        return json;
    }

    private static boolean isSatisfied(String waitFor, JsonObject state, String screenName,
        String chatContains, int chatBaseline) {

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
            int current = ClientChatRecorder.size();
            if (current <= chatBaseline) {
                return false;
            }
            String needle = chatContains.toLowerCase(Locale.ROOT);
            List<ClientChatRecorder.Entry> recent =
                ClientChatRecorder.recent(Math.min(300, current - chatBaseline));
            for (ClientChatRecorder.Entry entry : recent) {
                if (entry.getText().toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }
}
