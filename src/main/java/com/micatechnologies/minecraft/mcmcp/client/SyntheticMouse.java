package com.micatechnologies.minecraft.mcmcp.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.lwjgl.input.Mouse;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Delivers a click to a {@link GuiScreen} the way the game itself does: by setting LWJGL's mouse
 * state, across real frames, and handing each event to the screen through Forge's input route.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code GuiScreen.mouseClicked} is not how the game delivers clicks. It is one step *inside* how
 * the game delivers clicks. The real path is {@code GuiScreen.handleInput()}, which pumps LWJGL's
 * event queue and, for each event, posts Forge's {@code MouseInputEvent.Pre}, calls
 * {@code handleMouseInput()} unless that was cancelled, then posts {@code MouseInputEvent.Post}.
 * Vanilla's {@code handleMouseInput} reads {@code Mouse.getEventX/getEventY/getEventButton/
 * getEventButtonState} and dispatches to {@code mouseClicked} or {@code mouseReleased}.
 *
 * <p>Each step is somewhere a screen or a mod can take the click instead:
 * <ul>
 *   <li>MalisisCore's screens override {@code handleMouseInput()} and hit-test
 *       {@code Mouse.getX()/getY()} themselves, so {@code mouseClicked} reached nothing.</li>
 *   <li>Mods that skin vanilla screens (LDIC's advancement tabs, #169) handle the click in
 *       {@code MouseInputEvent.Pre} and cancel it, or place a cursor in {@code Post}. Calling
 *       {@code handleMouseInput()} directly skipped both.</li>
 *   <li>Scrolling lists (vanilla {@code GuiSlot}, Forge's {@code GuiScrollingList}, the mod list) and
 *       NanoVG frameworks such as OneConfig's poll {@code Mouse.isButtonDown} and the cursor while
 *       drawing (#166). {@code GuiSlot} even hit-tests the press event against the cursor position
 *       its last {@code drawScreen} saw. A press and release inside one task, with no frame drawn
 *       between, is invisible to them.</li>
 * </ul>
 *
 * <h2>A click is three frames</h2>
 *
 * <p>So a click is played out over real frames, the way a person's is:
 * <ol>
 *   <li><b>hover</b>: the cursor is put at the point with the button up, and one frame is drawn, so
 *       every screen's idea of where the mouse is matches the point;</li>
 *   <li><b>press</b>: the button goes down and the press event is delivered; the frame is drawn with
 *       it held, which is what polling widgets see;</li>
 *   <li><b>release</b>: the button comes up at the same point and the release event is delivered;
 *       one more frame lets a polling widget see the release.</li>
 * </ol>
 *
 * <p>{@code Display.update()} refills LWJGL's state from the real mouse at the end of every frame,
 * so the point and the button are written back at the start of each client tick and render tick
 * ({@link #reassert}, called by {@link ClientInputScheduler}), before anything reads them. After the
 * last frame nothing is written and the next refill hands the mouse back to the person.
 *
 * <p>If no frame is drawn (a stalled or minimised client), the caller finishes the click at once
 * with {@link #finishNow}: every remaining event in one task, which is still right for most screens.
 *
 * <h2>What it sets, and why both coordinate pairs</h2>
 *
 * <p>Vanilla's {@code handleMouseInput} reads {@code Mouse.getEventX()/getEventY()}. MalisisGui reads
 * {@code Mouse.getX()/getY()}. Neither is wrong, so both pairs are set to the same point.
 *
 * <p>Points are LWJGL display pixels. A screenshot pixel is a display pixel with the Y axis flipped
 * ({@link #fromPixel}); a point in a screen's own coordinates converts with that screen's
 * {@code width}/{@code height} ({@link #fromGui}), exactly as vanilla converts back. Not the
 * {@code ScaledResolution}: a screen initialised at another size (LDIC's scaled-up inventories, #163)
 * reads the mouse with its own size, and converting with the game's put every click at twice the
 * intended point.
 *
 * <h2>Failure is not fatal</h2>
 *
 * <p>Every field is looked up once and cached. If LWJGL is not the expected implementation — a
 * shaded build, or LWJGL3ify's compatibility shim — {@link #isAvailable()} returns false and the
 * caller falls back to {@code mouseClicked}. Nothing here throws its way out to the game thread.
 */
@SideOnly(Side.CLIENT)
public final class SyntheticMouse {

    /**
     * LWJGL mouse state, reflected once.
     *
     * <p>These are all {@code private static} on {@link Mouse} with no setters, because nothing was
     * ever meant to author an event. The names are LWJGL's own and are not obfuscated — unlike
     * Minecraft's, they need no second SRG spelling.
     */
    @Nullable
    private static final Field FIELD_X = field("x");
    @Nullable
    private static final Field FIELD_Y = field("y");
    @Nullable
    private static final Field FIELD_EVENT_X = field("event_x");
    @Nullable
    private static final Field FIELD_EVENT_Y = field("event_y");
    @Nullable
    private static final Field FIELD_EVENT_DX = field("event_dx");
    @Nullable
    private static final Field FIELD_EVENT_DY = field("event_dy");
    @Nullable
    private static final Field FIELD_EVENT_BUTTON = field("eventButton");
    @Nullable
    private static final Field FIELD_EVENT_STATE = field("eventState");
    @Nullable
    private static final Field FIELD_EVENT_DWHEEL = field("event_dwheel");
    @Nullable
    private static final Field FIELD_EVENT_NANOS = field("event_nanos");
    /** What {@code Mouse.isButtonDown} reads. Optional: without it, polling widgets just miss out. */
    @Nullable
    private static final Field FIELD_BUTTONS = field("buttons");

    /**
     * Forge's {@code GuiScreen.mouseHandled}: set by a screen that took an event, which suppresses
     * {@code MouseInputEvent.Post}. Forge's own field, so its name is the same in a released jar.
     */
    @Nullable
    private static final Field FIELD_MOUSE_HANDLED = screenField("mouseHandled");

    private SyntheticMouse() {
    }

    @Nullable
    private static Field field(String name) {
        try {
            Field f = Mouse.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
        catch (NoSuchFieldException | RuntimeException unavailable) {
            // A different LWJGL. isAvailable() reports it and callers fall back.
            return null;
        }
    }

    @Nullable
    private static Field screenField(String name) {
        try {
            Field f = GuiScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
        catch (NoSuchFieldException | RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * Whether a synthetic event can be authored on this LWJGL build.
     *
     * <p>Checked before use rather than at class-load time so a mismatch degrades to the
     * {@code mouseClicked} path instead of taking the endpoint down.
     */
    public static boolean isAvailable() {
        return FIELD_X != null && FIELD_Y != null
            && FIELD_EVENT_X != null && FIELD_EVENT_Y != null
            && FIELD_EVENT_DX != null && FIELD_EVENT_DY != null
            && FIELD_EVENT_BUTTON != null && FIELD_EVENT_STATE != null
            && FIELD_EVENT_DWHEEL != null && FIELD_EVENT_NANOS != null;
    }

    /**
     * Whether {@code screen} handles mouse input itself rather than leaving it to {@link GuiScreen}.
     *
     * <p>Used only to explain in the tool result which path a click took.
     */
    public static boolean overridesHandleMouseInput(GuiScreen screen) {
        Method method = handleMouseInputMethod(screen);
        return method != null && method.getDeclaringClass() != GuiScreen.class;
    }

    /**
     * Finds {@code handleMouseInput} on the screen's own hierarchy.
     *
     * <p>Named twice: a dev client runs deobfuscated and a released jar is reobfuscated, and a lookup
     * by one name alone works in exactly one of the two.
     */
    @Nullable
    private static Method handleMouseInputMethod(GuiScreen screen) {
        Class<?> type = screen.getClass();
        while (type != null && type != Object.class) {
            for (String name : new String[]{"handleMouseInput", "func_146274_d"}) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method;
                }
                catch (NoSuchMethodException ignored) {
                    // Try the other spelling, then the superclass.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Coordinates
    // ------------------------------------------------------------------

    /** A screenshot pixel (top-left origin) as an LWJGL point (bottom-left origin): {x, y}. */
    public static int[] fromPixel(int pixelX, int pixelY) {
        Minecraft mc = Minecraft.getMinecraft();
        return new int[]{pixelX, MousePoints.pixelToLwjglY(pixelY, mc.displayHeight)};
    }

    /**
     * A point in the screen's own coordinates as an LWJGL point. Inverts vanilla's
     * {@code x = eventX * width / displayWidth} and {@code y = height - eventY * height / displayHeight - 1},
     * with the screen's own size, so the screen reads back the point it was asked for.
     */
    public static int[] fromGui(GuiScreen screen, int guiX, int guiY) {
        Minecraft mc = Minecraft.getMinecraft();
        return new int[]{MousePoints.toLwjglX(guiX, screen.width, mc.displayWidth),
            MousePoints.toLwjglY(guiY, screen.height, mc.displayHeight)};
    }

    /** An LWJGL point as the screen itself reads it: {x, y} in its own coordinates. */
    public static int[] toGui(GuiScreen screen, int lwjglX, int lwjglY) {
        Minecraft mc = Minecraft.getMinecraft();
        return new int[]{MousePoints.toGuiX(lwjglX, screen.width, mc.displayWidth),
            MousePoints.toGuiY(lwjglY, screen.height, mc.displayHeight)};
    }

    // ------------------------------------------------------------------
    // Playing a gesture out
    // ------------------------------------------------------------------

    /** What happened to a gesture, for the tool to report. */
    public static final class Outcome {
        /** "lwjgl" across frames, or "lwjgl-immediate" when no frame was drawn in time. */
        public String via = "lwjgl";
        /** "MouseInputEvent.Pre" when a mod cancelled the press, so the screen never saw it. */
        @Nullable
        public String cancelledBy;
        /** Frames drawn while the gesture played out. */
        public int frames;
        /** Whether LWJGL's button state could be held for polling widgets. */
        public boolean polled = FIELD_BUTTONS != null;
        /** Wheel events a mod cancelled in MouseInputEvent.Pre. */
        public int wheelCancelled;
    }

    /** What one frame of a gesture does. */
    private enum Kind {
        /** The cursor is put at the point and a frame drawn, with no event. */
        HOVER,
        PRESS,
        RELEASE,
        /** The cursor moves to the point, as a motion event: a drag when the button is held. */
        MOVE,
        /** One notch of the wheel at the point. */
        WHEEL
    }

    /** One frame of a gesture: where the cursor is, whether the button is down, and the event sent. */
    private static final class Step {
        final Kind kind;
        final int x;
        final int y;
        final boolean down;
        final int wheel;

        Step(Kind kind, int x, int y, boolean down, int wheel) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.down = down;
            this.wheel = wheel;
        }
    }

    private static final class Gesture {
        final List<Step> steps;
        final int button;
        /** How long the cursor stays at the last point once done, for a hover; 0 for none. */
        final long parkMillis;
        final CompletableFuture<Outcome> future = new CompletableFuture<>();
        final Outcome outcome = new Outcome();
        /** The step in force; the first is applied at once, the rest one per frame. */
        int index;

        final int savedEventX = Mouse.getEventX();
        final int savedEventY = Mouse.getEventY();
        final int savedEventButton = Mouse.getEventButton();
        final boolean savedEventState = Mouse.getEventButtonState();
        final int savedEventDWheel = Mouse.getEventDWheel();

        Gesture(List<Step> steps, int button, long parkMillis) {
            this.steps = steps;
            this.button = button;
            this.parkMillis = parkMillis;
        }

        Step step() {
            return steps.get(Math.min(index, steps.size() - 1));
        }
    }

    /** The gesture being played out, or null. Client thread only. */
    @Nullable
    private static Gesture active;

    /** Where a finished hover holds the cursor, until {@link #parkedUntil}. Client thread only. */
    private static int parkedX;
    private static int parkedY;
    private static long parkedUntil;

    /** Most wheel notches one scroll delivers. */
    public static final int MAX_WHEEL_NOTCHES = 100;

    /** What LWJGL reports for one notch of a real wheel. Screens use only its sign. */
    private static final int WHEEL_NOTCH = 120;

    /**
     * Starts a click at an LWJGL point and returns at once; it plays out over the next three frames:
     * hover, press held, release. Client thread only. The future completes on the client thread.
     *
     * <p>A gesture still in flight is finished first, so two never interleave.
     *
     * @return null when this LWJGL build cannot be driven; the caller should fall back to
     *         {@code mouseClicked}
     */
    @Nullable
    public static CompletableFuture<Outcome> begin(int lwjglX, int lwjglY, int button) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Kind.HOVER, lwjglX, lwjglY, false, 0));
        steps.add(new Step(Kind.PRESS, lwjglX, lwjglY, true, 0));
        steps.add(new Step(Kind.RELEASE, lwjglX, lwjglY, false, 0));
        return start(new Gesture(steps, button, 0L));
    }

    /**
     * Turns the wheel {@code notches} times at a point, one notch a frame after a hover frame
     * (#182). Positive scrolls up, as a real wheel turned away does. {@code GuiSlot} reads
     * {@code Mouse.getEventDWheel()} in {@code handleMouseInput}, and only while the cursor its last
     * {@code drawScreen} saw is inside the list, which is what the hover frame is for.
     */
    @Nullable
    public static CompletableFuture<Outcome> scroll(int lwjglX, int lwjglY, int notches) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Kind.HOVER, lwjglX, lwjglY, false, 0));
        int count = Math.min(MAX_WHEEL_NOTCHES, Math.abs(notches));
        for (int i = 0; i < count; i++) {
            steps.add(new Step(Kind.WHEEL, lwjglX, lwjglY, false, notches > 0 ? WHEEL_NOTCH : -WHEEL_NOTCH));
        }
        steps.add(new Step(Kind.HOVER, lwjglX, lwjglY, false, 0));
        return start(new Gesture(steps, -1, 0L));
    }

    /**
     * Presses at one point, moves to another over {@code moves} frames with the button held, and
     * releases there (#182): a scrollbar, a slider, or a drag-and-drop. {@code GuiSlot}'s bar follows
     * {@code Mouse.isButtonDown} and the cursor while it draws, so each move is a frame of its own.
     */
    @Nullable
    public static CompletableFuture<Outcome> drag(int fromX, int fromY, int toX, int toY, int button,
                                                  int moves) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Kind.HOVER, fromX, fromY, false, 0));
        // A motion event with the button up before the press: GuiSlot arms its drag only from that
        // state, and its own scrollBy (a list scrolled by text) leaves it disarmed until one comes.
        steps.add(new Step(Kind.MOVE, fromX, fromY, false, 0));
        steps.add(new Step(Kind.PRESS, fromX, fromY, true, 0));
        int count = Math.max(1, moves);
        for (int i = 1; i <= count; i++) {
            steps.add(new Step(Kind.MOVE, fromX + (toX - fromX) * i / count,
                fromY + (toY - fromY) * i / count, true, 0));
        }
        steps.add(new Step(Kind.RELEASE, toX, toY, false, 0));
        return start(new Gesture(steps, button, 0L));
    }

    /**
     * Moves the cursor to a point without clicking, and keeps it there for {@code holdMillis} after
     * the gesture ends (#176), so a tooltip drawn under it stays up for a screenshot taken next. A
     * real cursor would stay put by itself; this one is overwritten by every poll of the real mouse.
     */
    @Nullable
    public static CompletableFuture<Outcome> hover(int lwjglX, int lwjglY, long holdMillis) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Kind.HOVER, lwjglX, lwjglY, false, 0));
        steps.add(new Step(Kind.MOVE, lwjglX, lwjglY, false, 0));
        steps.add(new Step(Kind.HOVER, lwjglX, lwjglY, false, 0));
        return start(new Gesture(steps, -1, Math.max(0L, holdMillis)));
    }

    /** Lets go of a parked cursor at once. Client thread only. */
    public static void unpark() {
        parkedUntil = 0L;
    }

    /** Whether a hover is holding the cursor, and where: {x, y} in LWJGL pixels, or null. */
    @Nullable
    public static int[] parked() {
        return System.currentTimeMillis() < parkedUntil ? new int[] {parkedX, parkedY} : null;
    }

    @Nullable
    private static CompletableFuture<Outcome> start(Gesture gesture) {
        if (!isAvailable()) {
            return null;
        }
        finishNow();
        unpark();
        active = gesture;
        assertState(gesture);
        return gesture.future;
    }

    /** Writes the gesture's point and button back over LWJGL's latest poll. Client thread only. */
    public static void reassert() {
        Gesture gesture = active;
        if (gesture != null) {
            assertState(gesture);
        }
        else if (parkedUntil != 0L) {
            assertParked();
        }
    }

    /** Advances the gesture by one frame. Called at the start of every render tick. */
    public static void onFrame() {
        Gesture gesture = active;
        if (gesture == null) {
            if (parkedUntil != 0L) {
                assertParked();
            }
            return;
        }
        // The first render tick after start() draws the first step's frame; each one after moves on.
        if (++gesture.outcome.frames > 1) {
            advance(gesture);
        }
        else {
            assertState(gesture);
        }
    }

    /**
     * Plays out whatever is left of the gesture in flight at once. Client thread only. Used when no
     * frame arrives in time, and before a new gesture starts.
     */
    public static void finishNow() {
        Gesture gesture = active;
        if (gesture == null) {
            return;
        }
        if (gesture.index < gesture.steps.size()) {
            gesture.outcome.via = "lwjgl-immediate";
        }
        while (active == gesture) {
            advance(gesture);
        }
    }

    private static void advance(Gesture gesture) {
        try {
            Step previous = gesture.step();
            gesture.index++;
            if (gesture.index >= gesture.steps.size()) {
                end(gesture);
                if (gesture.parkMillis > 0L) {
                    parkedX = previous.x;
                    parkedY = previous.y;
                    parkedUntil = System.currentTimeMillis() + gesture.parkMillis;
                }
                gesture.future.complete(gesture.outcome);
                return;
            }
            Step step = gesture.step();
            assertState(gesture);
            if (step.kind != Kind.HOVER) {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen != null) {
                    String cancelledBy = dispatch(screen, gesture, step, previous);
                    if (cancelledBy != null) {
                        if (step.kind == Kind.PRESS) {
                            gesture.outcome.cancelledBy = cancelledBy;
                        }
                        else if (step.kind == Kind.WHEEL) {
                            gesture.outcome.wheelCancelled++;
                        }
                    }
                }
            }
        }
        catch (Throwable failure) {
            // Never out onto the game thread: the gesture fails, the game carries on.
            end(gesture);
            gesture.future.completeExceptionally(failure);
        }
    }

    private static void end(Gesture gesture) {
        if (active == gesture) {
            active = null;
        }
        try {
            setInt(FIELD_EVENT_X, gesture.savedEventX);
            setInt(FIELD_EVENT_Y, gesture.savedEventY);
            setInt(FIELD_EVENT_BUTTON, gesture.savedEventButton);
            setBoolean(FIELD_EVENT_STATE, gesture.savedEventState);
            setInt(FIELD_EVENT_DWHEEL, gesture.savedEventDWheel);
            // The point and button stay until the next poll hands the real mouse back.
        }
        catch (IllegalAccessException | RuntimeException e) {
            Mcmcp.LOGGER.warn("MCMCP could not restore LWJGL's mouse event state", e);
        }
    }

    private static void assertState(Gesture gesture) {
        Step step = gesture.step();
        try {
            setInt(FIELD_X, step.x);
            setInt(FIELD_Y, step.y);
            setInt(FIELD_EVENT_X, step.x);
            setInt(FIELD_EVENT_Y, step.y);
            if (gesture.button >= 0) {
                setButton(gesture.button, step.down);
            }
        }
        catch (IllegalAccessException | RuntimeException e) {
            Mcmcp.LOGGER.warn("MCMCP could not set LWJGL's mouse state", e);
        }
    }

    private static void assertParked() {
        if (System.currentTimeMillis() >= parkedUntil) {
            // Not written back: the next poll hands the real mouse back.
            parkedUntil = 0L;
            return;
        }
        try {
            setInt(FIELD_X, parkedX);
            setInt(FIELD_Y, parkedY);
        }
        catch (IllegalAccessException | RuntimeException e) {
            parkedUntil = 0L;
        }
    }

    private static void setButton(int button, boolean down) throws IllegalAccessException {
        if (FIELD_BUTTONS == null) {
            return;
        }
        Object value = FIELD_BUTTONS.get(null);
        if (value instanceof ByteBuffer) {
            ByteBuffer buttons = (ByteBuffer) value;
            if (button >= 0 && button < buttons.capacity()) {
                buttons.put(button, (byte) (down ? 1 : 0));
            }
        }
    }

    /**
     * One event, the way Forge's {@code GuiScreen.handleInput} delivers it.
     *
     * @return "MouseInputEvent.Pre" if a handler cancelled it before the screen saw it, else null
     */
    @Nullable
    private static String dispatch(GuiScreen screen, Gesture gesture, Step step, Step previous)
        throws Exception {
        boolean buttonEvent = step.kind == Kind.PRESS || step.kind == Kind.RELEASE;
        setInt(FIELD_EVENT_BUTTON, buttonEvent ? gesture.button : -1);
        setBoolean(FIELD_EVENT_STATE, step.kind == Kind.PRESS);
        // Only a move carries movement and only a wheel event a scroll. Stale deltas left here make a
        // screen that reads them treat a click as a drag.
        setInt(FIELD_EVENT_DX, step.kind == Kind.MOVE ? step.x - previous.x : 0);
        setInt(FIELD_EVENT_DY, step.kind == Kind.MOVE ? step.y - previous.y : 0);
        setInt(FIELD_EVENT_DWHEEL, step.kind == Kind.WHEEL ? step.wheel : 0);
        setLong(FIELD_EVENT_NANOS, System.nanoTime());

        if (FIELD_MOUSE_HANDLED != null) {
            FIELD_MOUSE_HANDLED.setBoolean(screen, false);
        }
        if (MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.MouseInputEvent.Pre(screen))) {
            return "MouseInputEvent.Pre";
        }
        Method handleMouseInput = handleMouseInputMethod(screen);
        if (handleMouseInput != null) {
            handleMouseInput.invoke(screen);
        }
        boolean handled = FIELD_MOUSE_HANDLED != null && FIELD_MOUSE_HANDLED.getBoolean(screen);
        if (screen == Minecraft.getMinecraft().currentScreen && !handled) {
            MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.MouseInputEvent.Post(screen));
        }
        return null;
    }

    private static void setInt(@Nullable Field field, int value) throws IllegalAccessException {
        if (field != null) {
            field.setInt(null, value);
        }
    }

    private static void setBoolean(@Nullable Field field, boolean value) throws IllegalAccessException {
        if (field != null) {
            field.setBoolean(null, value);
        }
    }

    private static void setLong(@Nullable Field field, long value) throws IllegalAccessException {
        if (field != null) {
            field.setLong(null, value);
        }
    }
}
