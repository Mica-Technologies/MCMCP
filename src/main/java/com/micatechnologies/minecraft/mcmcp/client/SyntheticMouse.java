package com.micatechnologies.minecraft.mcmcp.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.annotation.Nullable;

import org.lwjgl.input.Mouse;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Delivers a click to a {@link GuiScreen} the way the game itself does: by setting LWJGL's mouse
 * state and calling the screen's {@code handleMouseInput()}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code GuiScreen.mouseClicked} is not how the game delivers clicks. It is one step *inside* how
 * the game delivers clicks. The real path is {@code GuiScreen.handleInput()}, which pumps LWJGL's
 * event queue and calls {@code handleMouseInput()} once per event; vanilla's {@code handleMouseInput}
 * then reads {@code Mouse.getEventX/getEventY/getEventButton/getEventButtonState} and dispatches to
 * {@code mouseClicked} or {@code mouseReleased}.
 *
 * <p>A screen is free to override {@code handleMouseInput()} and never call {@code mouseClicked} at
 * all. MalisisCore's {@code MalisisGui} does exactly that — it does its own hit testing against
 * {@code Mouse.getX()/getY()} and dispatches to its own component tree — so invoking
 * {@code mouseClicked} on it reaches nothing whatsoever. Every MalisisDoors, MalisisSwitches and
 * MalisisCore screen was silently unclickable, and the symptom was indistinguishable from a broken
 * mod: the click returned success, the screen did not change, and nothing was logged. That cost a
 * false bug report against a perfectly good tab widget before the cause was found.
 *
 * <p>This is not a Malisis quirk. Any screen that overrides {@code handleMouseInput} is in the same
 * position, and overriding it is the documented way to handle scroll wheels.
 *
 * <h2>What it sets, and why both coordinate pairs</h2>
 *
 * <p>Vanilla's {@code handleMouseInput} reads {@code Mouse.getEventX()/getEventY()}. MalisisGui reads
 * {@code Mouse.getX()/getY()}. Neither is wrong, so both pairs are set to the same point — setting
 * only one works on exactly half of the screens in the wild, and which half depends on the mod.
 *
 * <p>LWJGL's mouse origin is bottom-left, while GUI space and screenshots are top-left. The flip is
 * applied here, in {@link #toLwjglY}, and is the single most likely thing to be wrong if clicks land
 * a consistent distance from the wrong edge.
 *
 * <h2>Press and release are both required</h2>
 *
 * <p>A real click is two events. Many screens act on the release, or require a press and release on
 * the same component (MalisisGui fires {@code onClick} only when the released component is the one
 * that took focus on press). Sending only the press leaves buttons visually stuck down and actions
 * unfired. Both events are delivered inside one game-thread task, so no frame renders between them
 * and the two cannot disagree about what is under the cursor.
 *
 * <h2>Failure is not fatal</h2>
 *
 * <p>Every field is looked up once and cached. If LWJGL is not the expected implementation — a
 * shaded build, or LWJGL3ify's compatibility shim — {@link #isAvailable()} returns false and the
 * caller falls back to {@code mouseClicked}, which is still correct for the majority of screens.
 * Nothing here throws its way out to the game thread.
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
     * <p>Used only to explain in the tool result which path a click took. The synthetic path is
     * correct for both kinds of screen, so this does not gate anything.
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

    /** GUI-space X to LWJGL display-pixel X. */
    private static int toLwjglX(Minecraft mc, int guiX, int scaledWidth) {
        return guiX * mc.displayWidth / Math.max(1, scaledWidth);
    }

    /**
     * GUI-space Y to LWJGL display-pixel Y, flipped to a bottom-left origin.
     *
     * <p>Inverts vanilla's {@code height - Mouse.getEventY() * height / displayHeight - 1}, so a point
     * converted here and read back by a screen lands on the row it was asked for.
     */
    private static int toLwjglY(Minecraft mc, int guiY, int scaledHeight) {
        int flipped = Math.max(0, scaledHeight - 1 - guiY);
        return flipped * mc.displayHeight / Math.max(1, scaledHeight);
    }

    /**
     * Delivers a full press-and-release at a GUI-space point.
     *
     * <p>Must be called on the client thread: it drives a screen's input handler, which touches the
     * whole GUI tree.
     *
     * @return false if this LWJGL build cannot be driven, or the screen has no reachable
     *         {@code handleMouseInput}; the caller should fall back to {@code mouseClicked}.
     */
    public static boolean click(GuiScreen screen, int guiX, int guiY, int button,
                                int scaledWidth, int scaledHeight) throws Exception {
        if (!isAvailable()) {
            return false;
        }
        Method handleMouseInput = handleMouseInputMethod(screen);
        if (handleMouseInput == null) {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        int lwjglX = toLwjglX(mc, guiX, scaledWidth);
        int lwjglY = toLwjglY(mc, guiY, scaledHeight);

        // The real cursor is wherever the player left it. Put it back afterwards so the next real
        // frame does not treat the synthetic position as the mouse's last known location.
        int savedX = Mouse.getX();
        int savedY = Mouse.getY();
        int savedEventX = Mouse.getEventX();
        int savedEventY = Mouse.getEventY();
        int savedEventButton = Mouse.getEventButton();
        boolean savedEventState = Mouse.getEventButtonState();
        int savedEventDWheel = Mouse.getEventDWheel();

        try {
            position(lwjglX, lwjglY);
            dispatch(screen, handleMouseInput, button, true);
            // Position is set again before the release: a screen's handler is free to move the
            // cursor, and a release somewhere else is a drag, not a click.
            position(lwjglX, lwjglY);
            dispatch(screen, handleMouseInput, button, false);
        }
        finally {
            position(savedX, savedY);
            setInt(FIELD_EVENT_X, savedEventX);
            setInt(FIELD_EVENT_Y, savedEventY);
            setInt(FIELD_EVENT_BUTTON, savedEventButton);
            setBoolean(FIELD_EVENT_STATE, savedEventState);
            setInt(FIELD_EVENT_DWHEEL, savedEventDWheel);
        }
        return true;
    }

    /** Sets both coordinate pairs, because screens disagree about which one they read. */
    private static void position(int lwjglX, int lwjglY) throws IllegalAccessException {
        setInt(FIELD_X, lwjglX);
        setInt(FIELD_Y, lwjglY);
        setInt(FIELD_EVENT_X, lwjglX);
        setInt(FIELD_EVENT_Y, lwjglY);
    }

    private static void dispatch(GuiScreen screen, Method handleMouseInput, int button,
                                 boolean pressed) throws Exception {
        setInt(FIELD_EVENT_BUTTON, button);
        setBoolean(FIELD_EVENT_STATE, pressed);
        // A click carries no movement and no scroll. Leaving stale deltas here makes a screen that
        // reads them treat the click as a drag.
        setInt(FIELD_EVENT_DX, 0);
        setInt(FIELD_EVENT_DY, 0);
        setInt(FIELD_EVENT_DWHEEL, 0);
        setLong(FIELD_EVENT_NANOS, System.nanoTime());
        handleMouseInput.invoke(screen);
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
