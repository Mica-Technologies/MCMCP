package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Keeps only the named fields of a tile entity's NBT.
 *
 * <p>Checking a district's signal controllers needs two or three fields from each, not the whole tag:
 * a controller's full NBT is kilobytes, and fifty of them would fill a reply with link lists nobody
 * asked for. A path is dot-separated through compounds ({@code "tcCp"}, {@code "Items.id"}); a path
 * that reaches a list applies the rest of itself to each element, unless the next step is an index
 * ({@code "tcPh.ph[0]"} or {@code "tcPh.ph.0"}), which picks one element. An indexed list comes back as
 * an object keyed by index, {@code {"ph":{"0":…}}}, so that two paths into the same list merge and
 * nobody mistakes one element for the whole list.
 *
 * <p>Pure: JSON in, JSON out.
 */
public final class NbtProjection {

    private NbtProjection() {
    }

    /** The fields of {@code nbt} named by {@code paths}, nested as they were; absent paths are left out. */
    public static JsonObject project(JsonObject nbt, List<String> paths) {
        JsonObject out = new JsonObject();
        for (String path : paths) {
            String trimmed = path.trim();
            if (!trimmed.isEmpty()) {
                copy(nbt, out, steps(trimmed), 0);
            }
        }
        return out;
    }

    /** {@code "a.b[2][0].c"} as {@code [a, b, 2, 0, c]}: brackets are another way to write a step. */
    static String[] steps(String path) {
        List<String> steps = new ArrayList<>();
        for (String part : path.split("\\.")) {
            int bracket = part.indexOf('[');
            String head = bracket < 0 ? part : part.substring(0, bracket);
            if (!head.isEmpty()) {
                steps.add(head);
            }
            while (bracket >= 0) {
                int close = part.indexOf(']', bracket);
                if (close < 0) {
                    break;
                }
                steps.add(part.substring(bracket + 1, close).trim());
                bracket = part.indexOf('[', close);
            }
        }
        return steps.toArray(new String[0]);
    }

    private static void copy(JsonObject from, JsonObject into, String[] parts, int at) {
        String key = parts[at];
        JsonElement value = from.get(key);
        if (value == null) {
            return;
        }
        place(value, into, key, parts, at + 1);
    }

    /** Puts what is left of the path through {@code value} into {@code into} under {@code key}. */
    private static void place(JsonElement value, JsonObject into, String key, String[] parts, int next) {
        if (next == parts.length) {
            into.add(key, value);
            return;
        }
        if (value.isJsonObject()) {
            JsonObject child = existingObject(into, key);
            copy(value.getAsJsonObject(), child, parts, next);
            if (child.size() == 0) {
                into.remove(key);
            }
        }
        else if (value.isJsonArray()) {
            JsonArray list = value.getAsJsonArray();
            Integer index = index(parts[next]);
            if (index != null) {
                if (index < 0 || index >= list.size()) {
                    return;
                }
                JsonObject picked = existingObject(into, key);
                place(list.get(index), picked, String.valueOf(index), parts, next + 1);
                if (picked.size() == 0) {
                    into.remove(key);
                }
                return;
            }
            JsonArray projected = new JsonArray();
            for (JsonElement element : list) {
                if (element.isJsonObject()) {
                    JsonObject child = new JsonObject();
                    copy(element.getAsJsonObject(), child, parts, next);
                    projected.add(child);
                }
            }
            into.add(key, projected);
        }
    }

    @Nullable
    private static Integer index(String step) {
        if (step.isEmpty()) {
            return null;
        }
        for (int i = 0; i < step.length(); i++) {
            if (!Character.isDigit(step.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(step);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The shape of a tag and what each part of it costs, so a caller can see what to ask for before
     * paying for it. A primitive is its size in characters; a compound is {@code {"chars", "keys"}}; a
     * list is {@code {"chars", "len"}}, plus {@code "each"} (its first element's shape) when it holds
     * compounds. Below {@code depth} levels of compound a compound shows only its size; a list is not
     * a level.
     */
    public static JsonElement summary(JsonElement value, int depth) {
        int chars = Json.write(value).length();
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            out.addProperty("chars", chars);
            if (depth > 0) {
                JsonObject keys = new JsonObject();
                for (Map.Entry<String, JsonElement> field : value.getAsJsonObject().entrySet()) {
                    keys.add(field.getKey(), summary(field.getValue(), depth - 1));
                }
                out.add("keys", keys);
            }
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray list = value.getAsJsonArray();
            JsonObject out = new JsonObject();
            out.addProperty("chars", chars);
            out.addProperty("len", list.size());
            if (depth > 0 && list.size() > 0 && list.get(0).isJsonObject()) {
                out.add("each", summary(list.get(0), depth));
            }
            return out;
        }
        return new com.google.gson.JsonPrimitive(chars);
    }

    private static JsonObject existingObject(JsonObject into, String key) {
        JsonElement existing = into.get(key);
        if (existing != null && existing.isJsonObject()) {
            return existing.getAsJsonObject();
        }
        JsonObject created = new JsonObject();
        into.add(key, created);
        return created;
    }

    /**
     * Whether a block id matches a filter entry: an exact id ({@code "csm:controller"}) or a
     * namespace or prefix ending in {@code ':'} or {@code '*'} ({@code "csm:"}, {@code "csm:signal_*"}).
     */
    public static boolean matches(String blockId, @Nullable List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (String filter : filters) {
            String f = filter.trim();
            if (f.endsWith("*") ? blockId.startsWith(f.substring(0, f.length() - 1))
                : f.endsWith(":") ? blockId.startsWith(f) : blockId.equals(f)) {
                return true;
            }
        }
        return false;
    }
}
