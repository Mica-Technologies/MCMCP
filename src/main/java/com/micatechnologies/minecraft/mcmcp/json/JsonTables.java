package com.micatechnologies.minecraft.mcmcp.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A list of objects as {@code {columns, rows}}: each key written once, not once per item.
 *
 * <p>Tile entities, entities, inventory slots, profile rows and search hits come back as dozens to
 * hundreds of objects with the same keys, and the keys are often most of the bytes: {@code "x":},
 * {@code "y":}, {@code "z":}, {@code "distance":} on every one. Every byte of a reply is paid for in a
 * model's context, so a big list is sent as a header and arrays. Nothing is dropped: a key an item
 * does not have is {@code null} in its row, and a value that is itself an object (a tile entity's
 * NBT) stays whole in its cell.
 *
 * <p>Short lists stay lists, since below {@link #MIN_ROWS} the header costs more than it saves. A
 * test script reads either shape the same way: its paths treat a table as its rows.
 *
 * <p>Pure, and unit-tested.
 */
public final class JsonTables {

    /** Fewer items than this are left as a list. */
    public static final int MIN_ROWS = 3;

    private JsonTables() {
    }

    /** The list as a table when it is a list of at least {@link #MIN_ROWS} objects; else the list. */
    public static JsonElement table(JsonArray list) {
        if (list.size() < MIN_ROWS) {
            return list;
        }
        List<String> columns = new ArrayList<>();
        for (JsonElement item : list) {
            if (!item.isJsonObject()) {
                return list;
            }
            for (Map.Entry<String, JsonElement> entry : item.getAsJsonObject().entrySet()) {
                if (!columns.contains(entry.getKey())) {
                    columns.add(entry.getKey());
                }
            }
        }
        JsonArray rows = new JsonArray();
        for (JsonElement item : list) {
            JsonObject object = item.getAsJsonObject();
            JsonArray row = new JsonArray();
            for (String column : columns) {
                JsonElement value = object.get(column);
                row.add(value == null ? JsonNull.INSTANCE : value);
            }
            rows.add(row);
        }
        JsonObject table = new JsonObject();
        table.add("columns", Json.arrayOfStrings(columns));
        table.add("rows", rows);
        return table;
    }

    /**
     * Each list directly inside {@code object} as a table, in place, and the object back. Not
     * recursive: a list nested in a cell is data (a chest's NBT items), and keeps its own shape.
     */
    public static JsonObject tabulate(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : new ArrayList<>(object.entrySet())) {
            if (entry.getValue().isJsonArray()) {
                object.add(entry.getKey(), table(entry.getValue().getAsJsonArray()));
            }
        }
        return object;
    }

    /** Whether a value is a table this class made. */
    public static boolean isTable(JsonElement value) {
        return value != null && value.isJsonObject() && value.getAsJsonObject().size() == 2
            && value.getAsJsonObject().has("columns") && value.getAsJsonObject().get("columns").isJsonArray()
            && value.getAsJsonObject().has("rows") && value.getAsJsonObject().get("rows").isJsonArray();
    }

    /** A table back as its list of objects, without the nulls it filled in. */
    public static JsonArray rows(JsonElement table) {
        JsonArray columns = table.getAsJsonObject().getAsJsonArray("columns");
        JsonArray list = new JsonArray();
        for (JsonElement row : table.getAsJsonObject().getAsJsonArray("rows")) {
            JsonObject object = new JsonObject();
            JsonArray cells = row.getAsJsonArray();
            for (int i = 0; i < columns.size() && i < cells.size(); i++) {
                if (!cells.get(i).isJsonNull()) {
                    object.add(columns.get(i).getAsString(), cells.get(i));
                }
            }
            list.add(object);
        }
        return list;
    }
}
