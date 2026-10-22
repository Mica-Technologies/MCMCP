package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tile entities grouped by block and by the values of the NBT keys asked for, for
 * {@code server_get_tile_entities groupBy} (#124): "which thermostats are set to what" used to be every
 * entity's tag on the wire and a grouping script on the far side.
 *
 * <p>Plain Java: the values are already projected to JSON, so nothing here needs a world.
 */
public final class NbtGroups {

    private static final class Group {
        final String block;
        final JsonObject value;
        long count;
        final JsonArray samples = new JsonArray();

        Group(String block, JsonObject value) {
            this.block = block;
            this.value = value;
        }
    }

    private final int samplesPerGroup;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private long entities;

    /** @param samplesPerGroup positions kept per group, the first ones added */
    public NbtGroups(int samplesPerGroup) {
        this.samplesPerGroup = samplesPerGroup;
    }

    /** One tile entity: its block, its projected NBT and where it is. */
    public void add(String block, JsonObject value, int x, int y, int z) {
        entities++;
        // Compact JSON is a stable key: projection keeps the tag's own key order, and the same tag
        // always writes the same way.
        String key = block + "\u0000" + Json.write(value);
        Group group = groups.get(key);
        if (group == null) {
            group = new Group(block, value);
            groups.put(key, group);
        }
        group.count++;
        if (group.samples.size() < samplesPerGroup) {
            JsonArray pos = new JsonArray();
            pos.add(x);
            pos.add(y);
            pos.add(z);
            group.samples.add(pos);
        }
    }

    public long entities() {
        return entities;
    }

    public int size() {
        return groups.size();
    }

    /** The largest groups first, then in the order first seen; at most {@code limit} of them. */
    public JsonArray toJson(int limit) {
        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> Long.compare(b.count, a.count));
        JsonArray json = new JsonArray();
        for (int i = 0; i < sorted.size() && i < limit; i++) {
            Group group = sorted.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("block", group.block);
            entry.add("value", group.value);
            entry.addProperty("count", group.count);
            if (group.samples.size() > 0) {
                entry.add("samples", group.samples);
            }
            json.add(entry);
        }
        return json;
    }
}
