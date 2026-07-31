package com.micatechnologies.minecraft.mcmcp.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Small helpers over Gson for building and reading the JSON-RPC / MCP wire format.
 *
 * <p>Gson rather than Jackson or a newer Gson on purpose: Minecraft 1.12.2 already puts
 * {@code com.google.gson:gson:2.8.0} on the classpath, so this costs zero shaded bytes and cannot
 * split-package with the copy Forge has already loaded. The flip side is that we are pinned to the
 * 2.8.0 API — notably {@code JsonParser.parseString} (2.8.6+) and {@code JsonObject.keySet} (2.8.1+)
 * do not exist here, which is why this class exists at all rather than being called inline.
 *
 * <p>Every accessor is null- and type-tolerant: MCP messages arrive from a client we do not
 * control, so "field missing" and "field is the wrong type" are ordinary inputs, not bugs. Reading
 * a field the wrong way returns the supplied default instead of throwing; validation that a caller
 * genuinely requires is done explicitly via the {@code require*} methods, which raise the
 * JSON-RPC {@code -32602 Invalid params} error the spec asks for.
 */
public final class Json {

    /** Pretty printer used for config dumps and log output; the wire format uses {@link #compact()}. */
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * HTML escaping is off deliberately. Gson escapes {@code < > & = '} into {@code <}-style
     * sequences by default, which is valid JSON but mangles the tool descriptions, chat text and
     * file paths that make up most of what MCMCP sends, and bloats every payload for no benefit —
     * this JSON goes to an MCP client over HTTP, never into an HTML document.
     */
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private static final JsonParser PARSER = new JsonParser();

    private Json() {
    }

    public static Gson compact() {
        return COMPACT;
    }

    public static Gson pretty() {
        return PRETTY;
    }

    /** Serialises {@code element} to the compact wire form. {@code null} becomes the literal {@code null}. */
    public static String write(@Nullable JsonElement element) {
        return COMPACT.toJson(element == null ? JsonNull.INSTANCE : element);
    }

    public static String writePretty(@Nullable JsonElement element) {
        return PRETTY.toJson(element == null ? JsonNull.INSTANCE : element);
    }

    /**
     * Parses {@code text}, returning {@code null} rather than throwing when it is not valid JSON.
     * Callers on the transport boundary turn that {@code null} into a {@code -32700 Parse error}.
     */
    @Nullable
    public static JsonElement parse(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            JsonElement parsed = PARSER.parse(text);
            return parsed == null || parsed.isJsonNull() ? null : parsed;
        }
        catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonArray arr() {
        return new JsonArray();
    }

    /** Builds a one-entry object; the common shape for MCP params and single-field results. */
    public static JsonObject obj(String key, @Nullable JsonElement value) {
        JsonObject object = new JsonObject();
        object.add(key, value == null ? JsonNull.INSTANCE : value);
        return object;
    }

    public static JsonObject obj(String key, @Nullable String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    public static JsonArray arrayOf(Collection<? extends JsonElement> elements) {
        JsonArray array = new JsonArray();
        for (JsonElement element : elements) {
            array.add(element == null ? JsonNull.INSTANCE : element);
        }
        return array;
    }

    public static JsonArray arrayOfStrings(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(new JsonPrimitive(value));
        }
        return array;
    }

    // ------------------------------------------------------------------
    // Tolerant readers
    // ------------------------------------------------------------------

    /** Returns the object at {@code key}, or {@code null} if absent or not an object. */
    @Nullable
    public static JsonObject getObject(@Nullable JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** Like {@link #getObject} but never null — callers that only read from the result. */
    public static JsonObject getObjectOrEmpty(@Nullable JsonObject parent, String key) {
        JsonObject found = getObject(parent, key);
        return found == null ? new JsonObject() : found;
    }

    @Nullable
    public static JsonArray getArray(@Nullable JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    /**
     * Returns the string at {@code key}, or {@code fallback}.
     *
     * <p>Numbers and booleans are accepted and stringified: MCP clients are frequently LLM-driven
     * and send {@code {"count": 5}} where the schema said string just as often as the reverse.
     * Being lenient here turns a hard protocol error into a tool that simply works.
     */
    @Nullable
    public static String getString(@Nullable JsonObject parent, String key, @Nullable String fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        return element.getAsString();
    }

    @Nullable
    public static String getString(@Nullable JsonObject parent, String key) {
        return getString(parent, key, null);
    }

    /**
     * Returns the number at {@code key} as an int, or {@code fallback}.
     *
     * <p>Strings that parse as numbers are accepted for the same reason {@link #getString} accepts
     * numbers. A value that is neither yields {@code fallback} rather than an exception.
     */
    public static int getInt(@Nullable JsonObject parent, String key, int fallback) {
        Double value = readNumber(parent, key);
        return value == null ? fallback : (int) Math.round(value);
    }

    public static long getLong(@Nullable JsonObject parent, String key, long fallback) {
        Double value = readNumber(parent, key);
        return value == null ? fallback : Math.round(value);
    }

    public static double getDouble(@Nullable JsonObject parent, String key, double fallback) {
        Double value = readNumber(parent, key);
        return value == null ? fallback : value;
    }

    @Nullable
    private static Double readNumber(@Nullable JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return primitive.getAsDouble();
        }
        if (primitive.isString()) {
            try {
                return Double.valueOf(primitive.getAsString().trim());
            }
            catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Returns the boolean at {@code key}, accepting the strings {@code "true"}/{@code "false"} too. */
    public static boolean getBoolean(@Nullable JsonObject parent, String key, boolean fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            String text = primitive.getAsString().trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }

    /** Reads an array of strings, skipping non-primitive entries. Never null. */
    public static List<String> getStringList(@Nullable JsonObject parent, String key) {
        List<String> values = new ArrayList<>();
        JsonArray array = getArray(parent, key);
        if (array == null) {
            return values;
        }
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    /** True when {@code key} is present and not JSON null. */
    public static boolean has(@Nullable JsonObject parent, String key) {
        if (parent == null) {
            return false;
        }
        JsonElement element = parent.get(key);
        return element != null && !element.isJsonNull();
    }
}
