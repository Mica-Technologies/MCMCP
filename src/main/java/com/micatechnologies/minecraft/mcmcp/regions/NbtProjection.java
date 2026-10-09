package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Keeps only the named fields of a tile entity's NBT.
 *
 * <p>Checking a district's signal controllers needs two or three fields from each, not the whole tag:
 * a controller's full NBT is kilobytes, and fifty of them would fill a reply with link lists nobody
 * asked for. A path is dot-separated through compounds ({@code "tcCp"}, {@code "Items.id"}); a path
 * that reaches a list applies the rest of itself to each element.
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
                copy(nbt, out, trimmed.split("\\."), 0);
            }
        }
        return out;
    }

    private static void copy(JsonObject from, JsonObject into, String[] parts, int at) {
        String key = parts[at];
        JsonElement value = from.get(key);
        if (value == null) {
            return;
        }
        if (at == parts.length - 1) {
            into.add(key, value);
            return;
        }
        if (value.isJsonObject()) {
            JsonObject child = existingObject(into, key);
            copy(value.getAsJsonObject(), child, parts, at + 1);
            if (child.size() == 0) {
                into.remove(key);
            }
        }
        else if (value.isJsonArray()) {
            JsonArray projected = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject child = new JsonObject();
                    copy(element.getAsJsonObject(), child, parts, at + 1);
                    projected.add(child);
                }
            }
            into.add(key, projected);
        }
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
