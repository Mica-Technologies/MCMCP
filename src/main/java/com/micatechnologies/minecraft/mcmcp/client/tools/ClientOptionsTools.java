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
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
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
 * render distance. Options that the game only cycles through (graphics, clouds, GUI scale) are left
 * out; {@code client_gui_open} reaches their screens.
 */
@SideOnly(Side.CLIENT)
public final class ClientOptionsTools {

    private static final String SOUND_PREFIX = "sound_";

    /** Every settable option by the name a caller uses: sound_*, then the float and boolean ones. */
    private static final Map<String, Object> OPTIONS = new LinkedHashMap<>();

    static {
        for (SoundCategory category : SoundCategory.values()) {
            OPTIONS.put(SOUND_PREFIX + category.getName(), category);
        }
        for (GameSettings.Options option : GameSettings.Options.values()) {
            if (option.isFloat() || option.isBoolean()) {
                OPTIONS.put(option.name().toLowerCase(Locale.ROOT), option);
            }
        }
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
            return "'" + name + "' needs a " + (isBoolean(option) ? "boolean." : "number.");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
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

    private static void apply(GameSettings settings, Object option, JsonElement value) {
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

    private static JsonElement value(GameSettings settings, Object option) {
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
