package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Holds the human's keyboard and mouse out of the game while a model is driving the client.
 *
 * <h2>What it is for</h2>
 *
 * A model driving the client aims the camera, reads what is under the crosshair and acts on it. A
 * hand resting on a mouse moves that crosshair between the aiming and the acting, and the model has
 * no way to know it happened — it sees a plan that should have worked and a result that did not
 * match. Physically not touching the machine works, and is a lot to ask of somebody watching an
 * agent play for twenty minutes.
 *
 * <h2>Where real input actually enters the game</h2>
 *
 * There are exactly three consumers, and blocking two of them is not enough:
 *
 * <ol>
 *   <li>{@code Minecraft.runTick} hands events to {@code currentScreen.handleInput()} — any open
 *       GUI.</li>
 *   <li>{@code Minecraft.runTick} then calls {@code runTickMouse} and {@code runTickKeyboard}, which
 *       drive the keybindings and open the pause menu.</li>
 *   <li>{@code EntityRenderer.updateCameraAndRender} turns the camera — <b>per frame, not per
 *       tick</b>, and from {@code Mouse.getDX()/getDY()} rather than from the event queue.</li>
 * </ol>
 *
 * <p>The first two both read the LWJGL event queues, and both run after Forge's client tick START
 * hook, so draining {@link Keyboard#next()} and {@link Mouse#next()} there empties the queues before
 * either of them looks. The third needs a render tick START hook, because at twenty ticks against a
 * few hundred frames a second, a tick-granular block would let most mouse movement straight through.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * It does not open a {@link net.minecraft.client.gui.GuiScreen} to swallow input, which is the
 * obvious approach and the wrong one. {@code runTickKeyboard} is where {@code keyBindAttack.isPressed()}
 * turns into {@code clickMouse()}, and {@code sendClickBlockToController} is guarded on
 * {@code currentScreen == null} — so a blocking screen would also switch off MCMCP's own
 * {@code client_interact}. Draining the queues leaves synthetic input untouched, because
 * {@link ClientInputScheduler} writes keybinding state directly and {@code MovementInputFromOptions}
 * reads it without consulting the event queue at all.
 *
 * <h2>Getting out</h2>
 *
 * This takes control of somebody's computer, so the way out has to work even when everything else
 * has gone wrong:
 *
 * <ul>
 *   <li><b>Double Escape</b> releases it, always. Escapes are read out of the drained events, so a
 *       single press does nothing — the pause menu never opens — and two inside
 *       {@link #ESCAPE_WINDOW_MILLIS} let go.</li>
 *   <li><b>It expires.</b> A model that crashes mid-task cannot leave the machine locked forever.</li>
 *   <li><b>Leaving the world releases it</b>, like {@link ClientInputScheduler#releaseAll()}.</li>
 *   <li><b>If MCMCP stops ticking, the lock stops with it.</b> The blocking is something this class
 *       does every tick, not a state it puts the game into, so anything that stops the mod hands
 *       input straight back rather than stranding it.</li>
 *   <li>A chat line on lock and a banner on screen say all of the above, because an affordance
 *       nobody knows about is not an affordance.</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public final class ClientInputLock {

    /**
     * How close together two Escapes have to be to count as a double press.
     *
     * <p>Generous, because the cost of being wrong is asymmetric. Releasing on a slow double press
     * the human meant costs an agent one interrupted action; failing to release costs them their
     * keyboard. Nothing else is bound to Escape while the lock is on, so there is no accidental
     * double press to guard against.
     */
    private static final long ESCAPE_WINDOW_MILLIS = 750L;

    /** Longest a banner reason is shown before it is cut, so it cannot run off the screen. */
    private static final int MAX_REASON_LENGTH = 80;

    private static final Object STATE = new Object();

    private static boolean registered;

    private static volatile boolean locked;
    private static volatile long expiresAtMillis;
    private static volatile String reason = "";

    /**
     * The player's own {@code pauseOnLostFocus}, saved so it can be given back.
     *
     * <p>Suppressed while locked because {@code EntityRenderer} opens the pause menu when the window
     * loses focus, and reading what an agent is doing means alt-tabbing to another window. Never
     * written through {@code GameSettings.saveOptions}, so even a crash mid-lock cannot make the
     * change outlive the session.
     */
    private static boolean savedPauseOnLostFocus;

    private static long lastEscapeMillis;

    /**
     * How long mouse input stays discarded after the window regains focus.
     *
     * <p>Long enough to catch the activating click, short enough that nobody clicks deliberately
     * inside it.
     */
    private static final long FOCUS_CLICK_GRACE_MILLIS = 200L;

    /**
     * Whether the window was active at the last check. Client thread only. Starts true, so a window
     * that launches already focused is not mistaken for one regaining focus.
     */
    private static boolean wasActive = true;
    private static long ignoreMouseUntilMillis;

    private ClientInputLock() {
    }

    /** Subscribes to the client and render ticks. Called once from the client proxy. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ClientInputLock.Events());
        registered = true;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public static boolean isLocked() {
        return locked;
    }

    /** Seconds until the lock expires on its own, or 0 when it is not locked. */
    public static int secondsRemaining() {
        if (!locked) {
            return 0;
        }
        long remaining = expiresAtMillis - System.currentTimeMillis();
        return remaining <= 0L ? 0 : (int) ((remaining + 999L) / 1000L);
    }

    /** Why the lock was taken, as given by whoever took it. Empty when none was given. */
    public static String reason() {
        return reason;
    }

    // ------------------------------------------------------------------
    // Taking and giving back
    // ------------------------------------------------------------------

    /**
     * Locks the human out for {@code seconds}, or extends an existing lock to that long.
     *
     * <p>Must run on the client thread: it unlatches keys and reads the game settings.
     *
     * @param why shown to the human on screen, so they can tell a task from a stuck agent
     */
    public static void lock(int seconds, String why) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean wasLocked;

        synchronized (STATE) {
            wasLocked = locked;
            if (!wasLocked) {
                savedPauseOnLostFocus = mc.gameSettings.pauseOnLostFocus;
                mc.gameSettings.pauseOnLostFocus = false;
            }
            locked = true;
            expiresAtMillis = System.currentTimeMillis() + seconds * 1000L;
            reason = why == null ? "" : why.trim();
            // Not carried over from before the lock: a stale press from minutes ago would release it
            // on the first Escape rather than the second.
            lastEscapeMillis = 0L;
        }

        if (!wasLocked) {
            // Whatever the human was physically holding is latched down in the keybinding state, and
            // the release event is about to be swallowed. Without this they rejoin walking forward.
            KeyBinding.unPressAllKeys();
            dismissLostFocusPause(mc);
            tell(TextFormatting.YELLOW + "MCMCP has taken your keyboard and mouse"
                + (reason.isEmpty() ? "" : ": " + reason)
                + TextFormatting.GRAY + " — press Escape twice to take them back.");
        }
    }

    /**
     * Gives input back.
     *
     * @param cause a short phrase for the chat line, e.g. {@code "released by the model"}
     * @return whether it had been locked
     */
    public static boolean release(String cause) {
        boolean wasLocked;

        synchronized (STATE) {
            wasLocked = locked;
            if (wasLocked) {
                Minecraft.getMinecraft().gameSettings.pauseOnLostFocus = savedPauseOnLostFocus;
            }
            locked = false;
            expiresAtMillis = 0L;
            reason = "";
            lastEscapeMillis = 0L;
        }

        if (wasLocked) {
            // Anything held when the lock came off would otherwise stay latched: the press that
            // latched it was swallowed, so the game never saw the matching release.
            KeyBinding.unPressAllKeys();
            tell(TextFormatting.GREEN + "MCMCP gave your keyboard and mouse back"
                + TextFormatting.GRAY + " (" + cause + ").");
        }
        return wasLocked;
    }

    /**
     * Closes a pause menu that is only on screen because the window lost focus.
     *
     * <p>Suppressing {@code pauseOnLostFocus} stops the <em>next</em> one, which is no help at all if
     * one is already up: reading what an agent is doing means alt-tabbing away, and by the time the
     * model calls this the menu has usually been open for a while. It is not cosmetic either —
     * {@code EntityPlayerSP} stops responding to movement input while it is up, so a model that
     * locked the input would find its own {@code client_move} silently doing nothing.
     *
     * <p>Only {@link GuiIngameMenu}, and only on the way in. Anything else on screen is either
     * something the human deliberately opened or something the model opened for itself, and neither
     * is furniture to be cleared away.
     */
    private static void dismissLostFocusPause(Minecraft mc) {
        if (mc.currentScreen instanceof GuiIngameMenu) {
            mc.displayGuiScreen(null);
        }
    }

    private static void tell(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null) {
            mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(message));
        }
    }

    // ------------------------------------------------------------------
    // The blocking itself
    // ------------------------------------------------------------------

    /**
     * Empties the LWJGL event queues so nothing downstream sees a keystroke or a click.
     *
     * <p>Runs at client tick START, which is ahead of both {@code currentScreen.handleInput()} and
     * {@code runTickKeyboard}/{@code runTickMouse}. Escapes are picked out on the way past, because
     * this is the only place they still exist — by the time anything else could look for them they
     * have been discarded.
     */
    private static void drainInput() {
        long now = System.currentTimeMillis();

        while (Keyboard.next()) {
            if (!Keyboard.getEventKeyState() || Keyboard.getEventKey() != Keyboard.KEY_ESCAPE) {
                continue;
            }
            if (lastEscapeMillis != 0L && now - lastEscapeMillis <= ESCAPE_WINDOW_MILLIS) {
                release("you pressed Escape twice");
                // Whatever is left in the queue was typed while still locked, and letting it through
                // now would fire it at the game all at once.
                drainQueues();
                return;
            }
            lastEscapeMillis = now;
        }

        drainQueues();
    }

    /** Discards every pending mouse event and the accumulated look delta. */
    private static void drainQueues() {
        while (Mouse.next()) {
            // Discarded. Reading the event is what removes it.
        }
        consumeLookDelta();
    }

    /**
     * Throws away the mouse movement the camera would have turned by.
     *
     * <p>{@code Mouse.getDX()} returns the movement accumulated since it was last called and resets
     * the accumulator, so calling it here is what leaves {@code MouseHelper.mouseXYChange} — which
     * runs moments later inside {@code EntityRenderer.updateCameraAndRender} — reading zero.
     */
    private static void consumeLookDelta() {
        Mouse.getDX();
        Mouse.getDY();
    }

    /**
     * Keeps mouse input aimed at another window out of a game window that does not have focus.
     *
     * <p>Vanilla already skips mouse look while the window is inactive, but LWJGL keeps accumulating
     * the movement of a cursor passing over it. The first active frame then turns the camera by all of
     * it at once, so an agent that aimed before a person hovered by finds itself aimed somewhere else.
     * This only bites with {@code pauseOnLostFocus} off — otherwise the pause menu regrabs the mouse,
     * which resets the delta — and off is exactly how an unattended client runs.
     *
     * <p>The click that brings the window back is dropped too. It arrives with the activation, and
     * with no pause menu to land on it would attack or use whatever the crosshair is on.
     * {@link #FOCUS_CLICK_GRACE_MILLIS} covers it arriving a message pump or two later.
     *
     * <p>Deliberately narrower than the lock: only mouse input, and only while the window is not the
     * one the person is using. Synthetic input is unaffected, for the reasons the class comment gives.
     */
    private static void discardUnfocusedMouseInput() {
        if (!Display.isCreated()) {
            return;
        }
        boolean active = Display.isActive();
        long now = System.currentTimeMillis();
        if (active && !wasActive) {
            ignoreMouseUntilMillis = now + FOCUS_CLICK_GRACE_MILLIS;
        }
        wasActive = active;
        if (!active || now < ignoreMouseUntilMillis) {
            drainQueues();
        }
    }

    /**
     * Event subscriber, kept as a nested class so an accidental second {@code register()} cannot put
     * the lock's own statics on the event bus twice.
     */
    public static class Events {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.START) {
                return;
            }

            if (!locked) {
                discardUnfocusedMouseInput();
                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world == null || mc.player == null) {
                release("you left the world");
                return;
            }
            if (System.currentTimeMillis() >= expiresAtMillis) {
                release("it expired");
                return;
            }

            try {
                drainInput();
            }
            catch (Exception e) {
                // An exception escaping onto the game thread crashes the game, and a lock that
                // cannot read the input queue cannot be escaped from either. Both are answered by
                // letting go: the human gets their machine back and the log says why.
                Mcmcp.LOGGER.error("MCMCP could not hold the input lock, so it has been released", e);
                release("MCMCP hit an error");
            }
        }

        /**
         * Takes the camera delta before the renderer can turn on it.
         *
         * <p>Render tick START fires immediately before {@code updateCameraAndRender}, which is the
         * only place mouse look happens and is a per-frame path — the client tick drain above runs
         * twenty times a second against a frame rate an order of magnitude higher, so on its own it
         * would let most of a mouse movement through.
         */
        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START) {
                return;
            }
            if (!locked) {
                discardUnfocusedMouseInput();
                return;
            }
            try {
                consumeLookDelta();
            }
            catch (Exception e) {
                Mcmcp.LOGGER.error("MCMCP could not hold the input lock, so it has been released", e);
                release("MCMCP hit an error");
            }
        }

        /**
         * Draws the banner saying the input is held and how to get it back.
         *
         * <p>The chat line at lock time is the guarantee — it survives being scrolled past and does
         * not depend on the overlay rendering at all — and this is what somebody who looked away and
         * came back sees.
         */
        @SubscribeEvent
        public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
            if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || !locked) {
                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.fontRenderer == null) {
                return;
            }

            String detail = reason;
            if (detail.length() > MAX_REASON_LENGTH) {
                detail = detail.substring(0, MAX_REASON_LENGTH - 1) + "…";
            }
            String headline = "MCMCP is driving" + (detail.isEmpty() ? "" : " — " + detail);
            String escape = "Input locked · Escape twice to take back · " + secondsRemaining() + "s left";

            ScaledResolution resolution = event.getResolution();
            int width = Math.max(mc.fontRenderer.getStringWidth(headline),
                mc.fontRenderer.getStringWidth(escape));
            int left = (resolution.getScaledWidth() - width) / 2;
            int top = 4;

            Gui.drawRect(left - 4, top - 3, left + width + 4, top + 21, 0xB0000000);
            mc.fontRenderer.drawStringWithShadow(headline, left, top, 0xFFD24A);
            mc.fontRenderer.drawStringWithShadow(escape, left, top + 11, 0xBFBFBF);
        }
    }
}
