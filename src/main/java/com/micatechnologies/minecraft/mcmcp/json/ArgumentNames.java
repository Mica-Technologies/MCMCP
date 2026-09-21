package com.micatechnologies.minecraft.mcmcp.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Checks a tool call's argument names against the tool's published input schema.
 *
 * <h2>Why a misspelled argument is refused rather than ignored</h2>
 *
 * JSON Schema's default is to allow properties it does not name, and MCMCP used to inherit it. The
 * failure that produced is the quiet kind: {@code client_world_create} called with
 * {@code world_type: "flat"} — the real name is {@code worldType} — built a default-terrain world,
 * reported success, and every placement, screenshot and measurement after it was made in the wrong
 * world. Nothing an agent reads says "that argument was not used". A refusal naming the argument it
 * probably meant costs one retry.
 *
 * <p>Model-generated arguments get names wrong in predictable ways — snake_case for camelCase and
 * the reverse, a dropped or added plural — so {@link #closest} compares with case, underscores and
 * hyphens stripped before falling back to edit distance.
 *
 * <p>Only top-level names are checked. Nested objects are validated by the tools that read them,
 * and a schema that declares no properties at all takes no arguments, so anything passed to it is
 * unknown.
 */
public final class ArgumentNames {

    private ArgumentNames() {
    }

    /**
     * The argument names in {@code arguments} that {@code schema} does not declare, in call order.
     */
    public static List<String> unknown(JsonObject schema, JsonObject arguments) {
        List<String> unknown = new ArrayList<>();
        JsonObject properties = Json.getObjectOrEmpty(schema, "properties");
        for (Map.Entry<String, JsonElement> argument : arguments.entrySet()) {
            if (!properties.has(argument.getKey())) {
                unknown.add(argument.getKey());
            }
        }
        return unknown;
    }

    /** The names {@code schema} declares, in declaration order. */
    public static List<String> declared(JsonObject schema) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonElement> property : Json.getObjectOrEmpty(schema, "properties").entrySet()) {
            names.add(property.getKey());
        }
        return names;
    }

    /**
     * The refusal for a call to {@code tool} that passed undeclared arguments, or null when every
     * argument is declared.
     */
    @Nullable
    public static String describeUnknown(String tool, JsonObject schema, JsonObject arguments) {
        List<String> unknown = unknown(schema, arguments);
        if (unknown.isEmpty()) {
            return null;
        }
        List<String> valid = declared(schema);
        StringBuilder message = new StringBuilder();
        for (String name : unknown) {
            message.append(message.length() == 0 ? "" : " ")
                .append("Unknown argument '").append(name).append("' for ").append(tool);
            String suggestion = closest(name, valid);
            if (suggestion != null) {
                message.append(" (did you mean '").append(suggestion).append("'?)");
            }
            message.append('.');
        }
        message.append(" Nothing was done. ");
        message.append(valid.isEmpty()
            ? tool + " takes no arguments."
            : "Valid arguments: " + String.join(", ", valid) + ".");
        return message.toString();
    }

    /**
     * The declared name {@code name} was most likely meant to be, or null if none is close.
     *
     * <p>An exact match once case, underscores and hyphens are ignored wins outright; otherwise the
     * nearest by edit distance, provided it is within a third of the name's length (at least two).
     * Further than that and a suggestion is more likely to mislead than help.
     */
    @Nullable
    public static String closest(String name, Collection<String> candidates) {
        String normalised = normalise(name);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            String other = normalise(candidate);
            if (other.equals(normalised)) {
                return candidate;
            }
            int distance = editDistance(normalised, other);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        int allowed = Math.max(2, normalised.length() / 3);
        return bestDistance <= allowed ? best : null;
    }

    private static String normalise(String name) {
        return name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
