package com.micatechnologies.minecraft.mcmcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientGuiTools;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.GuiNotification;
import net.minecraftforge.fml.common.StartupQuery;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Forge's world-load prompts — "this world was saved with different mods, continue?" — and keeping
 * the client endpoint usable while one is up.
 *
 * <h2>What the client thread is doing</h2>
 *
 * When an integrated server finds something it needs the player to confirm, its thread raises an
 * FML {@link StartupQuery} and blocks on it. The client thread is meanwhile inside
 * {@code Minecraft.launchIntegratedServer}, in a loop that waits for the server to start: it shows
 * the query as a {@link GuiNotification} (or its subclass {@code GuiConfirmation}), and every 200ms
 * draws it and lets it handle input. It does not return to the game loop, so it never runs
 * Minecraft's scheduled tasks — and every MCMCP tool, which reaches the client thread through those
 * tasks, waited out its timeout. Nothing said what the game was waiting for (issue #46).
 *
 * <p>It was worse than a timeout while MCMCP launched worlds from that same task queue: Minecraft
 * runs the queue holding its lock, so the launch held the lock throughout, and every
 * {@code addScheduledTask} blocked on it with no timeout at all. Worlds are launched from the client
 * tick now ({@code ClientWorldTools.launchFromTick}), as the world-select screen launches them.
 *
 * <h2>Getting onto the client thread anyway</h2>
 *
 * Drawing the prompt calls {@code drawDefaultBackground}, which posts Forge's
 * {@link GuiScreenEvent.BackgroundDrawnEvent} on the client thread. So while a prompt is up, or a
 * world launch is holding the client thread, {@link ClientThreadBridge} queues tool work here instead
 * of in Minecraft's queue, and this class runs it from that event — and from the client tick, so work
 * queued just as the prompt closed is not stranded. GUI tools then work on the prompt as on any
 * screen: {@code client_gui_widgets} lists Yes and No, {@code client_gui_click} answers.
 *
 * <h2>Answering in advance</h2>
 *
 * {@code client_world_load} can arm an answer. The next prompt is then answered as soon as it is
 * drawn, by pressing FML's own button, and what it asked is kept for {@code client_wait} to report.
 * FML's {@code fml.queryResult} system property would answer too, but it answers every query for
 * the life of the process before it is shown, and leaves no record of what was asked.
 */
@SideOnly(Side.CLIENT)
public final class ClientStartupQuery {

    /** Longest prompt text carried in a result. Missing-entry lists run to hundreds of lines. */
    private static final int MAX_TEXT_CHARS = 2000;

    /** How long an armed answer waits for a prompt before it lapses. */
    private static final long ARM_MILLIS = 10L * 60L * 1000L;

    private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    private static boolean registered;

    /** Set while {@link #drain} runs, so work that redraws the prompt cannot drain re-entrantly. */
    private static boolean draining;

    private static volatile boolean launching;

    @Nullable
    private static volatile String armedAnswer;

    private static volatile long armedUntilMillis;

    @Nullable
    private static volatile JsonObject lastAnswered;

    private ClientStartupQuery() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new Events());
        registered = true;
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    /**
     * Whether the client thread is held where Minecraft's scheduled tasks do not run, but this
     * class's queue is drained: a startup prompt is up, or a singleplayer world is launching.
     *
     * <p>Read from an HTTP worker. The fields are references and flags written by the client thread,
     * read here without a lock; a stale answer only sends a task down the other queue, and both are
     * drained once the launch ends.
     */
    public static boolean holdsClientThread(Minecraft mc) {
        if (launching || mc.currentScreen instanceof GuiNotification) {
            return true;
        }
        IntegratedServer server = mc.getIntegratedServer();
        return mc.world == null && mc.isIntegratedServerRunning() && server != null
            && !server.serverIsInRunLoop();
    }

    /**
     * Brackets a world launch MCMCP started, so routing covers it from its first moment rather than
     * from when the integrated server object exists. Client thread only.
     */
    public static void markLaunching(boolean value) {
        launching = value;
    }

    /** Queues work for the client thread, to run when the prompt is next drawn or the next tick. */
    public static void submit(Runnable task) {
        register();
        PENDING.add(task);
    }

    private static void drain() {
        if (draining) {
            return;
        }
        draining = true;
        try {
            for (Runnable task; (task = PENDING.poll()) != null; ) {
                try {
                    task.run();
                }
                catch (Throwable t) {
                    // Never let tool work escape onto the client thread; the task's own future
                    // carries the failure back to its caller.
                    Mcmcp.LOGGER.error("MCMCP task failed while a startup prompt was up", t);
                }
            }
        }
        finally {
            draining = false;
        }
    }

    // ------------------------------------------------------------------
    // Describing and answering
    // ------------------------------------------------------------------

    /**
     * The prompt on screen, or null when there is none. Client thread only.
     *
     * <p>{@code GuiNotification.query} is protected, and read by name: it is FML's field, which
     * reobfuscation does not rename.
     */
    @Nullable
    public static JsonObject describe(Minecraft mc) {
        GuiScreen screen = mc.currentScreen;
        if (!(screen instanceof GuiNotification)) {
            return null;
        }
        StartupQuery query = queryOf((GuiNotification) screen);
        JsonObject json = new JsonObject();
        boolean confirm = query != null && query.getResult() != null;
        json.addProperty("kind", confirm ? "confirm" : "notice");
        String text = query == null ? "" : query.getText();
        json.addProperty("lines", text.split("\n").length);
        if (text.length() > MAX_TEXT_CHARS) {
            json.addProperty("text", text.substring(0, MAX_TEXT_CHARS) + "...");
            json.addProperty("textTruncated", true);
        }
        else {
            json.addProperty("text", text);
        }
        JsonArray buttons = new JsonArray();
        for (GuiButton button : ClientGuiTools.buttonsOn(screen)) {
            buttons.add(button.displayString);
        }
        json.add("buttons", buttons);
        json.addProperty("blocksWorldLoad", true);
        json.addProperty("howToAnswer", confirm
            ? "client_gui_click label '" + labelOf(screen, 0) + "' to continue loading or '"
                + labelOf(screen, 1) + "' to cancel."
            : "client_gui_click label '" + labelOf(screen, 0) + "' to acknowledge it.");
        return json;
    }

    @Nullable
    private static StartupQuery queryOf(GuiNotification screen) {
        try {
            Field field = GuiNotification.class.getDeclaredField("query");
            field.setAccessible(true);
            return (StartupQuery) field.get(screen);
        }
        catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private static String labelOf(GuiScreen screen, int id) {
        for (GuiButton button : ClientGuiTools.buttonsOn(screen)) {
            if (button.id == id) {
                return button.displayString;
            }
        }
        return id == 0 ? "Yes" : "No";
    }

    /**
     * Arms an answer for the next prompt: {@code "continue"} or {@code "cancel"}. Null disarms.
     * Callable from any thread.
     */
    public static void arm(@Nullable String answer) {
        register();
        armedAnswer = answer;
        armedUntilMillis = answer == null ? 0L : System.currentTimeMillis() + ARM_MILLIS;
        if (answer != null) {
            lastAnswered = null;
        }
    }

    /**
     * The prompt last answered by an armed answer, once: it is cleared as it is returned, so the
     * same answer is not reported against a later load. Callable from any thread.
     */
    @Nullable
    public static synchronized JsonObject takeLastAnswered() {
        JsonObject answered = lastAnswered;
        lastAnswered = null;
        return answered;
    }

    /** Answers the prompt on screen with the armed answer, if one is armed. Client thread only. */
    private static void answerIfArmed(Minecraft mc) {
        String answer = armedAnswer;
        if (answer == null) {
            return;
        }
        if (System.currentTimeMillis() > armedUntilMillis) {
            armedAnswer = null;
            return;
        }
        GuiScreen screen = mc.currentScreen;
        JsonObject described = describe(mc);
        if (described == null) {
            return;
        }
        // Yes is FML's button 0 and No its button 1; a notice has only button 0, Done, whatever
        // was armed, because acknowledging it is the only answer it takes.
        boolean confirm = "confirm".equals(described.get("kind").getAsString());
        int id = confirm && "cancel".equals(answer) ? 1 : 0;
        for (GuiButton button : ClientGuiTools.buttonsOn(screen)) {
            if (button.id != id) {
                continue;
            }
            try {
                if (ClientGuiTools.pressButton(screen, button)) {
                    described.addProperty("answeredWith", button.displayString);
                    described.addProperty("answer", answer.toLowerCase(Locale.ROOT));
                    described.remove("howToAnswer");
                    described.remove("blocksWorldLoad");
                    lastAnswered = described;
                    Mcmcp.LOGGER.info("MCMCP answered a Forge startup prompt with '"
                        + button.displayString + "', as armed by client_world_load");
                }
            }
            catch (Exception e) {
                Mcmcp.LOGGER.error("MCMCP could not answer a Forge startup prompt", e);
                armedAnswer = null;
            }
            return;
        }
    }

    /** Nested for the reason {@link ClientInputLock.Events} is: a second register() cannot double it. */
    public static class Events {

        @SubscribeEvent
        public void onBackgroundDrawn(GuiScreenEvent.BackgroundDrawnEvent event) {
            if (!(event.getGui() instanceof GuiNotification)) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            answerIfArmed(mc);
            drain();
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (armedAnswer != null && mc.world != null && mc.player != null) {
                // Loaded: whatever the load was going to ask, it has asked.
                armedAnswer = null;
            }
            drain();
        }
    }
}
