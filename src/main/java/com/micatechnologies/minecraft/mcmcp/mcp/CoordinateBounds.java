package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.regions.WorldBounds;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Refuses a tool call whose arguments name a position no world has, before its handler sees them
 * (#219).
 *
 * <p>Every tool that takes a position works something out from it: a box, a set of chunks, a loop
 * from one corner to the other. Each of those is as large as the position is far, and a position at
 * the end of {@code int} breaks them in ways no handler was written for: {@code x <= maxX} is never
 * false when {@code maxX} is {@code Integer.MAX_VALUE}, {@code max - min + 1} wraps to a small number
 * that passes a volume limit, and a box round a corner there is the whole integer range. One such box
 * hung a live server, and a read of the tools found the same in a dozen more. So the check is made
 * once, here, for every tool, by the names positions go by; a handler still checks what is particular
 * to it.
 *
 * <p>What is checked: a number under a name a position goes by ({@code x}, {@code toZ}, ...), and
 * every number inside a list or object that holds positions ({@code cells}, {@code box}, {@code to},
 * ...), at any depth. x and z must be within {@link WorldBounds#MAX_HORIZONTAL} of 0; y within
 * {@link #MAX_VERTICAL}, which is wide enough for an offset or a deliberate out-of-range probe and
 * far too small to wrap anything. Numbers given as strings count, since the readers accept them.
 *
 * <p>Pure: no Minecraft class.
 */
public final class CoordinateBounds {

    /** The farthest a y may be from 0. Not the world's height: tools clamp or refuse that themselves. */
    public static final int MAX_VERTICAL = 4_096;

    /** Arguments that are one x or z. */
    private static final Set<String> HORIZONTAL = new HashSet<>(Arrays.asList(
        "x", "z", "toX", "toZ", "centerX", "centerZ", "lookAtX", "lookAtZ"));

    /** Arguments that are one y. */
    private static final Set<String> VERTICAL = new HashSet<>(Arrays.asList(
        "y", "toY", "lookAtY", "floorY", "y_min", "y_max"));

    /** Arguments every number inside which is a coordinate, an offset from one, or smaller still. */
    private static final Set<String> HOLDS_POSITIONS = new HashSet<>(Arrays.asList(
        "to", "from", "origin", "at", "look_at", "cells", "positions", "seeds", "box", "boxes", "skipBoxes",
        "hold", "loadBoxes", "openZones", "probes", "openings", "doors", "floors", "columns", "line", "shell"));

    /** Arguments whose contents are not this call's to read: block and entity data, passed through. */
    private static final Set<String> OPAQUE = new HashSet<>(Arrays.asList("nbt", "tag", "data", "vars", "expect"));

    /** How deep the arguments are walked: far past any real call, short of a stack a hostile one could fill. */
    private static final int MAX_DEPTH = 24;

    private CoordinateBounds() {
    }

    /** Why the call is refused, naming the argument and its value, or null when every position is in range. */
    @Nullable
    public static String refusal(@Nullable JsonObject arguments) {
        return arguments == null ? null : walk(arguments, "", false, 0);
    }

    @Nullable
    private static String walk(JsonElement element, String path, boolean positions, int depth) {
        if (depth > MAX_DEPTH) {
            return "The arguments are nested more than " + MAX_DEPTH + " deep at '" + path + "'. Nothing was done.";
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                String at = path.isEmpty() ? key : path + "." + key;
                // A tile entity's data is some mod's, with names of its own: a long called 'to' there
                // is not a position of this call's.
                if (OPAQUE.contains(key)) {
                    continue;
                }
                String refusal;
                if (value.isJsonPrimitive()) {
                    boolean vertical = VERTICAL.contains(key);
                    refusal = vertical || HORIZONTAL.contains(key) || positions || HOLDS_POSITIONS.contains(key)
                        ? check(value.getAsJsonPrimitive(), at, vertical) : null;
                }
                else {
                    refusal = walk(value, at, positions || HOLDS_POSITIONS.contains(key), depth + 1);
                }
                if (refusal != null) {
                    return refusal;
                }
            }
            return null;
        }
        if (element.isJsonArray()) {
            int index = 0;
            for (JsonElement item : element.getAsJsonArray()) {
                String refusal = item.isJsonPrimitive()
                    ? positions ? check(item.getAsJsonPrimitive(), path + "[" + index + "]", false) : null
                    : walk(item, path + "[" + index + "]", positions, depth + 1);
                if (refusal != null) {
                    return refusal;
                }
                index++;
            }
        }
        return null;
    }

    @Nullable
    private static String check(JsonPrimitive value, String at, boolean vertical) {
        double number;
        if (value.isNumber()) {
            number = value.getAsDouble();
        }
        else if (value.isString()) {
            try {
                number = Double.parseDouble(value.getAsString().trim());
            }
            catch (NumberFormatException notANumber) {
                return null;
            }
        }
        else {
            return null;
        }
        long limit = vertical ? MAX_VERTICAL : WorldBounds.MAX_HORIZONTAL;
        // Written so that NaN, which is neither inside nor outside any range, is refused too.
        if (number >= -limit && number <= limit) {
            return null;
        }
        return "'" + at + "' is " + value.getAsString() + ", which is outside the world: "
            + (vertical ? "a y must be within " + limit + " of 0" : "x and z must be within " + limit + " of 0")
            + ". Nothing was done.";
    }
}
