package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.client.SyntheticMouse;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
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
 * <h2>Reflection, and why it is named twice</h2>
 *
 * {@code mouseClicked}, {@code keyTyped} and the {@code buttonList} field are all {@code protected}
 * on {@link GuiScreen}, so there is no public path to any of them. These are the only reflective
 * accesses in MCMCP. Each is looked up by MCP name <em>and</em> SRG name ({@code func_73864_a},
 * {@code func_73869_a}, {@code field_146292_n}), because a development client runs deobfuscated while
 * a released jar is reobfuscated — a lookup by one name alone works in exactly one of the two
 * environments, and which one it fails in is whichever you did not test.
 *
 * <p>Text fields are the exception: they are found by scanning a screen's fields for the
 * {@link GuiTextField} <em>type</em> rather than by name, which needs no mapping and works on a mod's
 * own screen where no name could have been known in advance.
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

    private ClientGuiTools() {
    }

    public static void register() {
        registerGuiWidgets();
        registerGuiClick();
        registerGuiClickAt();
        registerGuiKey();
        registerGuiText();
        registerGuiClose();
        registerView();
    }

    /**
     * Converts a point in display pixels — what you measure off a screenshot — into the scaled
     * coordinate space that {@code mouseClicked} and widget positions use.
     *
     * <p>No Y flip. LWJGL's mouse origin is bottom-left and vanilla flips it when reading
     * {@code Mouse.getY()}, but a screenshot's origin is top-left, which already matches the GUI's.
     * Flipping here would put every click the same distance from the wrong edge.
     */
    private static int toGuiX(Minecraft mc, int pixelX) {
        ScaledResolution resolution = new ScaledResolution(mc);
        return pixelX * resolution.getScaledWidth() / Math.max(1, mc.displayWidth);
    }

    private static int toGuiY(Minecraft mc, int pixelY) {
        ScaledResolution resolution = new ScaledResolution(mc);
        return pixelY * resolution.getScaledHeight() / Math.max(1, mc.displayHeight);
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
     * Delivers a click at a point in GUI space, and reports which path carried it.
     *
     * <p>Prefers {@link SyntheticMouse}, which sets LWJGL's mouse state and calls the screen's
     * {@code handleMouseInput()} - the same route a real click takes. That matters because
     * {@code mouseClicked} is only one branch *inside* the real route, and a screen that overrides
     * {@code handleMouseInput} may never reach it: MalisisCore's screens do their own hit testing and
     * ignore {@code mouseClicked} entirely, so calling it clicked nothing and reported success.
     *
     * <p>Falls back to {@code mouseClicked} when LWJGL cannot be driven - a shaded build, or
     * LWJGL3ify's shim - because that is still correct for most screens and a working majority beats
     * a uniform failure.
     *
     * @return the delivery path, for the caller to put in its result: models retry a click that did
     *         nothing, and knowing which path ran is the difference between "the button is broken"
     *         and "this screen needs the other path".
     */
    private static String deliverClick(GuiScreen screen, int guiX, int guiY, int button,
                                       int scaledWidth, int scaledHeight) throws Exception {
        if (SyntheticMouse.click(screen, guiX, guiY, button, scaledWidth, scaledHeight)) {
            return "lwjgl";
        }
        Method mouseClicked = mouseClickedMethod();
        if (mouseClicked == null) {
            throw new IllegalStateException("Could not drive LWJGL's mouse state, and could not locate "
                + "GuiScreen.mouseClicked on this Minecraft build; GUI clicking is unavailable.");
        }
        mouseClicked.invoke(screen, guiX, guiY, button);
        return "mouseClicked";
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
        final Method getter;
        final Method setter;
        @Nullable
        final Method focusGetter;
        final String type;

        TextWidget(Object target, Method getter, Method setter, @Nullable Method focusGetter) {
            this.target = target;
            this.getter = getter;
            this.setter = setter;
            this.focusGetter = focusGetter;
            this.type = target.getClass().getSimpleName();
        }

        String read() throws Exception {
            Object value = getter.invoke(target);
            return value == null ? "" : value.toString();
        }

        void write(String value) throws Exception {
            setter.invoke(target, value);
        }

        @Nullable
        Boolean focused() {
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
            Method getter = findAccessible(node.getClass(), "getText");
            Method setter = findAccessible(node.getClass(), "setText", String.class);
            if (getter != null && setter != null && getter.getReturnType() == String.class) {
                found.add(new TextWidget(node, getter, setter,
                    findAccessible(node.getClass(), "isFocused")));
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
                + "screenshot's pixels and the scaled positions reported here.")
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
                + "The click goes through the screen's own mouseClicked handler at the button's "
                + "centre, so screens that do custom hit testing or refuse clicks while busy behave "
                + "exactly as they would for a person clicking.\n\n"
                + "Screens usually change as a result. The reply reports which screen is open "
                + "afterwards, so compare it against the one you started on to see whether the click "
                + "did what you expected.")
            .schema(JsonSchema.object()
                .string("label", "Button label to click, matched case-insensitively. Either an exact "
                    + "match or, failing that, a unique substring match. Prefer this over index.")
                .integer("index", "Button index from client_gui_widgets. Used when label is omitted.")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("GUI control is disabled by permissions.allowPlayerControl "
                        + "in the MCMCP config.");
                }

                final String label = context.getString("label", null);
                final int index = context.getInt("index", -1);
                if (label == null && index < 0) {
                    return ToolResult.error("Pass either 'label' or 'index' to say which button to "
                        + "click. Call client_gui_widgets to see what is available.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
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
                        String screenBefore = screen.getClass().getSimpleName();
                        ScaledResolution buttonResolution = new ScaledResolution(mc);
                        String via = deliverClick(screen, mouseX, mouseY, 0,
                            buttonResolution.getScaledWidth(), buttonResolution.getScaledHeight());

                        JsonObject json = describeScreen(mc.currentScreen);
                        json.addProperty("via", via);
                        json.addProperty("clickedLabel", clickedLabel);
                        json.addProperty("clickedAtX", mouseX);
                        json.addProperty("clickedAtY", mouseY);
                        json.addProperty("screenBefore", screenBefore);
                        json.addProperty("screenChanged",
                            mc.currentScreen == null || !screenBefore.equals(
                                mc.currentScreen.getClass().getSimpleName()));
                        return json;
                    }
                });

                return ToolResult.text("Clicked '" + result.get("clickedLabel").getAsString()
                    + "'. Screen is now " + result.get("screenName").getAsString() + ".")
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
                + "exactly that reason.")
            .schema(JsonSchema.object()
                .integer("x", "Horizontal coordinate.")
                .integer("y", "Vertical coordinate, measured from the top.")
                .enumeration("space", "Coordinate space. 'pixel' (the default) matches a screenshot's "
                    + "own pixels. 'gui' is Minecraft's scaled space, which is what "
                    + "client_gui_widgets reports button positions in.", "pixel", "gui")
                .integer("button", "Mouse button: 0 left, 1 right, 2 middle. Defaults to 0.", 0, 2)
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

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
                        Minecraft mc = Minecraft.getMinecraft();
                        GuiScreen screen = mc.currentScreen;
                        if (screen == null) {
                            throw new IllegalStateException("No screen is open, so there is nothing to "
                                + "click.");
                        }

                        int guiX = "gui".equals(space) ? x : toGuiX(mc, x);
                        int guiY = "gui".equals(space) ? y : toGuiY(mc, y);

                        String screenBefore = screen.getClass().getSimpleName();
                        ScaledResolution resolution = new ScaledResolution(mc);
                        String via = deliverClick(screen, guiX, guiY, mouseButton,
                            resolution.getScaledWidth(), resolution.getScaledHeight());

                        JsonObject json = describeScreen(mc.currentScreen);
                        json.addProperty("via", via);
                        json.addProperty("handlesOwnMouseInput",
                            SyntheticMouse.overridesHandleMouseInput(screen));
                        json.addProperty("clickedGuiX", guiX);
                        json.addProperty("clickedGuiY", guiY);
                        json.addProperty("space", space);
                        json.addProperty("button", mouseButton);
                        json.addProperty("scaledWidth", resolution.getScaledWidth());
                        json.addProperty("scaledHeight", resolution.getScaledHeight());
                        json.addProperty("screenBefore", screenBefore);
                        json.addProperty("screenChanged",
                            mc.currentScreen == null || !screenBefore.equals(
                                mc.currentScreen.getClass().getSimpleName()));
                        return json;
                    }
                });

                return ToolResult.text("Clicked at gui(" + result.get("clickedGuiX").getAsInt() + ", "
                    + result.get("clickedGuiY").getAsInt() + "). Screen is now "
                    + result.get("screenName").getAsString() + ".")
                    .withStructured(result);
            })
            .build());
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
                        field.write(clear ? text : before + text);
                        String resulting = field.read();

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
                + "exactly why the gap is easy to miss.")
            .schema(JsonSchema.object()
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
                        return json;
                    }
                });
                return ToolResult.text("HUD "
                    + (result.get("hudHidden").getAsBoolean() ? "hidden" : "visible")
                    + ", perspective " + result.get("perspective").getAsString() + ".")
                    .withStructured(result);
            })
            .build());
    }
}
