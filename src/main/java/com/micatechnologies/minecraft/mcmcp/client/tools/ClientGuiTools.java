package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.client.SyntheticMouse;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.GuiModList;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Reading and driving whatever screen is currently open: buttons, text fields, and the HUD.
 *
 * <h2>Why this exists</h2>
 *
 * {@link ClientInputTools} can drive the player, but only once a player exists. Everything before
 * that — the main menu, the world list, the mod-config screens — and a great deal after it — any mod
 * whose real interface is a GUI rather than a block face — was unreachable. A model could observe
 * that a screen was open via {@code client_gui_state} and then had no way to act on it, which is a
 * worse position than not knowing: it could see the wall but not the door.
 *
 * <h2>Clicks go through the screen's own handler</h2>
 *
 * {@code client_gui_click} does not call {@code actionPerformed} on the button directly. It delivers
 * a click at the button's centre through {@link SyntheticMouse}, which sets LWJGL's mouse state and
 * calls the screen's {@code handleMouseInput()} - the same route a real click takes. That distinction
 * matters for the same reason it matters in {@link ClientInputTools}: screens routinely override the
 * mouse path to do their own hit testing, to reject clicks while something is loading, or to treat a
 * click on a list differently from a click on a button. Calling {@code actionPerformed} would bypass
 * all of it and fire actions the real UI would have refused.
 *
 * <p>Calling {@code mouseClicked} directly is not enough either, and that was this class's original
 * mistake. {@code mouseClicked} is one branch *inside* the real path; a screen that overrides
 * {@code handleMouseInput} - MalisisCore's do - may never reach it, so the click landed nowhere while
 * still reporting success. {@code mouseClicked} remains as a fallback for builds where LWJGL's state
 * cannot be driven, and every click result names the path it took in its {@code via} field.
 *
 * <h2>When pressing the button directly is right</h2>
 *
 * A click at a point assumes the button's stored {@code x}/{@code y} are where the screen draws it.
 * A screen that scales its own drawing breaks that: CSM's fire alarm panel draws at 0.9 scale about
 * the centre and converts the mouse back to its design space in {@code mouseClicked}, so a click at
 * the stored centre was converted to somewhere else and hit nothing (issue #43). Nothing reports a
 * miss, so there is no signal to fall back on automatically.
 *
 * <p>{@code mode: "press"} is the explicit way round it: it runs the hit branch of vanilla's
 * {@code mouseClicked} against the one button, in the button's own coordinates — its
 * {@code mousePressed}, Forge's {@code ActionPerformedEvent}s, the press sound and the screen's
 * {@code actionPerformed}. What it skips is everything the screen does before that branch: its
 * {@code handleMouseInput} and its own {@code mouseClicked}, which is where a screen would refuse a
 * click while busy. That is why it is not the default.
 *
 * <h2>Reflection, and why it is named twice</h2>
 *
 * {@code mouseClicked}, {@code keyTyped} and the {@code buttonList} field are all {@code protected}
 * on {@link GuiScreen}, so there is no public path to any of them. These are the only reflective
 * accesses in MCMCP. Each is looked up by MCP name <em>and</em> SRG name ({@code func_73864_a},
 * {@code func_73869_a}, {@code field_146292_n}), because a development client runs deobfuscated while
 * a released jar is reobfuscated — a lookup by one name alone works in exactly one of the two
 * environments, and which one it fails in is whichever you did not test.
 *
 * <p>Text fields are the exception. Vanilla's {@link GuiTextField} is recognised by <em>type</em> and
 * called directly, which needs no mapping. A mod's own field is recognised by the shape of its
 * {@code getText}/{@code setText} pair; those names are the mod's, not Minecraft's, so reobfuscation
 * never renames them. Matching vanilla by that shape too was the trap: it worked in a dev client and
 * found nothing in a released one.
 */
@SideOnly(Side.CLIENT)
public final class ClientGuiTools {

    /**
     * LWJGL key code for Escape. Passing it to a screen's {@code keyTyped} is what the real keyboard
     * handler does, so screens that override the key — and several do, to refuse to close — keep
     * their behaviour instead of being torn down underneath themselves.
     */
    private static final int KEY_ESCAPE = 1;

    /** LWJGL key code for Return, for screens that submit a text field on Enter. */
    private static final int KEY_RETURN = 28;

    /**
     * LWJGL key code for End: the keystroke {@code client_gui_text} sends after setting a field. A
     * focused vanilla field accepts it, so the screen's {@code keyTyped} goes on to its "the text
     * changed" branch; it moves the caret to where the text ends, which is where a person would have
     * left it anyway. A character 0 would have been refused by the field, and the branch never run.
     */
    private static final int KEY_END = 207;

    private ClientGuiTools() {
    }

    public static void register() {
        registerGuiWidgets();
        registerGuiClick();
        registerGuiClickAt();
        registerGuiScroll();
        registerGuiDrag();
        registerGuiKey();
        registerGuiText();
        registerGuiClose();
        registerGuiOpen();
        registerView();
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    /**
     * Finds a method by any of the given names.
     *
     * <p>Returns null rather than throwing: a missing method means this Minecraft build names it
     * something this class does not know, and the calling tool turns that into an error message that
     * says so. A hard failure at registration time would take the whole endpoint down instead.
     */
    @Nullable
    private static Method findMethod(Class<?> owner, Class<?>[] parameterTypes, String... names) {
        for (String name : names) {
            try {
                Method method = owner.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            }
            catch (NoSuchMethodException ignored) {
                // Try the next name.
            }
        }
        return null;
    }

    /**
     * The screen's button list, which is {@code protected} on {@link GuiScreen}.
     *
     * <p>Looked up by MCP and SRG name for the same reason the methods are: a released jar names it
     * {@code field_146292_n}. Returned as the live list rather than a copy — callers only read it,
     * and a copy would go stale the moment a click rebuilt the screen.
     */
    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttonsOf(GuiScreen screen) {
        for (String name : new String[]{"buttonList", "field_146292_n"}) {
            try {
                Field field = GuiScreen.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(screen);
                if (value instanceof List) {
                    return (List<GuiButton>) value;
                }
            }
            catch (NoSuchFieldException ignored) {
                // Try the next name.
            }
            catch (IllegalAccessException blocked) {
                throw new IllegalStateException("Could not read GuiScreen.buttonList: "
                    + blocked.getMessage());
            }
        }
        throw new IllegalStateException("Could not locate GuiScreen.buttonList on this Minecraft "
            + "build; GUI inspection is unavailable.");
    }

    /** The screen's buttons, for client code outside this class. Client thread only. */
    public static List<GuiButton> buttonsOn(GuiScreen screen) {
        return buttonsOf(screen);
    }

    /**
     * Finds a method declared anywhere on a screen's own class hierarchy.
     *
     * <p>Unlike {@link #findMethod}, which looks on {@link GuiScreen} itself, this walks the concrete
     * screen's classes. It is how the modern-style {@code charTyped}/{@code keyPressed} handlers get
     * found on screens that declare them, without assuming any particular framework is present.
     */
    @Nullable
    private static Method screenMethod(GuiScreen screen, String name, Class<?>... parameterTypes) {
        Class<?> type = screen.getClass();
        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            }
            catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    @Nullable
    private static Method mouseClickedMethod() {
        return findMethod(GuiScreen.class, new Class<?>[]{int.class, int.class, int.class},
            "mouseClicked", "func_73864_a");
    }

    /**
     * A click started on the client thread and still playing out. Holds no Minecraft object, so it
     * can cross back to the worker that waits on it.
     */
    private static final class PendingClick {
        /** Fields for the result, merged over the description of the screen open afterwards. */
        final JsonObject json = new JsonObject();
        @Nullable
        CompletableFuture<SyntheticMouse.Outcome> future;
        String screenBefore;
        /** How long to wait for the screen to change: the caller's waitForChange, or -1 for the default. */
        long waitMillis = -1L;
        /** How long the gesture may take to play out over frames before it is finished at once. */
        long framesTimeoutMillis = CLICK_FRAMES_TIMEOUT_MILLIS;
        /** Whether an unchanged screen is worth a note: so for a click, not for a scroll or hover. */
        boolean expectChange = true;
    }

    /**
     * How long a click waits for the client to draw its three frames before it is finished in one
     * task instead. A healthy client needs about 50 ms; a throttled one at 10 fps about 300.
     */
    private static final long CLICK_FRAMES_TIMEOUT_MILLIS = 2000L;

    /**
     * Starts a click at an LWJGL point. Client thread only.
     *
     * <p>Prefers {@link SyntheticMouse}, which plays the click out over real frames through Forge's
     * input route: {@code MouseInputEvent.Pre}, the screen's {@code handleMouseInput()}, then
     * {@code Post}, with LWJGL's button held for one frame between. That matters because
     * {@code mouseClicked} is only one branch *inside* the real route: MalisisCore's screens never
     * reach it, mods that skin vanilla screens take clicks in the events, and scrolling lists poll the
     * button while drawing.
     *
     * <p>Falls back to {@code mouseClicked} when LWJGL cannot be driven - a shaded build, or
     * LWJGL3ify's shim - because that is still correct for most screens. The path taken goes in the
     * result: models retry a click that did nothing, and knowing which path ran is the difference
     * between "the button is broken" and "this screen needs the other path".
     */
    private static void startClick(PendingClick pending, GuiScreen screen, int lwjglX, int lwjglY,
                                   int button) throws Exception {
        pending.screenBefore = screen.getClass().getSimpleName();
        pending.future = SyntheticMouse.begin(lwjglX, lwjglY, button);
        if (pending.future != null) {
            return;
        }
        Method mouseClicked = mouseClickedMethod();
        if (mouseClicked == null) {
            throw new IllegalStateException("Could not drive LWJGL's mouse state, and could not locate "
                + "GuiScreen.mouseClicked on this Minecraft build; GUI clicking is unavailable.");
        }
        int[] gui = SyntheticMouse.toGui(screen, lwjglX, lwjglY);
        mouseClicked.invoke(screen, gui[0], gui[1], button);
        pending.json.addProperty("via", "mouseClicked");
    }

    /**
     * Waits for a started click to finish, then describes the screen open afterwards. Worker thread:
     * the click needs the client thread to draw frames meanwhile.
     */
    private static JsonObject finishClick(ToolContext context, final PendingClick pending)
        throws Exception {
        if (pending.future != null) {
            SyntheticMouse.Outcome outcome;
            try {
                outcome = pending.future.get(pending.framesTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException noFrames) {
                // No frame drawn in time: finish what is left in one task, which is still right for
                // most screens, rather than fail a click that may already be half delivered.
                context.onGameThread(new Callable<Void>() {
                    @Override
                    public Void call() {
                        SyntheticMouse.finishNow();
                        return null;
                    }
                });
                outcome = pending.future.get(pending.framesTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                throw cause instanceof Exception ? (Exception) cause : failed;
            }
            pending.json.addProperty("via", outcome.via);
            pending.json.addProperty("frames", outcome.frames);
            if (outcome.wheelCancelled > 0) {
                pending.json.addProperty("wheelCancelled", outcome.wheelCancelled);
            }
            if (outcome.cancelledBy != null) {
                pending.json.addProperty("cancelledBy", outcome.cancelledBy);
            }
        }
        Callable<JsonObject> describe = new Callable<JsonObject>() {
            @Override
            public JsonObject call() {
                GuiScreen after = Minecraft.getMinecraft().currentScreen;
                JsonObject json = describeScreen(after);
                json.addProperty("screenBefore", pending.screenBefore);
                json.addProperty("screenChanged", after == null
                    || !pending.screenBefore.equals(after.getClass().getSimpleName()));
                return json;
            }
        };
        JsonObject result = context.onGameThread(describe);
        // Some buttons only send a packet, and the screen changes when the server answers (#172):
        // Respawn closed GuiGameOver a tick or more after the click, and the reply had already said
        // nothing changed. Waited for here, ending the moment the screen changes.
        long waitMillis = pending.waitMillis >= 0 ? pending.waitMillis
            : SERVER_ANSWERED_SCREENS.contains(pending.screenBefore) ? SERVER_ANSWER_WAIT_MILLIS : 0L;
        long started = System.currentTimeMillis();
        while (!result.get("screenChanged").getAsBoolean()
            && System.currentTimeMillis() - started < waitMillis) {
            context.getCancellation().throwIfCancelled();
            Thread.sleep(25L);
            result = context.onGameThread(describe);
        }
        if (waitMillis > 0) {
            result.addProperty("waitedMs", System.currentTimeMillis() - started);
        }
        if (pending.expectChange && !result.get("screenChanged").getAsBoolean()) {
            result.addProperty("note", "screenChanged is as of " + (waitMillis > 0 ? waitMillis
                + " ms" : "the moment") + " after the click. A button that sends a packet changes "
                + "the screen only when the server answers; check client_gui_state, or pass "
                + "waitForChange, before treating the click as failed.");
        }
        for (Map.Entry<String, JsonElement> entry : pending.json.entrySet()) {
            result.add(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** Screens whose buttons act through the server, so a click is waited on by default. */
    private static final Set<String> SERVER_ANSWERED_SCREENS = new HashSet<>(Arrays.asList(
        "GuiGameOver", "GuiDownloadTerrain", "GuiConnecting", "GuiScreenWorking"));

    /** How long a click on one of those waits for the screen to change, when not told otherwise. */
    private static final long SERVER_ANSWER_WAIT_MILLIS = 1000L;

    /** Most a caller may ask a click to wait for its screen to change. */
    private static final int MAX_WAIT_FOR_CHANGE_MILLIS = 5000;

    private static final String WAIT_FOR_CHANGE_DESCRIPTION = "Wait up to this many ms for the open "
        + "screen to change before replying, ending as soon as it does. For buttons that only send a "
        + "packet and change the screen when the server answers. Default 1000 on the death, "
        + "connecting and terrain screens, 0 elsewhere.";

    /** A sentence for the reply when a mod took the press before the screen saw it, else "". */
    private static String cancelledNote(JsonObject result) {
        return result.has("cancelledBy")
            ? " A mod cancelled the press in " + result.get("cancelledBy").getAsString()
                + " and handled it itself, so the screen's own handler never saw it."
            : "";
    }

    /** Adds the open screen's own size, and the game's, so a caller can see when they differ. */
    private static void addScreenSize(JsonObject json, Minecraft mc, GuiScreen screen) {
        ScaledResolution resolution = new ScaledResolution(mc);
        json.addProperty("screenWidth", screen.width);
        json.addProperty("screenHeight", screen.height);
        json.addProperty("scaledWidth", resolution.getScaledWidth());
        json.addProperty("scaledHeight", resolution.getScaledHeight());
        if (screen.width != resolution.getScaledWidth() || screen.height != resolution.getScaledHeight()) {
            // A screen laid out at its own size (LDIC's scaled inventories, #163). Its own size is
            // what it reads the mouse with, so it is what positions here are in.
            json.addProperty("screenScaled", true);
        }
    }

    /**
     * Presses one button the way vanilla's {@code mouseClicked} does once its hit test has found it,
     * with the hit test done in the button's own coordinates. Client thread only.
     *
     * <p>Public because {@code ClientStartupQuery} answers FML's mod-mismatch prompt with it: that
     * screen's buttons are FML's own and drawn where they are stored, but by then the client thread
     * is inside the world launch, and a direct press is the least that can go wrong.
     *
     * @return false when the button's own {@code mousePressed} refused, or a Forge handler cancelled
     *         the press; nothing was run in either case
     */
    public static boolean pressButton(GuiScreen screen, GuiButton button) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        int centreX = button.x + button.width / 2;
        int centreY = button.y + button.height / 2;
        // Called rather than skipped: sliders and toggles act in mousePressed itself, and it is the
        // button's own enabled-and-visible check.
        if (!button.mousePressed(mc, centreX, centreY)) {
            return false;
        }
        Method actionPerformed = findMethod(GuiScreen.class, new Class<?>[]{GuiButton.class},
            "actionPerformed", "func_146284_a");
        if (actionPerformed == null) {
            throw new IllegalStateException("Could not locate GuiScreen.actionPerformed on this "
                + "Minecraft build; pressing buttons directly is unavailable.");
        }
        List<GuiButton> buttons = buttonsOf(screen);
        GuiScreenEvent.ActionPerformedEvent.Pre pre =
            new GuiScreenEvent.ActionPerformedEvent.Pre(screen, button, buttons);
        if (MinecraftForge.EVENT_BUS.post(pre)) {
            return false;
        }
        GuiButton pressed = pre.getButton();
        pressed.playPressSound(mc.getSoundHandler());
        actionPerformed.invoke(screen, pressed);
        if (screen.equals(mc.currentScreen)) {
            MinecraftForge.EVENT_BUS.post(
                new GuiScreenEvent.ActionPerformedEvent.Post(screen, pressed, buttons));
        }
        pressed.mouseReleased(centreX, centreY);
        return true;
    }

    /**
     * Whether the screen handles clicks in its own {@code mouseClicked}. Such a screen may convert
     * the mouse before testing its buttons, so their stored positions are not necessarily where a
     * click lands.
     */
    private static boolean overridesMouseClicked(GuiScreen screen) {
        for (Class<?> type = screen.getClass(); type != null && type != GuiScreen.class;
             type = type.getSuperclass()) {
            for (String name : new String[]{"mouseClicked", "func_73864_a"}) {
                try {
                    type.getDeclaredMethod(name, int.class, int.class, int.class);
                    return true;
                }
                catch (NoSuchMethodException ignored) {
                    // Try the next name, then the superclass.
                }
            }
        }
        return false;
    }

    @Nullable
    private static Method keyTypedMethod() {
        return findMethod(GuiScreen.class, new Class<?>[]{char.class, int.class},
            "keyTyped", "func_73869_a");
    }

    /** How deep the widget walk goes before giving up. Deeper than any real screen nests. */
    private static final int MAX_WALK_DEPTH = 8;

    /** Objects the walk may visit, as a backstop against a graph that turns out to be enormous. */
    private static final int MAX_WALK_VISITS = 4000;

    /**
     * A text-entry widget found on a screen, together with the methods to read and write it.
     *
     * <p>Deliberately not typed as {@link GuiTextField}. See {@link #textWidgets}.
     */
    private static final class TextWidget {

        final Object target;
        /**
         * Set for vanilla's own field, which is called directly rather than by reflection. Its
         * methods are {@code func_146179_b} and so on in a released jar, so a lookup of
         * {@code "getText"} finds nothing there — only a dev client, where names are MCP, would
         * ever see a vanilla field. A direct call is renamed by reobfuscation along with the rest.
         */
        @Nullable
        final GuiTextField vanilla;
        @Nullable
        final Method getter;
        @Nullable
        final Method setter;
        @Nullable
        final Method focusGetter;
        @Nullable
        final Method focusSetter;
        final String type;

        TextWidget(GuiTextField vanilla) {
            this(vanilla, vanilla, null, null, null, null);
        }

        TextWidget(Object target, Method getter, Method setter, @Nullable Method focusGetter,
                   @Nullable Method focusSetter) {
            this(target, null, getter, setter, focusGetter, focusSetter);
        }

        private TextWidget(Object target, @Nullable GuiTextField vanilla, @Nullable Method getter,
                           @Nullable Method setter, @Nullable Method focusGetter,
                           @Nullable Method focusSetter) {
            this.target = target;
            this.vanilla = vanilla;
            this.getter = getter;
            this.setter = setter;
            this.focusGetter = focusGetter;
            this.focusSetter = focusSetter;
            this.type = target.getClass().getSimpleName();
        }

        String read() throws Exception {
            if (vanilla != null) {
                return vanilla.getText();
            }
            Object value = getter.invoke(target);
            return value == null ? "" : value.toString();
        }

        void write(String value) throws Exception {
            if (vanilla != null) {
                vanilla.setText(value);
                return;
            }
            setter.invoke(target, value);
        }

        @Nullable
        Boolean focused() {
            if (vanilla != null) {
                return vanilla.isFocused();
            }
            if (focusGetter == null) {
                return null;
            }
            try {
                Object value = focusGetter.invoke(target);
                return value instanceof Boolean ? (Boolean) value : null;
            }
            catch (Exception ignored) {
                return null;
            }
        }

        /** Best effort; a widget with no {@code setFocused(boolean)} is left as it is. */
        void focus(boolean focused) {
            if (vanilla != null) {
                vanilla.setFocused(focused);
                return;
            }
            if (focusSetter == null) {
                return;
            }
            try {
                focusSetter.invoke(target, focused);
            }
            catch (Exception ignored) {
                // Not every framework's setter is safe to call from outside; the nudge copes.
            }
        }
    }

    /**
     * Collects every text-entry widget reachable from a screen, by <em>shape</em> rather than type.
     *
     * <p>Anything exposing {@code String getText()} and {@code setText(String)} counts. That pair is
     * the near-universal convention — vanilla's {@link GuiTextField} has it, and so does every mod
     * widget framework the author has looked at, including the Core Lib one this was written
     * against. Matching on the convention rather than on {@code GuiTextField} is what makes a mod's
     * own text box reachable at all, and it needs no knowledge of the framework.
     *
     * <p>The walk is necessary because widgets are rarely direct fields of the screen. Core Lib's
     * {@code WidgetScreen} holds one root widget which holds its children in a list, so a search
     * limited to declared fields finds nothing on a screen full of text boxes.
     *
     * <h2>What it refuses to walk into</h2>
     *
     * Recursion skips {@code java.*} and {@code net.minecraft.*} objects. That is not tidiness: the
     * screen holds a {@code Minecraft} reference, and descending into it reaches the world, every
     * loaded entity and the whole render stack — a walk that would take far longer than the game
     * thread can spare and might never terminate. Values are shape-checked <em>before</em> that
     * filter applies, so a vanilla {@link GuiTextField} held directly by a screen is still found.
     */
    private static List<TextWidget> textWidgets(GuiScreen screen) {
        List<TextWidget> found = new ArrayList<>();
        Map<Object, Boolean> visited = new IdentityHashMap<>();
        walk(screen, found, visited, 0, true);
        return found;
    }

    private static void walk(@Nullable Object node, List<TextWidget> found,
        Map<Object, Boolean> visited, int depth, boolean isRoot) {

        if (node == null || depth > MAX_WALK_DEPTH || visited.size() > MAX_WALK_VISITS) {
            return;
        }
        if (visited.put(node, Boolean.TRUE) != null) {
            return;
        }

        if (!isRoot) {
            if (node instanceof GuiTextField) {
                found.add(new TextWidget((GuiTextField) node));
                return;
            }
            Method getter = findAccessible(node.getClass(), "getText");
            Method setter = findAccessible(node.getClass(), "setText", String.class);
            if (getter != null && setter != null && getter.getReturnType() == String.class) {
                found.add(new TextWidget(node, getter, setter,
                    findAccessible(node.getClass(), "isFocused"),
                    findAccessible(node.getClass(), "setFocused", boolean.class)));
                // A text widget is a leaf for this purpose; its internals hold nothing else wanted.
                return;
            }
        }

        if (node instanceof Iterable) {
            for (Object element : (Iterable<?>) node) {
                walk(element, found, visited, depth + 1, false);
            }
            return;
        }
        if (node.getClass().isArray() && !node.getClass().getComponentType().isPrimitive()) {
            int length = java.lang.reflect.Array.getLength(node);
            for (int i = 0; i < length; i++) {
                walk(java.lang.reflect.Array.get(node, i), found, visited, depth + 1, false);
            }
            return;
        }
        if (!isRoot && !mayDescendInto(node.getClass())) {
            return;
        }

        Class<?> type = node.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    walk(field.get(node), found, visited, depth + 1, false);
                }
                catch (Exception ignored) {
                    // A field we cannot read is a field we cannot offer; skip it.
                }
            }
            type = type.getSuperclass();
        }
    }

    private static boolean mayDescendInto(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) {
            return false;
        }
        String name = type.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.")
            && !name.startsWith("sun.") && !name.startsWith("jdk.")
            && !name.startsWith("net.minecraft.") && !name.startsWith("net.minecraftforge.");
    }

    @Nullable
    private static Method findAccessible(Class<?> owner, String name, Class<?>... parameterTypes) {
        Class<?> type = owner;
        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            }
            catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /** The screen currently open, or null. Must be called on the client thread. */
    @Nullable
    private static GuiScreen currentScreen() {
        return Minecraft.getMinecraft().currentScreen;
    }

    private static JsonObject describeScreen(@Nullable GuiScreen screen) {
        JsonObject json = new JsonObject();
        json.addProperty("guiOpen", screen != null);
        json.addProperty("screenName", screen == null ? "none" : screen.getClass().getSimpleName());
        json.addProperty("screenClass", screen == null ? null : screen.getClass().getName());
        return json;
    }

    // ------------------------------------------------------------------
    // Widget listing
    // ------------------------------------------------------------------

    /**
     * What can be clicked and typed into on the current screen.
     *
     * <p>The counterpart to {@code client_gui_state}, which says <em>which</em> screen is open but
     * nothing about what is on it. Clicking blind by index is fragile against a mod's custom screen,
     * where button order is whatever the author happened to add them in; listing labels first means
     * a model can pick "Create New World" by name and be right regardless of position.
     */
    private static void registerGuiWidgets() {
        McpRegistry.registerTool(McpTool.named("client_gui_widgets")
            .title("List GUI widgets")
            .description("List the buttons and text fields on the screen that is currently open, with "
                + "their labels, indices and enabled state.\n\n"
                + "Call this before client_gui_click or client_gui_text: those address widgets by "
                + "label or index, and this is where both come from. Works at the main menu and on "
                + "any mod's screen, not only in a world.\n\n"
                + "Buttons are listed in the screen's own order. Text fields are found by type, so "
                + "they are listed even when a mod gives them names this tool cannot know.\n\n"
                + "An empty list does NOT mean an empty screen. Only vanilla widgets are visible "
                + "here, and many mods build their interfaces out of their own classes — a screen "
                + "full of controls can report zero buttons. When that happens, screenshot the frame "
                + "and drive it with client_gui_click_at and client_gui_key, which work on any "
                + "screen. The reply includes both coordinate spaces for converting between a "
                + "screenshot's pixels and the scaled positions reported here.\n\n"
                + "Button x/y are the button's own stored position. A screen that scales its own "
                + "drawing converts the mouse in its mouseClicked ('overridesMouseClicked' true), so "
                + "it may draw a button somewhere else; if a click does nothing there, use "
                + "client_gui_click with mode 'press'.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen screen = currentScreen();
                        JsonObject json = describeScreen(screen);

                        // Both coordinate spaces, so a caller can convert between a screenshot's
                        // pixels and the scaled space widget positions are reported in without
                        // having to know Minecraft's scaling rules.
                        ScaledResolution resolution = new ScaledResolution(mc);
                        json.addProperty("scaledWidth", resolution.getScaledWidth());
                        json.addProperty("scaledHeight", resolution.getScaledHeight());
                        json.addProperty("scaleFactor", resolution.getScaleFactor());
                        json.addProperty("displayWidth", mc.displayWidth);
                        json.addProperty("displayHeight", mc.displayHeight);

                        if (screen == null) {
                            json.add("buttons", new JsonArray());
                            json.add("textFields", new JsonArray());
                            return json;
                        }
                        json.addProperty("overridesMouseClicked", overridesMouseClicked(screen));
                        // Widget positions are in the screen's own coordinates, which differ from
                        // scaledWidth on a screen laid out at its own size (#163).
                        json.addProperty("screenWidth", screen.width);
                        json.addProperty("screenHeight", screen.height);
                        if (screen.width != json.get("scaledWidth").getAsInt()
                            || screen.height != json.get("scaledHeight").getAsInt()) {
                            json.addProperty("screenScaled", true);
                        }

                        JsonArray buttons = new JsonArray();
                        List<GuiButton> present = buttonsOf(screen);
                        for (int index = 0; index < present.size(); index++) {
                            GuiButton button = present.get(index);
                            JsonObject entry = new JsonObject();
                            entry.addProperty("index", index);
                            entry.addProperty("id", button.id);
                            entry.addProperty("label", button.displayString);
                            entry.addProperty("enabled", button.enabled);
                            entry.addProperty("visible", button.visible);
                            entry.addProperty("x", button.x);
                            entry.addProperty("y", button.y);
                            entry.addProperty("width", button.width);
                            entry.addProperty("height", button.height);
                            buttons.add(entry);
                        }
                        json.add("buttons", buttons);

                        JsonArray fields = new JsonArray();
                        List<TextWidget> found = textWidgets(screen);
                        for (int index = 0; index < found.size(); index++) {
                            TextWidget field = found.get(index);
                            JsonObject entry = new JsonObject();
                            entry.addProperty("index", index);
                            entry.addProperty("text", field.read());
                            entry.addProperty("widgetType", field.type);
                            Boolean focused = field.focused();
                            if (focused != null) {
                                entry.addProperty("focused", focused);
                            }
                            fields.add(entry);
                        }
                        json.add("textFields", fields);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Clicking
    // ------------------------------------------------------------------

    private static void registerGuiClick() {
        McpRegistry.registerTool(McpTool.named("client_gui_click")
            .title("Click a GUI button")
            .description("Click a button on the currently open screen, by label or by index from "
                + "client_gui_widgets.\n\n"
                + "By default ('click') this is a mouse click at the button's stored centre, through "
                + "the screen's own input handling, so screens that do custom hit testing or refuse "
                + "clicks while busy behave exactly as they would for a person clicking.\n\n"
                + "A screen that scales its own drawing converts the mouse before hit testing, so a "
                + "click at the stored centre can land on nothing and change nothing. Use mode "
                + "'press' there: it presses this one button directly (its mousePressed, then the "
                + "screen's actionPerformed), the way the screen would on a hit, skipping only the "
                + "screen's own click handling.\n\n"
                + "Screens usually change as a result. The reply reports which screen is open "
                + "afterwards, so compare it against the one you started on to see whether the click "
                + "did what you expected.")
            .schema(JsonSchema.object()
                .string("label", "Button label to click, matched case-insensitively. Either an exact "
                    + "match or, failing that, a unique substring match. Prefer this over index.")
                .integer("index", "Button index from client_gui_widgets. Used when label is omitted.")
                .enumeration("mode", "'click' (default) clicks the mouse at the button's stored "
                    + "centre. 'press' presses the button directly, for screens that draw scaled "
                    + "or moved.", "click", "press")
                .integer("waitForChange", WAIT_FOR_CHANGE_DESCRIPTION, 0, MAX_WAIT_FOR_CHANGE_MILLIS)
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }

                final String label = context.getString("label", null);
                final int index = context.getInt("index", -1);
                final String mode = context.getString("mode", "click");
                if (!"click".equals(mode) && !"press".equals(mode)) {
                    return ToolResult.error("mode is 'click' or 'press'; got '" + mode + "'.");
                }
                final boolean press = "press".equals(mode);
                if (label == null && index < 0) {
                    return ToolResult.error("Pass either 'label' or 'index' to say which button to "
                        + "click. Call client_gui_widgets to see what is available.");
                }

                final PendingClick pending = context.onGameThread(new Callable<PendingClick>() {
                    @Override
                    public PendingClick call() throws Exception {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen screen = mc.currentScreen;
                        if (screen == null) {
                            throw new IllegalStateException("No screen is open, so there is nothing to "
                                + "click. Check client_gui_state first.");
                        }

                        GuiButton target = label != null
                            ? findByLabel(screen, label)
                            : findByIndex(screen, index);

                        if (!target.visible) {
                            throw new IllegalStateException("Button '" + target.displayString
                                + "' is not visible, so it cannot be clicked.");
                        }
                        if (!target.enabled) {
                            throw new IllegalStateException("Button '" + target.displayString
                                + "' is disabled, so clicking it would do nothing.");
                        }

                        // The centre of the button, in the scaled coordinate space that both
                        // buttonList positions and mouseClicked use. Clicking the corner would land
                        // outside any button whose hit box is inset from its drawn bounds.
                        int mouseX = target.x + target.width / 2;
                        int mouseY = target.y + target.height / 2;

                        String clickedLabel = target.displayString;
                        PendingClick started = new PendingClick();
                        if (press) {
                            started.screenBefore = screen.getClass().getSimpleName();
                            if (!pressButton(screen, target)) {
                                throw new IllegalStateException("Button '" + clickedLabel + "' "
                                    + "refused the press: its own mousePressed said no, or a mod "
                                    + "cancelled it.");
                            }
                            started.json.addProperty("via", "press");
                        }
                        else {
                            // Button positions are in the screen's own coordinates, so they convert
                            // with its own size (#163), not the ScaledResolution.
                            int[] point = SyntheticMouse.fromGui(screen, mouseX, mouseY);
                            startClick(started, screen, point[0], point[1], 0);
                        }
                        started.json.addProperty("clickedLabel", clickedLabel);
                        started.json.addProperty("clickedAtX", mouseX);
                        started.json.addProperty("clickedAtY", mouseY);
                        return started;
                    }
                });
                pending.waitMillis = context.has("waitForChange")
                    ? context.getBoundedInt("waitForChange", 0, 0, MAX_WAIT_FOR_CHANGE_MILLIS) : -1L;
                JsonObject result = finishClick(context, pending);

                return ToolResult.text("Clicked '" + result.get("clickedLabel").getAsString()
                    + "'. Screen is now " + result.get("screenName").getAsString() + "."
                    + cancelledNote(result))
                    .withStructured(result);
            })
            .build());
    }

    private static GuiButton findByIndex(GuiScreen screen, int index) {
        List<GuiButton> present = buttonsOf(screen);
        if (index < 0 || index >= present.size()) {
            throw new IllegalArgumentException("Button index " + index + " is out of range; this "
                + "screen has " + present.size() + " button(s).");
        }
        return present.get(index);
    }

    /**
     * Resolves a button by label: exact match first, then a unique substring.
     *
     * <p>An ambiguous substring is an error rather than a first-match guess. "Save" matching both
     * "Save and Quit to Title" and "Save World" should make the caller be more specific, not silently
     * pick whichever the screen happened to add first.
     */
    private static GuiButton findByLabel(GuiScreen screen, String label) {
        String needle = label.trim().toLowerCase(Locale.ROOT);
        List<GuiButton> partial = new ArrayList<>();
        for (GuiButton button : buttonsOf(screen)) {
            String display = button.displayString == null
                ? "" : button.displayString.toLowerCase(Locale.ROOT);
            if (display.equals(needle)) {
                return button;
            }
            if (display.contains(needle)) {
                partial.add(button);
            }
        }
        if (partial.size() == 1) {
            return partial.get(0);
        }
        if (partial.isEmpty()) {
            throw new IllegalArgumentException("No button on this screen matches '" + label
                + "'. Call client_gui_widgets to see the labels.");
        }
        StringBuilder options = new StringBuilder();
        for (GuiButton button : partial) {
            if (options.length() > 0) {
                options.append("', '");
            }
            options.append(button.displayString);
        }
        throw new IllegalArgumentException("'" + label + "' matches more than one button ('"
            + options + "'). Use a longer label or an index.");
    }

    // ------------------------------------------------------------------
    // Coordinate clicking, for screens with no vanilla widgets
    // ------------------------------------------------------------------

    /**
     * Click a point rather than a widget.
     *
     * <p>{@code client_gui_click} can only find {@link GuiButton}s, and a great many mod screens do
     * not use them. SuperMartijn642's Core Lib is the case that forced this: its {@code WidgetScreen}
     * draws a complete interface — text fields, toggles, sliders, spinners — out of its own widget
     * classes, so {@code buttonList} is empty and label lookup has nothing to match. That is not an
     * exotic setup; any framework that reimplements widgets looks the same from outside.
     *
     * <p>Clicking a coordinate needs none of it. Every screen, whatever it is built from, receives
     * clicks through {@code GuiScreen.mouseClicked}, because that is the only way the game delivers
     * them. Pair it with a screenshot: look at the frame, read off the pixel, click it.
     */
    private static void registerGuiClickAt() {
        McpRegistry.registerTool(McpTool.named("client_gui_click_at")
            .title("Click a point on the screen")
            .description("Click at a coordinate on the currently open screen, rather than on a named "
                + "button.\n\n"
                + "Use this when client_gui_widgets comes back with no buttons. Many mods build their "
                + "screens out of their own widget classes instead of vanilla ones — SuperMartijn642's "
                + "Core Lib is one — and those are invisible to label lookup but still receive clicks "
                + "normally, because every screen gets them through the same handler.\n\n"
                + "The workflow is: take a screenshot with inline=true, read the pixel coordinate of "
                + "the thing you want off the image, and click it. Pixel space is the default for "
                + "exactly that reason; the screen converts it with its own size, as it does a real "
                + "click, so screens laid out at their own scale ('screenScaled') work too.\n\n"
                + "A click plays out over three frames like a person's: the cursor hovers, the "
                + "button is held for a frame, then released, through Forge's mouse events. So "
                + "scrolling lists (the mod list, world list, Video Settings rows) and screens that "
                + "poll the mouse can be clicked. 'cancelledBy' in the reply means a mod took the "
                + "press in an event and handled it itself.\n\n"
                + "'hover': true moves the cursor there without clicking and holds it, so a "
                + "tooltip is drawn for a screenshot and its lines come back as hoveredSlot. To scroll "
                + "a list use client_gui_scroll; to drag a scrollbar or slider, client_gui_drag.")
            .schema(JsonSchema.object()
                .integer("x", "Horizontal coordinate.")
                .integer("y", "Vertical coordinate, measured from the top.")
                .enumeration("space", "Coordinate space. 'pixel' (the default) matches a screenshot's "
                    + "own pixels. 'gui' is Minecraft's scaled space, which is what "
                    + "client_gui_widgets reports button positions in, before any conversion a "
                    + "screen does itself.", "pixel", "gui")
                .integer("button", "Mouse button: 0 left, 1 right, 2 middle. Defaults to 0.", 0, 2)
                .integer("waitForChange", WAIT_FOR_CHANGE_DESCRIPTION, 0, MAX_WAIT_FOR_CHANGE_MILLIS)
                .bool("hover", "Move the cursor there without clicking, and hold it there for holdMs so a "
                    + "tooltip under it stays drawn for a screenshot. The reply has 'hoveredSlot' with "
                    + "the slot's item and tooltip lines on a container screen. Default false.")
                .integer("holdMs", "With hover: how long the cursor stays at the point afterwards. "
                    + "Default 5000; any later click or hover ends it.", 0, MAX_HOVER_HOLD_MILLIS)
                .required("x", "y")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }

                final int x = context.requireInt("x");
                final int y = context.requireInt("y");
                final String space = context.getString("space", "pixel");
                final int mouseButton = context.getBoundedInt("button", 0, 0, 2);
                final boolean hover = context.getBoolean("hover", false);
                final long holdMillis = context.getBoundedInt("holdMs", 5000, 0, MAX_HOVER_HOLD_MILLIS);

                final PendingClick pending = context.onGameThread(new Callable<PendingClick>() {
                    @Override
                    public PendingClick call() throws Exception {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen screen = mc.currentScreen;
                        if (screen == null) {
                            throw new IllegalStateException("No screen is open, so there is nothing to "
                                + "click.");
                        }

                        // A screenshot pixel is a display pixel, and goes to LWJGL as it is; the
                        // screen converts it with its own size, as it does a real click (#163).
                        int[] point = "gui".equals(space)
                            ? SyntheticMouse.fromGui(screen, x, y)
                            : SyntheticMouse.fromPixel(x, y);
                        int[] gui = SyntheticMouse.toGui(screen, point[0], point[1]);

                        PendingClick started = new PendingClick();
                        started.json.addProperty("handlesOwnMouseInput",
                            SyntheticMouse.overridesHandleMouseInput(screen));
                        started.json.addProperty("clickedGuiX", gui[0]);
                        started.json.addProperty("clickedGuiY", gui[1]);
                        started.json.addProperty("space", space);
                        started.json.addProperty("button", mouseButton);
                        addScreenSize(started.json, mc, screen);
                        if (hover) {
                            started.screenBefore = screen.getClass().getSimpleName();
                            started.future = SyntheticMouse.hover(point[0], point[1], holdMillis);
                            if (started.future == null) {
                                throw new IllegalStateException("LWJGL's mouse state cannot be driven "
                                    + "in this game, so the cursor cannot be moved without a click.");
                            }
                            started.expectChange = false;
                            started.waitMillis = 0L;
                            started.json.remove("button");
                        }
                        else {
                            startClick(started, screen, point[0], point[1], mouseButton);
                        }
                        return started;
                    }
                });

                if (!hover) {
                    pending.waitMillis = context.has("waitForChange")
                        ? context.getBoundedInt("waitForChange", 0, 0, MAX_WAIT_FOR_CHANGE_MILLIS) : -1L;
                }
                JsonObject result = finishClick(context, pending);
                if (hover) {
                    JsonObject slot = context.onGameThread(() -> ClientTooltipTools.hoveredSlot(
                        Minecraft.getMinecraft().currentScreen, false));
                    if (slot != null) {
                        result.add("hoveredSlot", slot);
                    }
                    result.addProperty("heldForMs", holdMillis);
                    return ToolResult.text("Cursor at gui(" + result.get("clickedGuiX").getAsInt() + ", "
                        + result.get("clickedGuiY").getAsInt() + "), held " + holdMillis + " ms; nothing "
                        + "clicked." + (slot == null ? "" : " Over slot " + slot.get("slot") + ".")
                        + cancelledNote(result)).withStructured(result);
                }

                return ToolResult.text("Clicked at gui(" + result.get("clickedGuiX").getAsInt() + ", "
                    + result.get("clickedGuiY").getAsInt() + "). Screen is now "
                    + result.get("screenName").getAsString() + "." + cancelledNote(result))
                    .withStructured(result);
            })
            .build());
    }

    /** Longest a hover may hold the cursor after it returns. */
    private static final int MAX_HOVER_HOLD_MILLIS = 30_000;

    // ------------------------------------------------------------------
    // Scrolling and dragging (#182)
    // ------------------------------------------------------------------

    /**
     * Turns the mouse wheel over a point, or scrolls a vanilla list until a row is in view.
     *
     * <p>Clicks could select rows in a scrolling list (#166), but nothing could scroll one: GuiSlot
     * scrolls only on the wheel or a drag of its bar, and ignores keys. A Controls-screen category
     * below the first page was unreachable.
     */
    private static void registerGuiScroll() {
        McpRegistry.registerTool(McpTool.named("client_gui_scroll")
            .title("Scroll a GUI list")
            .description("Turn the mouse wheel over a point on the open screen, or scroll a list until a "
                + "row is in view.\n\n"
                + "'amount' is wheel notches: positive scrolls up (toward the top), negative down; "
                + "each is one real wheel event, delivered over frames through the screen's own input "
                + "handling, so any list that scrolls on the wheel scrolls. A vanilla list moves half "
                + "a row a notch. x/y say where the cursor is (default: the middle of the screen); "
                + "lists only scroll while the cursor is over them.\n\n"
                + "'toText' instead finds the row whose text contains it in a vanilla-style list "
                + "(Controls, world list, resource packs, many mod lists) and scrolls it into view. "
                + "The reply says the row's index and where it now is, in GUI coordinates, ready for "
                + "client_gui_click_at with space 'gui'.")
            .schema(JsonSchema.object()
                .integer("amount", "Wheel notches, -" + SyntheticMouse.MAX_WHEEL_NOTCHES + " to "
                    + SyntheticMouse.MAX_WHEEL_NOTCHES + "; positive scrolls up.",
                    -SyntheticMouse.MAX_WHEEL_NOTCHES, SyntheticMouse.MAX_WHEEL_NOTCHES)
                .string("toText", "Instead of amount: scroll a list until the row containing this "
                    + "text (case-insensitive) is in view.")
                .integer("x", "Cursor x for the wheel. Default the middle of the screen.")
                .integer("y", "Cursor y for the wheel, from the top. Default the middle.")
                .enumeration("space", "'pixel' (default) for screenshot pixels, 'gui' for GUI "
                    + "coordinates.", "pixel", "gui")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }
                final String toText = context.getString("toText", null);
                if ((toText == null) == !context.has("amount")) {
                    return ToolResult.error("Pass exactly one of 'amount' (wheel notches) or 'toText' "
                        + "(a row to bring into view).");
                }
                if (toText != null) {
                    if (toText.trim().isEmpty()) {
                        return ToolResult.error("'toText' is empty.");
                    }
                    JsonObject found = context.onGameThread(() ->
                        GuiLists.scrollToText(Minecraft.getMinecraft().currentScreen, toText.trim()));
                    if (found.has("error")) {
                        return ToolResult.error(found.get("error").getAsString());
                    }
                    // A frame, so the list is drawn at its new place before anyone screenshots it.
                    com.micatechnologies.minecraft.mcmcp.client.ClientFrameClock.await(2, 0, 1000L);
                    return ToolResult.text("Scrolled '" + found.get("text").getAsString() + "' into view at "
                        + "gui y " + found.get("rowY").getAsInt() + ".").withStructured(found);
                }
                final int amount = context.getBoundedInt("amount", 0,
                    -SyntheticMouse.MAX_WHEEL_NOTCHES, SyntheticMouse.MAX_WHEEL_NOTCHES);
                if (amount == 0) {
                    return ToolResult.error("'amount' 0 scrolls nothing.");
                }
                final String space = context.getString("space", "pixel");
                final Integer x = context.has("x") ? context.requireInt("x") : null;
                final Integer y = context.has("y") ? context.requireInt("y") : null;
                final PendingClick pending = context.onGameThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    GuiScreen screen = requireScreen(mc);
                    int[] point = gesturePoint(mc, screen, x, y, space);
                    PendingClick started = gesture(mc, screen, point);
                    started.future = SyntheticMouse.scroll(point[0], point[1], amount);
                    requireDriven(started);
                    started.json.addProperty("notches", amount);
                    started.json.add("listsBefore", GuiLists.describe(screen));
                    return started;
                });
                pending.framesTimeoutMillis = CLICK_FRAMES_TIMEOUT_MILLIS + Math.abs(amount) * 100L;
                JsonObject result = finishClick(context, pending);
                JsonArray after = context.onGameThread(() -> GuiLists.describe(
                    Minecraft.getMinecraft().currentScreen));
                result.add("lists", after);
                boolean moved = !after.equals(result.get("listsBefore"));
                result.remove("listsBefore");
                return ToolResult.text("Turned the wheel " + Math.abs(amount) + " notch(es) "
                    + (amount > 0 ? "up" : "down") + (after.size() == 0 ? "."
                        : moved ? "; the list moved." : "; no vanilla list moved (the cursor may be "
                            + "outside it, or it is already at that end).")
                    + cancelledNote(result)).withStructured(result);
            })
            .build());
    }

    /** Presses at one point, moves with the button held, and releases at another. */
    private static void registerGuiDrag() {
        McpRegistry.registerTool(McpTool.named("client_gui_drag")
            .title("Drag in a GUI")
            .description("Press the mouse at 'from', move to 'to' with the button held over several "
                + "frames, and release there: a scrollbar, a slider, or a drag-and-drop. Delivered "
                + "like a person's drag, through the screen's own input handling and with LWJGL's "
                + "button held, so lists that follow the cursor while they draw (GuiSlot's "
                + "scrollbar) follow it.")
            .schema(JsonSchema.object()
                .array("from", "Start point [x, y].", Json.obj("type", "integer"))
                .array("to", "End point [x, y].", Json.obj("type", "integer"))
                .enumeration("space", "'pixel' (default) for screenshot pixels, 'gui' for GUI "
                    + "coordinates.", "pixel", "gui")
                .integer("button", "Mouse button: 0 left (default), 1 right, 2 middle.", 0, 2)
                .integer("steps", "Frames the move is spread over. Default 10.", 1, 60)
                .required("from", "to")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }
                final List<Integer> from = pair(context.getArguments(), "from");
                final List<Integer> to = pair(context.getArguments(), "to");
                if (from.size() != 2 || to.size() != 2) {
                    return ToolResult.error("'from' and 'to' are each [x, y].");
                }
                final String space = context.getString("space", "pixel");
                final int button = context.getBoundedInt("button", 0, 0, 2);
                final int steps = context.getBoundedInt("steps", 10, 1, 60);
                final PendingClick pending = context.onGameThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    GuiScreen screen = requireScreen(mc);
                    int[] start = gesturePoint(mc, screen, from.get(0), from.get(1), space);
                    int[] end = gesturePoint(mc, screen, to.get(0), to.get(1), space);
                    PendingClick started = gesture(mc, screen, start);
                    started.future = SyntheticMouse.drag(start[0], start[1], end[0], end[1], button, steps);
                    requireDriven(started);
                    int[] endGui = SyntheticMouse.toGui(screen, end[0], end[1]);
                    started.json.addProperty("toGuiX", endGui[0]);
                    started.json.addProperty("toGuiY", endGui[1]);
                    started.json.add("listsBefore", GuiLists.describe(screen));
                    return started;
                });
                pending.framesTimeoutMillis = CLICK_FRAMES_TIMEOUT_MILLIS + steps * 100L;
                JsonObject result = finishClick(context, pending);
                JsonArray after = context.onGameThread(() -> GuiLists.describe(
                    Minecraft.getMinecraft().currentScreen));
                if (after.size() > 0) {
                    result.add("lists", after);
                }
                result.remove("listsBefore");
                return ToolResult.text("Dragged from gui(" + result.get("clickedGuiX").getAsInt() + ", "
                    + result.get("clickedGuiY").getAsInt() + ") to gui(" + result.get("toGuiX").getAsInt()
                    + ", " + result.get("toGuiY").getAsInt() + "). Screen is now "
                    + result.get("screenName").getAsString() + "." + cancelledNote(result))
                    .withStructured(result);
            })
            .build());
    }

    /** {@code [x, y]} from an argument as two whole numbers, or an empty list when it is not that. */
    private static List<Integer> pair(JsonObject arguments, String name) {
        List<Integer> values = new ArrayList<>();
        JsonElement raw = arguments.get(name);
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 2) {
            return values;
        }
        for (JsonElement element : raw.getAsJsonArray()) {
            try {
                values.add((int) Math.round(element.getAsDouble()));
            }
            catch (RuntimeException notNumber) {
                return new ArrayList<>();
            }
        }
        return values;
    }

    private static GuiScreen requireScreen(Minecraft mc) {
        if (mc.currentScreen == null) {
            throw new IllegalStateException("No screen is open. Check client_gui_state first.");
        }
        return mc.currentScreen;
    }

    /** An LWJGL point from a caller's x/y in their space, or the middle of the screen when absent. */
    private static int[] gesturePoint(Minecraft mc, GuiScreen screen, @Nullable Integer x,
                                      @Nullable Integer y, String space) {
        if ("gui".equals(space)) {
            return SyntheticMouse.fromGui(screen, x == null ? screen.width / 2 : x,
                y == null ? screen.height / 2 : y);
        }
        return SyntheticMouse.fromPixel(x == null ? mc.displayWidth / 2 : x,
            y == null ? mc.displayHeight / 2 : y);
    }

    /** A pending gesture that reports where it started and never waits for a screen change. */
    private static PendingClick gesture(Minecraft mc, GuiScreen screen, int[] point) {
        PendingClick started = new PendingClick();
        started.screenBefore = screen.getClass().getSimpleName();
        started.expectChange = false;
        started.waitMillis = 0L;
        int[] gui = SyntheticMouse.toGui(screen, point[0], point[1]);
        started.json.addProperty("clickedGuiX", gui[0]);
        started.json.addProperty("clickedGuiY", gui[1]);
        addScreenSize(started.json, mc, screen);
        return started;
    }

    private static void requireDriven(PendingClick started) {
        if (started.future == null) {
            throw new IllegalStateException("LWJGL's mouse state cannot be driven in this game, so "
                + "the wheel and drags cannot be delivered.");
        }
    }

    // ------------------------------------------------------------------
    // Raw key input, for screens with no vanilla text fields
    // ------------------------------------------------------------------

    /**
     * Send keystrokes to the screen itself rather than to a {@link GuiTextField}.
     *
     * <p>The keyboard counterpart to {@code client_gui_click_at}, and needed for the same reason: a
     * mod's own text field is not a {@code GuiTextField}, so {@code client_gui_text} cannot find it.
     * Whatever it is, it receives characters through the screen's {@code keyTyped}, because that is
     * where the game delivers them. Click the field first to focus it, then type.
     */
    private static void registerGuiKey() {
        McpRegistry.registerTool(McpTool.named("client_gui_key")
            .title("Send keystrokes to a screen")
            .description("Send characters or a special key straight to the open screen's key handler.\n\n"
                + "Use this when client_gui_text finds no text fields — a mod's own text widget is not "
                + "a vanilla one, but it still receives keys through the screen. Click the field first "
                + "to give it focus, then type into it.\n\n"
                + "Either send 'text' to type a run of characters, or 'key' for a single named key "
                + "such as backspace or enter.")
            .schema(JsonSchema.object()
                .string("text", "Characters to type, one keyTyped call each.")
                .enumeration("key", "A single named key to press instead of text.",
                    "enter", "backspace", "delete", "tab", "escape", "up", "down", "left", "right",
                    "home", "end")
                .integer("repeat", "How many times to press a named key. Defaults to 1.", 1, 100)
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }

                final String text = context.getString("text", null);
                final String key = context.getString("key", null);
                final int repeat = context.getBoundedInt("repeat", 1, 1, 100);
                if (text == null && key == null) {
                    return ToolResult.error("Pass either 'text' to type characters or 'key' for a "
                        + "named key.");
                }

                final int keyCode = key == null ? -1 : namedKeyCode(key);
                if (key != null && keyCode < 0) {
                    return ToolResult.error("Unknown key '" + key + "'.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen screen = mc.currentScreen;
                        if (screen == null) {
                            throw new IllegalStateException("No screen is open, so there is nothing to "
                                + "type into.");
                        }
                        Method keyTyped = keyTypedMethod();

                        // Screens that reimplement input often override handleKeyboardInput to read
                        // LWJGL directly and dispatch to their own charTyped/keyPressed, never
                        // calling keyTyped at all. SuperMartijn642's Core Lib does exactly this, and
                        // the names it uses are vanilla's own from 1.13 onwards, so honouring them
                        // is following a convention rather than special-casing one mod.
                        //
                        // The symptom when this is missed is precise and misleading: Escape still
                        // works, because that is handled by the inherited keyTyped, while every
                        // character silently vanishes. It looks like a focus problem and is not.
                        Method charTyped = screenMethod(screen, "charTyped", char.class);
                        Method keyPressed = screenMethod(screen, "keyPressed", int.class);

                        if (keyTyped == null && charTyped == null && keyPressed == null) {
                            throw new IllegalStateException("This screen exposes no key handler this "
                                + "tool knows how to call.");
                        }

                        String screenBefore = screen.getClass().getSimpleName();
                        String dispatch = "keyTyped";
                        int sent = 0;

                        if (text != null) {
                            for (char character : text.toCharArray()) {
                                if (charTyped != null) {
                                    charTyped.invoke(screen, character);
                                    dispatch = "charTyped";
                                }
                                else if (keyTyped != null) {
                                    keyTyped.invoke(screen, character, 0);
                                }
                                sent++;
                            }
                        }
                        if (keyCode >= 0) {
                            for (int i = 0; i < repeat; i++) {
                                if (keyPressed != null) {
                                    keyPressed.invoke(screen, keyCode);
                                    dispatch = "keyPressed";
                                }
                                else if (keyTyped != null) {
                                    // Character 0 for a non-printing key: vanilla text fields switch
                                    // on the key code for these and ignore the character, and passing
                                    // a printable one would have some widgets insert it as well as
                                    // acting on the key.
                                    keyTyped.invoke(screen, '\0', keyCode);
                                }
                                sent++;
                            }
                        }

                        JsonObject json = describeScreen(mc.currentScreen);
                        json.addProperty("keystrokesSent", sent);
                        json.addProperty("dispatchedVia", dispatch);
                        json.addProperty("screenBefore", screenBefore);
                        return json;
                    }
                });

                return ToolResult.text("Sent " + result.get("keystrokesSent").getAsInt()
                    + " keystroke(s). Screen is now " + result.get("screenName").getAsString() + ".")
                    .withStructured(result);
            })
            .build());
    }

    /** LWJGL key codes for the named keys, which are the ones worth reaching without raw codes. */
    private static int namedKeyCode(String name) {
        if ("enter".equals(name)) {
            return KEY_RETURN;
        }
        if ("backspace".equals(name)) {
            return 14;
        }
        if ("delete".equals(name)) {
            return 211;
        }
        if ("tab".equals(name)) {
            return 15;
        }
        if ("escape".equals(name)) {
            return KEY_ESCAPE;
        }
        if ("up".equals(name)) {
            return 200;
        }
        if ("down".equals(name)) {
            return 208;
        }
        if ("left".equals(name)) {
            return 203;
        }
        if ("right".equals(name)) {
            return 205;
        }
        if ("home".equals(name)) {
            return 199;
        }
        if ("end".equals(name)) {
            return 207;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Typing
    // ------------------------------------------------------------------

    /**
     * Tells the screen its field changed, by focusing the field and sending one End keystroke.
     *
     * <p>Setting a field's text directly bypasses the screen. Screens that decide whether a button is
     * enabled do it in {@code keyTyped}, after the field reports having taken a key — vanilla's
     * Direct Connect enables "Join Server" that way — so the button kept the state it had for the
     * empty field, and a click or Enter afterwards did nothing (issue #26). The field is focused
     * first, and every other field unfocused, because an unfocused vanilla field refuses keys and a
     * focused neighbour would take the keystroke instead.
     *
     * @return the path the keystroke took, or {@code "none"} when the field would not take focus
     *         and the keystroke would have gone somewhere else
     */
    private static String nudge(GuiScreen screen, List<TextWidget> fields, TextWidget field)
        throws Exception {
        if (!Boolean.TRUE.equals(field.focused())) {
            for (TextWidget other : fields) {
                if (other != field) {
                    other.focus(false);
                }
            }
            field.focus(true);
        }
        if (Boolean.FALSE.equals(field.focused())) {
            return "none";
        }
        Method keyPressed = screenMethod(screen, "keyPressed", int.class);
        if (keyPressed != null) {
            keyPressed.invoke(screen, KEY_END);
            return "keyPressed";
        }
        Method keyTyped = keyTypedMethod();
        if (keyTyped != null) {
            keyTyped.invoke(screen, '\0', KEY_END);
            return "keyTyped";
        }
        return "none";
    }

    private static void registerGuiText() {
        McpRegistry.registerTool(McpTool.named("client_gui_text")
            .title("Type into a GUI text field")
            .description("Set the contents of a text field on the currently open screen — a world "
                + "name, a seed, a search box, a rename field.\n\n"
                + "Fields are found by shape rather than type: anything exposing getText/setText "
                + "counts, which covers vanilla text boxes and mod widget frameworks alike. Use "
                + "client_gui_widgets to see what was found and at which index.\n\n"
                + "The value is set outright rather than typed character by character. Replaying "
                + "keystrokes depends on where the caret happens to be, so 'clear and type' quietly "
                + "becomes 'insert halfway through' on a field a click has already put a caret into. "
                + "The widget may still refuse part of what you asked for — a length cap or a "
                + "character filter — so check 'fullyAccepted' and the returned text.\n\n"
                + "Afterwards the field is focused and sent one End keystroke, so a screen that "
                + "enables its buttons as you type sees the change. 'buttonsChanged' lists any "
                + "button that became enabled or disabled.\n\n"
                + "If a screen visibly has a field this cannot find, fall back to "
                + "client_gui_click_at plus client_gui_key.")
            .schema(JsonSchema.object()
                .string("text", "The text to type.")
                .integer("index", "Which text field, by index from client_gui_widgets. Defaults to 0, "
                    + "which is the only field on most screens.")
                .bool("clear", "Clear the field before typing. Defaults to true; pass false to append.")
                .bool("submit", "Press Enter afterwards, for screens that act on the field's contents "
                    + "when it is submitted. Defaults to false.")
                .required("text")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }

                final String text = context.requireString("text");
                final int index = context.getInt("index", 0);
                final boolean clear = context.getBoolean("clear", true);
                final boolean submit = context.getBoolean("submit", false);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen screen = mc.currentScreen;
                        if (screen == null) {
                            throw new IllegalStateException("No screen is open, so there is no text "
                                + "field to type into.");
                        }

                        List<TextWidget> fields = textWidgets(screen);
                        if (fields.isEmpty()) {
                            throw new IllegalStateException("No text fields were found on "
                                + screen.getClass().getSimpleName() + ". If the screen visibly has "
                                + "one, drive it with client_gui_click_at and client_gui_key instead.");
                        }
                        if (index < 0 || index >= fields.size()) {
                            throw new IllegalArgumentException("Text field index " + index + " is out "
                                + "of range; this screen has " + fields.size() + " field(s).");
                        }

                        TextWidget field = fields.get(index);
                        String before = field.read();
                        String screenBefore = screen.getClass().getSimpleName();

                        // Set the value outright rather than replaying keystrokes. Typing into a
                        // field depends on where its caret happens to be, which a click positions
                        // somewhere in the middle of the existing text — so "clear then type"
                        // silently becomes "insert halfway through", and backspacing a fixed number
                        // of times either overshoots into the previous value or leaves a tail.
                        // Setting the string is the operation actually wanted, and it is what the
                        // widget's own setter is for.
                        Map<GuiButton, Boolean> enabledBefore = new IdentityHashMap<>();
                        for (GuiButton button : buttonsOf(screen)) {
                            enabledBefore.put(button, button.enabled);
                        }

                        field.write(clear ? text : before + text);
                        String resulting = field.read();
                        String nudgedVia = nudge(screen, fields, field);

                        // Which buttons the new text switched on or off. A button that stays
                        // disabled is the likeliest reason a following click "does nothing", and
                        // saying so here saves the model a client_gui_widgets call to find out.
                        JsonArray buttonsChanged = new JsonArray();
                        List<GuiButton> buttonsNow = buttonsOf(screen);
                        for (int i = 0; i < buttonsNow.size(); i++) {
                            GuiButton button = buttonsNow.get(i);
                            Boolean was = enabledBefore.get(button);
                            if (was != null && was != button.enabled) {
                                JsonObject change = new JsonObject();
                                change.addProperty("index", i);
                                change.addProperty("label", button.displayString);
                                change.addProperty("enabled", button.enabled);
                                buttonsChanged.add(change);
                            }
                        }

                        if (submit) {
                            Method keyTyped = keyTypedMethod();
                            Method keyPressed = screenMethod(screen, "keyPressed", int.class);
                            if (keyPressed != null) {
                                keyPressed.invoke(screen, KEY_RETURN);
                            }
                            else if (keyTyped != null) {
                                keyTyped.invoke(screen, '\r', KEY_RETURN);
                            }
                            else {
                                throw new IllegalStateException("This screen exposes no key handler, "
                                    + "so 'submit' is unavailable.");
                            }
                        }

                        JsonObject json = describeScreen(mc.currentScreen);
                        json.addProperty("fieldIndex", index);
                        json.addProperty("widgetType", field.type);
                        json.addProperty("text", resulting);
                        json.addProperty("textBefore", before);
                        json.addProperty("requestedText", text);
                        // The widget may refuse part of what was asked for — a length cap, or a
                        // character filter. Comparing rather than assuming is the point.
                        json.addProperty("fullyAccepted",
                            clear ? resulting.equals(text) : resulting.equals(before + text));
                        json.addProperty("nudgedVia", nudgedVia);
                        if (buttonsChanged.size() > 0) {
                            json.add("buttonsChanged", buttonsChanged);
                        }
                        json.addProperty("submitted", submit);
                        json.addProperty("screenBefore", screenBefore);
                        return json;
                    }
                });

                return ToolResult.text("Field now reads: " + result.get("text").getAsString())
                    .withStructured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Closing a screen
    // ------------------------------------------------------------------

    /**
     * Escape.
     *
     * <p>Small, and the difference between a recoverable session and a stuck one. Minecraft pauses
     * singleplayer and opens {@code GuiIngameMenu} whenever its window loses focus, which happens
     * every time the operator switches applications. Without this, that menu can only be dismissed by
     * a human at the keyboard — so an unattended session ends the first time somebody alt-tabs.
     */
    private static void registerGuiClose() {
        McpRegistry.registerTool(McpTool.named("client_gui_close")
            .title("Close the current screen")
            .description("Dismiss the screen that is currently open, as pressing Escape would.\n\n"
                + "The usual reason to need this is the pause menu: Minecraft pauses singleplayer and "
                + "opens it whenever the game window loses focus, and it stays open until something "
                + "closes it. A paused game ignores movement input and renders the menu over every "
                + "screenshot.\n\n"
                + "Escape is delivered to the screen's own key handler, so a screen that declines to "
                + "close on Escape stays open; the reply says what is on screen afterwards.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen screen = mc.currentScreen;
                        if (screen == null) {
                            JsonObject json = describeScreen(null);
                            json.addProperty("closed", false);
                            // "No screen was open" is not the whole story when the game also has no
                            // input focus: that is precisely the state an unattended session sits in
                            // once pauseOnLostFocus is off, and it is the state in which held attack
                            // silently does nothing. This tool's job is handing input back to the
                            // game, so do that here too rather than only after dismissing a screen.
                            boolean grabbed = false;
                            if (mc.world != null && !mc.inGameHasFocus) {
                                mc.setIngameFocus();
                                grabbed = true;
                            }
                            json.addProperty("grabbedInputFocus", grabbed);
                            json.addProperty("inGameFocus", mc.inGameHasFocus);
                            json.addProperty("note", grabbed
                                ? "No screen was open; input focus was returned to the game."
                                : "No screen was open.");
                            return json;
                        }

                        String screenBefore = screen.getClass().getSimpleName();
                        Method keyTyped = keyTypedMethod();
                        if (keyTyped == null) {
                            throw new IllegalStateException("Could not locate GuiScreen.keyTyped on "
                                + "this Minecraft build; closing screens is unavailable.");
                        }
                        keyTyped.invoke(screen, '\0', KEY_ESCAPE);

                        // Closing the last screen should hand input back to the game. Minecraft does
                        // this itself when Escape is handled by the default path, but a screen with a
                        // custom handler may not, and a world left with no screen and no input focus
                        // ignores every movement tool.
                        if (mc.currentScreen == null && mc.world != null && !mc.inGameHasFocus) {
                            mc.setIngameFocus();
                        }

                        JsonObject json = describeScreen(mc.currentScreen);
                        json.addProperty("closed", mc.currentScreen == null);
                        json.addProperty("screenBefore", screenBefore);
                        json.addProperty("paused", mc.isGamePaused());
                        json.addProperty("inGameFocus", mc.inGameHasFocus);
                        return json;
                    }
                });

                boolean closed = result.get("closed").getAsBoolean();
                return ToolResult.text(closed
                    ? "Closed the screen; nothing is open now."
                    : "Screen did not close; " + result.get("screenName").getAsString()
                        + " is still open.")
                    .withStructured(result);
            })
            .build());
    }

    /**
     * Opening a vanilla screen from in-world.
     *
     * <p>Every other GUI tool acts on a screen that is already open, and Escape — the only key that
     * opens the pause menu — is not a gameplay binding {@code client_key} can press. The pause menu
     * otherwise opens only when the window loses focus, which an agent cannot arrange, so the
     * Options screens behind it were unreachable and muting a test client took a restart and an edit
     * to options.txt (issue #47).
     */
    /**
     * A mod's own Config screen, made the way the mod list's Config button makes it (#166): its
     * {@code IModGuiFactory.createConfigGui}. The mod list itself needs a polled click to select an
     * entry, so this is the direct way in.
     */
    private static GuiScreen modConfigScreen(String modId, @Nullable GuiScreen parent) {
        ModContainer mod = Loader.instance().getIndexedModList().get(modId);
        if (mod == null) {
            throw new IllegalArgumentException("No mod '" + modId + "' is loaded; game_list_mods lists "
                + "the ids.");
        }
        IModGuiFactory factory = FMLClientHandler.instance().getGuiFactoryFor(mod);
        if (factory == null || !factory.hasConfigGui()) {
            throw new IllegalArgumentException("'" + modId + "' has no Config screen; its Config "
                + "button in the mod list is disabled too.");
        }
        GuiScreen config = factory.createConfigGui(parent == null ? new GuiModList(null) : parent);
        if (config == null) {
            throw new IllegalStateException("'" + modId + "' declares a Config screen but made none.");
        }
        return config;
    }

    private static void registerGuiOpen() {
        McpRegistry.registerTool(McpTool.named("client_gui_open")
            .title("Open a screen")
            .description("Open a vanilla screen, which the other client_gui_* tools can then drive: "
                + "'pause' (the Escape menu; pauses singleplayer), 'options', 'sound', 'controls', "
                + "'video', or 'chat' (optionally prefilled with 'text'); 'mods' (the mod list), or "
                + "'mod_config' with 'modId' for that mod's own Config screen, as its Config button "
                + "opens it. The options screens return to the screen that was open, or to the game, "
                + "on Done.\n\n"
                + "To change a setting without clicking through a screen, use client_options.")
            .schema(JsonSchema.object()
                .enumeration("screen", "Which screen to open.",
                    "pause", "options", "sound", "controls", "video", "chat", "mods", "mod_config")
                .string("text", "chat: text to prefill the chat box with, e.g. '/'.")
                .string("modId", "mod_config: the mod id, as game_list_mods reports it.")
                .required("screen")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }
                final String screen = context.requireString("screen");
                final String text = context.getString("text", "");
                if (!Arrays.asList("pause", "options", "sound", "controls", "video", "chat", "mods",
                    "mod_config").contains(screen)) {
                    return ToolResult.error("Unknown screen '" + screen + "'; use pause, options, "
                        + "sound, controls, video, chat, mods or mod_config.");
                }
                final String modId = context.getString("modId", null);
                if ("mod_config".equals(screen) && modId == null) {
                    return ToolResult.error("mod_config needs 'modId'; game_list_mods lists them.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen parent = mc.currentScreen;
                        GuiScreen opened;
                        if ("pause".equals(screen) || "chat".equals(screen)) {
                            ClientStateTools.requireInWorld();
                            opened = "pause".equals(screen) ? new GuiIngameMenu() : new GuiChat(text);
                        }
                        else if ("options".equals(screen)) {
                            opened = new GuiOptions(parent, mc.gameSettings);
                        }
                        else if ("sound".equals(screen)) {
                            opened = new GuiScreenOptionsSounds(parent, mc.gameSettings);
                        }
                        else if ("controls".equals(screen)) {
                            opened = new GuiControls(parent, mc.gameSettings);
                        }
                        else if ("mods".equals(screen)) {
                            opened = new GuiModList(parent);
                        }
                        else if ("mod_config".equals(screen)) {
                            opened = modConfigScreen(modId, parent);
                        }
                        else {
                            opened = new GuiVideoSettings(parent, mc.gameSettings);
                        }
                        String screenBefore = parent == null ? "none" : parent.getClass().getSimpleName();
                        mc.displayGuiScreen(opened);

                        // GuiOpenEvent lets a mod replace or cancel the screen, so report what is
                        // actually open rather than what was asked for.
                        JsonObject json = describeScreen(mc.currentScreen);
                        json.addProperty("opened", mc.currentScreen == opened);
                        json.addProperty("screenBefore", screenBefore);
                        json.addProperty("paused", mc.isGamePaused());
                        return json;
                    }
                });
                return ToolResult.text(result.get("opened").getAsBoolean()
                    ? "Opened " + result.get("screenName").getAsString() + "."
                    : "Asked for '" + screen + "', but " + result.get("screenName").getAsString()
                        + " is open: a mod replaced or cancelled the screen.")
                    .withStructured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // HUD and camera perspective
    // ------------------------------------------------------------------

    /**
     * The two settings that decide what a screenshot actually contains.
     *
     * <p>Hiding the HUD is F1, and it is the difference between a usable capture and one with a
     * hotbar across the bottom and a hand in the corner. Perspective is F5. Neither is player control
     * in any meaningful sense — they change what this client draws for itself and nothing else — so
     * they are gated on screenshot permission rather than on player control.
     */
    private static void registerView() {
        McpRegistry.registerTool(McpTool.named("client_view")
            .title("HUD and camera perspective")
            .description("Show or hide the HUD and switch camera perspective — the F1 and F5 keys.\n\n"
                + "Hiding the HUD removes the hotbar, crosshair, hand and chat overlay from the "
                + "rendered frame, which is what makes a screenshot usable as documentation rather "
                + "than a debug capture. Third-person perspective is how you photograph the player in "
                + "a scene, or see a block the player is standing on.\n\n"
                + "Both persist until changed, so set the HUD back to visible when you are done or "
                + "every later screenshot is missing it.\n\n"
                + "'pauseOnLostFocus' is the third setting here and the one that makes unattended "
                + "work possible at all: Minecraft pauses singleplayer and opens the menu every time "
                + "its window loses focus, which it has whenever nobody is sitting at the machine. "
                + "Closing that menu does not help, because it reopens. Set this false and it stops "
                + "happening.\n\n"
                + "'grabInputFocus' is its companion, and you need it for mining. Turning the pause "
                + "off keeps the game running, but the game still considers itself unfocused, and "
                + "Minecraft gates CONTINUOUS left-click on that flag alone — so held attack applies "
                + "the input, changes nothing, and reports no error. Set this true to hand input "
                + "back. Everything else (movement, right-click, looking) works without it, which is "
                + "exactly why the gap is easy to miss.\n\n"
                + "'windowSize': [width, height] resizes the game window, and with it every later "
                + "screenshot (a fresh client opens at 854x480). Not in fullscreen. The reply gives "
                + "the size the game now draws at. 'dismissTutorial' ends the first-run tutorial and "
                + "clears its toasts, which otherwise sit over the top-right of every frame.")
            .schema(JsonSchema.object()
                .array("windowSize", "Window client area [width, height] in pixels, from "
                    + MIN_WINDOW_WIDTH + "x" + MIN_WINDOW_HEIGHT + " to " + MAX_WINDOW_SIDE + " a side.",
                    Json.obj("type", "integer"))
                .bool("dismissTutorial", "End the tutorial ('Move with WASD', 'Look around') and "
                    + "clear the toasts on screen.")
                .bool("hideHud", "True hides the HUD, false shows it. Omit to leave it unchanged.")
                .enumeration("perspective", "Camera perspective. Omit to leave it unchanged.",
                    "first", "third_back", "third_front")
                .bool("pauseOnLostFocus", "Whether losing window focus pauses the game and opens the "
                    + "menu. Set false for unattended sessions. Omit to leave it unchanged.")
                .bool("grabInputFocus", "True hands input back to the game when it has lost focus, "
                    + "which is what makes held attack (mining) work in an unattended session. "
                    + "Omit to leave it unchanged.")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowScreenshots()) {
                    return ToolResult.error("View control is disabled by permissions.allowScreenshots "
                        + "in the MCMCP config, since its only purpose is shaping what a capture "
                        + "contains.");
                }

                final JsonObject arguments = context.getArguments();
                final boolean changeHud = arguments.has("hideHud");
                final boolean hideHud = context.getBoolean("hideHud", false);
                final boolean changePause = arguments.has("pauseOnLostFocus");
                final boolean pauseOnLostFocus = context.getBoolean("pauseOnLostFocus", true);
                final boolean grabInputFocus = context.getBoolean("grabInputFocus", false);
                final String perspective = context.getString("perspective", null);
                final boolean dismissTutorial = context.getBoolean("dismissTutorial", false);
                final List<Integer> windowSize = pair(arguments, "windowSize");
                if (arguments.has("windowSize") && (windowSize.size() != 2
                    || windowSize.get(0) < MIN_WINDOW_WIDTH || windowSize.get(1) < MIN_WINDOW_HEIGHT
                    || windowSize.get(0) > MAX_WINDOW_SIDE || windowSize.get(1) > MAX_WINDOW_SIDE)) {
                    return ToolResult.error("'windowSize' is [width, height], each whole pixels from "
                        + MIN_WINDOW_WIDTH + "x" + MIN_WINDOW_HEIGHT + " up to " + MAX_WINDOW_SIDE
                        + ". Nothing was changed.");
                }

                final int perspectiveValue;
                if (perspective == null) {
                    perspectiveValue = -1;
                }
                else if ("first".equals(perspective)) {
                    perspectiveValue = 0;
                }
                else if ("third_back".equals(perspective)) {
                    perspectiveValue = 1;
                }
                else if ("third_front".equals(perspective)) {
                    perspectiveValue = 2;
                }
                else {
                    return ToolResult.error("Unknown perspective '" + perspective
                        + "'; expected first, third_back or third_front.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = Minecraft.getMinecraft();
                        if (changeHud) {
                            mc.gameSettings.hideGUI = hideHud;
                        }
                        if (perspectiveValue >= 0) {
                            mc.gameSettings.thirdPersonView = perspectiveValue;
                        }
                        if (changePause) {
                            mc.gameSettings.pauseOnLostFocus = pauseOnLostFocus;
                            // Turning it off does not dismiss a menu already open, and that menu is
                            // usually the reason anyone reaches for this setting. Close it here so
                            // the caller does not have to follow every call with client_gui_close.
                            if (!pauseOnLostFocus && mc.currentScreen instanceof GuiIngameMenu) {
                                mc.displayGuiScreen(null);
                            }
                        }
                        // Only with a world and no screen: setIngameFocus grabs the mouse and
                        // dismisses nothing, so calling it under an open screen would leave that
                        // screen up with the cursor captured behind it.
                        if (grabInputFocus && mc.world != null && mc.currentScreen == null
                            && !mc.inGameHasFocus) {
                            mc.setIngameFocus();
                        }
                        String windowError = null;
                        if (windowSize.size() == 2) {
                            windowError = resizeWindow(mc, windowSize.get(0), windowSize.get(1));
                        }
                        if (dismissTutorial) {
                            mc.getTutorial().setStep(TutorialSteps.NONE);
                            mc.getToastGui().clear();
                        }
                        // Deliberately not saveOptions(): this is a runtime override for an
                        // automated session, not a change to how the person who owns this client
                        // wants their game to behave. It resets when the game restarts, which is
                        // the right default for something that disables a safety behaviour.

                        JsonObject json = new JsonObject();
                        json.addProperty("hudHidden", mc.gameSettings.hideGUI);
                        json.addProperty("pauseOnLostFocus", mc.gameSettings.pauseOnLostFocus);
                        json.addProperty("inGameFocus", mc.inGameHasFocus);
                        json.addProperty("thirdPersonView", mc.gameSettings.thirdPersonView);
                        json.addProperty("perspective",
                            mc.gameSettings.thirdPersonView == 0 ? "first"
                                : mc.gameSettings.thirdPersonView == 1 ? "third_back" : "third_front");
                        json.addProperty("displayWidth", mc.displayWidth);
                        json.addProperty("displayHeight", mc.displayHeight);
                        if (windowError != null) {
                            json.addProperty("windowError", windowError);
                        }
                        if (dismissTutorial) {
                            json.addProperty("tutorialDismissed", true);
                        }
                        return json;
                    }
                });
                if (result.has("windowError")) {
                    return ToolResult.error(result.get("windowError").getAsString());
                }
                return ToolResult.text("HUD "
                    + (result.get("hudHidden").getAsBoolean() ? "hidden" : "visible")
                    + ", perspective " + result.get("perspective").getAsString() + ", drawing at "
                    + result.get("displayWidth").getAsInt() + "x" + result.get("displayHeight").getAsInt()
                    + ".")
                    .withStructured(result);
            })
            .build());
    }

    private static final int MIN_WINDOW_WIDTH = 320;
    private static final int MIN_WINDOW_HEIGHT = 240;
    private static final int MAX_WINDOW_SIDE = 7680;

    /**
     * Resizes the window's client area (#180), the way leaving fullscreen does: a new display mode,
     * then {@code Minecraft.resize}, which re-lays out the open screen and rebuilds the framebuffer
     * that screenshots read. Client thread only.
     *
     * @return why it could not be done, or null
     */
    @Nullable
    private static String resizeWindow(Minecraft mc, int width, int height) {
        if (mc.isFullScreen()) {
            return "The game is fullscreen, which takes the monitor's size; leave fullscreen first. "
                + "Nothing was changed.";
        }
        try {
            org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(width, height));
            // Forge's fix for MC-68754: the window is not resizable by hand again until this is cycled.
            org.lwjgl.opengl.Display.setResizable(false);
            org.lwjgl.opengl.Display.setResizable(true);
        }
        catch (org.lwjgl.LWJGLException | RuntimeException e) {
            return "The window could not be resized to " + width + "x" + height + ": " + e.getMessage();
        }
        mc.resize(width, height);
        return null;
    }
}
