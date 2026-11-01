package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.ArgumentNames;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Reading and setting the client's game options without clicking through the Options screens.
 *
 * <p>Muting a test client is the common case: agents run games the person can hear, and before
 * this the only route was quitting the client and editing options.txt (issue #47).
 *
 * <p>Values go through {@link GameSettings}' own setters, so a change has the side effects the
 * slider or button would have had: the sound handler is told about a volume, the renderer about a
 * render distance.
 *
 * <p>Options the game only cycles through (GUI scale, attack indicator, graphics, clouds; #164) are
 * set by index or label and reached by cycling {@code setOptionValue} one step at a time until the
 * option reads back as wanted, so each step has the side effect a click on the button would have.
 * GUI scale also re-initialises the open screen at the new {@code ScaledResolution}, as Video
 * Settings does after its own click.
 */
@SideOnly(Side.CLIENT)
public final class ClientOptionsTools {

    private static final String SOUND_PREFIX = "sound_";

    /** Every settable option by the name a caller uses: sound_*, then the float and boolean ones. */
    private static final Map<String, Object> OPTIONS = new LinkedHashMap<>();

    /**
     * An option the game cycles through rather than slides or toggles. Labels are by index, in the
     * order the game cycles them, so a caller can pass either.
     */
    static final class Cycled {
        final GameSettings.Options option;
        final String[] labels;
        final ToIntFunction<GameSettings> reader;

        Cycled(GameSettings.Options option, ToIntFunction<GameSettings> reader, String... labels) {
            this.option = option;
            this.reader = reader;
            this.labels = labels;
        }
    }

    static {
        for (SoundCategory category : SoundCategory.values()) {
            OPTIONS.put(SOUND_PREFIX + category.getName(), category);
        }
        for (GameSettings.Options option : GameSettings.Options.values()) {
            if (option.isFloat() || option.isBoolean()) {
                OPTIONS.put(option.name().toLowerCase(Locale.ROOT), option);
            }
        }
        cycled(new Cycled(GameSettings.Options.GUI_SCALE, s -> s.guiScale,
            "auto", "small", "normal", "large"));
        cycled(new Cycled(GameSettings.Options.ATTACK_INDICATOR, s -> s.attackIndicator,
            "off", "crosshair", "hotbar"));
        cycled(new Cycled(GameSettings.Options.GRAPHICS, s -> s.fancyGraphics ? 1 : 0,
            "fast", "fancy"));
        cycled(new Cycled(GameSettings.Options.RENDER_CLOUDS, s -> s.clouds, "off", "fast", "fancy"));
        cycled(new Cycled(GameSettings.Options.AMBIENT_OCCLUSION, s -> s.ambientOcclusion,
            "off", "minimum", "maximum"));
        cycled(new Cycled(GameSettings.Options.PARTICLES, s -> s.particleSetting,
            "all", "decreased", "minimal"));
        cycled(new Cycled(GameSettings.Options.CHAT_VISIBILITY,
            s -> s.chatVisibility.getChatVisibility(), "shown", "commands", "hidden"));
        cycled(new Cycled(GameSettings.Options.MAIN_HAND,
            s -> s.mainHand == EnumHandSide.LEFT ? 0 : 1, "left", "right"));
        cycled(new Cycled(GameSettings.Options.NARRATOR, s -> s.narrator,
            "off", "all", "chat", "system"));
    }

    private static void cycled(Cycled option) {
        OPTIONS.put(option.option.name().toLowerCase(Locale.ROOT), option);
    }

    private ClientOptionsTools() {
    }

    public static void register() {
        JsonObject valueSchema = new JsonObject();
        valueSchema.addProperty("type", "object");
        JsonObject values = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("number");
        types.add("boolean");
        types.add("string");
        values.add("type", types);
        valueSchema.add("additionalProperties", values);
        valueSchema.addProperty("description", "Options to change, by name: {\"sound_master\": 0, "
            + "\"render_distance\": 8}.");

        McpRegistry.registerTool(McpTool.named("client_options")
            .title("Game options")
            .description("Read or change this client's game options without opening the Options "
                + "screens. Call with no arguments to list every option with its value and range.\n\n"
                + "Volumes are sound_<category> from 0 to 1 (sound_master, sound_music, sound_record, "
                + "sound_weather, sound_block, sound_hostile, sound_neutral, sound_player, "
                + "sound_ambient, sound_voice); sound_master 0 mutes the client. Other options use "
                + "the game's names in lower case: render_distance, fov, gamma, sensitivity, "
                + "framerate_limit, view_bobbing, auto_jump, show_subtitles, and so on.\n\n"
                + "Cycled options take an index or a label: gui_scale (0 auto, 1 small, 2 normal, "
                + "3 large; re-initialises the open screen), attack_indicator (off, crosshair, "
                + "hotbar), graphics, render_clouds, ambient_occlusion, particles, chat_visibility, "
                + "main_hand, narrator. The listing shows them as [index, label, [labels]].\n\n"
                + "A change applies at once and lasts until the game restarts, unless 'persist' is "
                + "true or something else saves options first, as the Options screens do.")
            .schema(JsonSchema.object()
                .property("set", valueSchema)
                .bool("persist", "Also write the options to options.txt so they survive a restart. "
                    + "Default false.")
                .build())
            .clientOnly()
            .idempotent()
            .closedWorld()
            .handler(context -> {
                JsonElement raw = context.getArguments().get("set");
                final Map<String, JsonElement> changes = new LinkedHashMap<>();
                if (raw != null && !raw.isJsonNull()) {
                    if (!raw.isJsonObject()) {
                        return ToolResult.error("'set' must be an object of option names to values, "
                            + "such as {\"sound_master\": 0}.");
                    }
                    for (Map.Entry<String, JsonElement> entry : raw.getAsJsonObject().entrySet()) {
                        String problem = check(entry.getKey(), entry.getValue());
                        if (problem != null) {
                            return ToolResult.error(problem + " Nothing was changed.");
                        }
                        changes.put(entry.getKey(), entry.getValue());
                    }
                }
                final boolean persist = context.getBoolean("persist", false);
                if (!changes.isEmpty() && !McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Changing options is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        GameSettings settings = Minecraft.getMinecraft().gameSettings;
                        JsonObject json = new JsonObject();
                        if (changes.isEmpty()) {
                            JsonObject all = new JsonObject();
                            for (Map.Entry<String, Object> option : OPTIONS.entrySet()) {
                                all.add(option.getKey(), describe(settings, option.getValue()));
                            }
                            json.add("options", all);
                            return json;
                        }
                        JsonObject changed = new JsonObject();
                        for (Map.Entry<String, JsonElement> change : changes.entrySet()) {
                            Object option = OPTIONS.get(change.getKey());
                            JsonObject entry = new JsonObject();
                            entry.add("old", value(settings, option));
                            apply(settings, option, change.getValue());
                            entry.add("new", value(settings, option));
                            changed.add(change.getKey(), entry);
                        }
                        if (persist) {
                            settings.saveOptions();
                        }
                        json.add("changed", changed);
                        json.addProperty("persisted", persist);
                        if (changes.containsKey("chat_visibility")
                            && settings.chatVisibility == EntityPlayer.EnumChatVisibility.HIDDEN) {
                            json.addProperty("warning", "With chat hidden the server drops every "
                                + "command and chat message this player sends, so client_send_chat "
                                + "and client_run_commands refuse until it is shown again. To keep "
                                + "chat out of screenshots, chat_opacity 0 hides it and leaves "
                                + "commands working.");
                        }
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    /** What is wrong with setting {@code name} to {@code value}, or null if nothing is. */
    private static String check(String name, JsonElement value) {
        Object option = OPTIONS.get(name);
        if (option == null) {
            String closest = ArgumentNames.closest(name, OPTIONS.keySet());
            return "No option '" + name + "'" + (closest == null ? "." : "; did you mean '" + closest
                + "'?") + " Call client_options with no arguments to list them.";
        }
        if (value == null || !value.isJsonPrimitive()) {
            return "'" + name + "' needs a " + (isBoolean(option) ? "boolean."
                : option instanceof Cycled ? "label or index." : "number.");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (option instanceof Cycled) {
            Cycled cycled = (Cycled) option;
            return cycledIndex(cycled.labels, primitive.getAsString()) >= 0 ? null
                : "'" + name + "' is one of " + String.join(", ", cycled.labels)
                    + ", or an index from 0 to " + (cycled.labels.length - 1) + "; " + value
                    + " is neither.";
        }
        if (isBoolean(option)) {
            if (primitive.isBoolean()
                || "true".equalsIgnoreCase(primitive.getAsString())
                || "false".equalsIgnoreCase(primitive.getAsString())) {
                return null;
            }
            return "'" + name + "' is on or off; pass true or false.";
        }
        double number;
        try {
            number = primitive.getAsDouble();
        }
        catch (NumberFormatException e) {
            return "'" + name + "' needs a number.";
        }
        float min = option instanceof SoundCategory ? 0F : ((GameSettings.Options) option).getValueMin();
        float max = option instanceof SoundCategory ? 1F : ((GameSettings.Options) option).getValueMax();
        if (Double.isNaN(number) || number < min || number > max) {
            return "'" + name + "' runs from " + trim(min) + " to " + trim(max) + "; " + value
                + " is outside that.";
        }
        return null;
    }

    private static boolean isBoolean(Object option) {
        return option instanceof GameSettings.Options && ((GameSettings.Options) option).isBoolean();
    }

    /** The index {@code value} names among {@code labels}, by label or by number, or -1. */
    static int cycledIndex(String[] labels, String value) {
        String text = value.trim();
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equalsIgnoreCase(text)) {
                return index;
            }
        }
        try {
            double number = Double.parseDouble(text);
            int index = (int) number;
            return index == number && index >= 0 && index < labels.length ? index : -1;
        }
        catch (NumberFormatException notNumber) {
            return -1;
        }
    }

    private static void apply(GameSettings settings, Object option, JsonElement value) {
        if (option instanceof Cycled) {
            Cycled cycled = (Cycled) option;
            int wanted = cycledIndex(cycled.labels, value.getAsString());
            int before = cycled.reader.applyAsInt(settings);
            // One step at a time, so every step has the button's side effects. Bounded: an option
            // the game refuses to move (the narrator, with no narrator available) stops here, and
            // its 'new' value says so.
            for (int step = 0; step < cycled.labels.length
                && cycled.reader.applyAsInt(settings) != wanted; step++) {
                settings.setOptionValue(cycled.option, 1);
            }
            if (cycled.option == GameSettings.Options.GUI_SCALE
                && cycled.reader.applyAsInt(settings) != before) {
                reinitialiseScreen();
            }
            return;
        }
        if (option instanceof SoundCategory) {
            settings.setSoundLevel((SoundCategory) option, value.getAsFloat());
            return;
        }
        GameSettings.Options gameOption = (GameSettings.Options) option;
        if (gameOption.isBoolean()) {
            boolean wanted = value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
                : Boolean.parseBoolean(value.getAsString());
            // Boolean options are toggles; setOptionValue flips one and runs its side effects.
            if (settings.getOptionOrdinalValue(gameOption) != wanted) {
                settings.setOptionValue(gameOption, 1);
            }
            return;
        }
        settings.setOptionFloatValue(gameOption, gameOption.snapToStepClamp(value.getAsFloat()));
    }

    /**
     * Lays the open screen out again at the current {@code ScaledResolution}, as Video Settings does
     * after a GUI scale click. Without it the screen keeps the old size until something resizes it.
     */
    private static void reinitialiseScreen() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null) {
            ScaledResolution resolution = new ScaledResolution(mc);
            mc.currentScreen.setWorldAndResolution(mc, resolution.getScaledWidth(),
                resolution.getScaledHeight());
        }
    }

    private static JsonElement value(GameSettings settings, Object option) {
        if (option instanceof Cycled) {
            Cycled cycled = (Cycled) option;
            int index = cycled.reader.applyAsInt(settings);
            JsonArray json = new JsonArray();
            json.add(index);
            json.add(index >= 0 && index < cycled.labels.length ? cycled.labels[index] : "?");
            return json;
        }
        if (option instanceof SoundCategory) {
            return new JsonPrimitive(settings.getSoundLevel((SoundCategory) option));
        }
        GameSettings.Options gameOption = (GameSettings.Options) option;
        if (gameOption.isBoolean()) {
            return new JsonPrimitive(settings.getOptionOrdinalValue(gameOption));
        }
        return new JsonPrimitive(trim(settings.getOptionFloatValue(gameOption)));
    }

    /** A boolean as its value; a number as [value, min, max], which is all a caller needs to set it. */
    private static JsonElement describe(GameSettings settings, Object option) {
        if (isBoolean(option)) {
            return value(settings, option);
        }
        if (option instanceof Cycled) {
            JsonArray json = value(settings, option).getAsJsonArray();
            JsonArray labels = new JsonArray();
            for (String label : ((Cycled) option).labels) {
                labels.add(label);
            }
            json.add(labels);
            return json;
        }
        JsonArray json = new JsonArray();
        json.add(value(settings, option));
        if (option instanceof SoundCategory) {
            json.add(0);
            json.add(1);
        }
        else {
            json.add(trim(((GameSettings.Options) option).getValueMin()));
            json.add(trim(((GameSettings.Options) option).getValueMax()));
        }
        return json;
    }

    /** 0.5 rather than 0.5000000298023224, and 8 rather than 8.0. */
    private static Number trim(float value) {
        if (value == Math.rint(value)) {
            return (long) value;
        }
        return Math.round(value * 1000D) / 1000D;
    }
}
