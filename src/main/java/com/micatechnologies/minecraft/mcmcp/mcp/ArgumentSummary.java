package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A tool call's arguments in a line short enough for a log or a stall report, whatever their size.
 *
 * <p>The arguments of a bulk write are megabytes; the ones that say what went wrong are a handful of
 * small values beside it: a mode, an origin, a flag, and how many cells there were (#219, where the
 * answer was "none"). So small values are written out, and a long list or a large object is written as
 * its size.
 *
 * <p>Pure: no Minecraft class. Costs a walk of the top-level keys, never of a list's contents.
 */
public final class ArgumentSummary {

    /** Longest string value written out. */
    private static final int MAX_STRING = 48;
    /** Most elements of a list, or keys of an object, written out rather than counted. */
    private static final int MAX_INLINE = 6;

    private ArgumentSummary() {
    }

    /** {@code "server_set_blocks {mode=palette, cells=[0 items], origin={"x":440,"y":78,"z":-2294}}"}. */
    public static String of(@Nullable String tool, @Nullable JsonObject arguments, int maxChars) {
        StringBuilder out = new StringBuilder(tool == null ? "a call" : tool);
        if (arguments != null && arguments.size() > 0) {
            out.append(" {");
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append(entry.getKey()).append('=').append(value(entry.getValue()));
                if (out.length() > maxChars) {
                    break;
                }
            }
            out.append('}');
        }
        if (out.length() > maxChars) {
            out.setLength(Math.max(0, maxChars - 1));
            out.append('…');
        }
        return out.toString();
    }

    private static String value(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonPrimitive()) {
            String text = element.getAsJsonPrimitive().isString() ? element.getAsString() : element.toString();
            return text.length() > MAX_STRING ? text.substring(0, MAX_STRING) + "…(" + text.length() + " chars)" : text;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            return array.size() <= MAX_INLINE && flat(array) ? Json.write(array) : "[" + array.size() + " items]";
        }
        JsonObject object = element.getAsJsonObject();
        if (object.size() <= MAX_INLINE) {
            boolean flat = true;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                flat &= small(entry.getValue());
            }
            if (flat) {
                return Json.write(object);
            }
        }
        return "{" + object.size() + " keys}";
    }

    private static boolean flat(JsonArray array) {
        for (JsonElement item : array) {
            if (!small(item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean small(JsonElement element) {
        return element.isJsonNull() || element.isJsonPrimitive()
            && (!element.getAsJsonPrimitive().isString() || element.getAsString().length() <= MAX_STRING);
    }
}
