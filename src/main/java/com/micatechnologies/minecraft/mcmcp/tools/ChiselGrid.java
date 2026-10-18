package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

/**
 * Drawing the bits of a Chisels &amp; Bits block from a palette and grid strings: what
 * {@code client_chisel_block} and {@code server_chisel_block} both take (#97).
 *
 * <p>Common code, and no C&amp;B class is named, like {@link ChiselNbt}.
 */
public final class ChiselGrid {

    /** The schema type of a layers entry: an array of row strings. */
    public static final JsonPrimitive STRING_ARRAY = new JsonPrimitive("array");

    public static final String[] FACES = {"north", "south", "west", "east", "up", "down"};

    private ChiselGrid() {
    }

    /**
     * The palette argument as states, with '.' for air. Each state goes through its numeric id and
     * back: a bit is stored as that id, so a property it cannot hold (a stair's shape) would never
     * read back.
     *
     * @throws IllegalArgumentException naming the key that is wrong
     */
    public static Map<Character, IBlockState> palette(JsonObject args) {
        Map<Character, IBlockState> keys = new HashMap<>();
        keys.put('.', Blocks.AIR.getDefaultState());
        JsonObject given = Json.getObjectOrEmpty(args, "palette");
        for (Map.Entry<String, JsonElement> entry : given.entrySet()) {
            String key = entry.getKey();
            if (key.length() != 1 || key.equals(".") || key.equals(" ")) {
                throw new IllegalArgumentException("Palette key '" + key + "' must be one character other "
                    + "than '.' or ' '.");
            }
            if (!entry.getValue().isJsonPrimitive()) {
                throw new IllegalArgumentException("Palette key '" + key + "' must name a block state.");
            }
            try {
                IBlockState state = ChiselNbt.parseState(entry.getValue().getAsString());
                keys.put(key.charAt(0), Block.getStateById(Block.getStateId(state)));
            }
            catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Palette key '" + key + "': " + e.getMessage());
            }
        }
        return keys;
    }

    /** The distinct states of {@code cells} and each cell's index into them, as {@link ChiselNbt#build} takes. */
    public static int[] index(IBlockState[] cells, List<IBlockState> states) {
        int[] indices = new int[cells.length];
        Map<IBlockState, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            Integer index = indexOf.get(cells[i]);
            if (index == null) {
                index = states.size();
                indexOf.put(cells[i], index);
                states.add(cells[i]);
            }
            indices[i] = index;
        }
        return indices;
    }

    /** Bits per state, named, for a result. */
    public static JsonObject counts(List<IBlockState> states, int[] indices) {
        int[] tally = new int[states.size()];
        for (int index : indices) {
            tally[index]++;
        }
        JsonObject counts = new JsonObject();
        for (int i = 0; i < states.size(); i++) {
            counts.addProperty(ChiselNbt.stateName(states.get(i)), tally[i]);
        }
        return counts;
    }

    @Nullable
    public static int[] intTriple(JsonObject arguments, String name) {
        JsonElement element = arguments.get(name);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            return null;
        }
        int[] values = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement value = element.getAsJsonArray().get(i);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            values[i] = value.getAsInt();
        }
        return values;
    }

    public static JsonObject paletteSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "Single-character keys to block states, e.g. {\"#\": "
            + "\"minecraft:stone\", \"r\": \"minecraft:wool[color=red]\"}. '.' and ' ' are "
            + "reserved.");
        schema.add("additionalProperties", Json.obj("type",
            new JsonPrimitive("string")));
        return schema;
    }

    /** Applies layers, boxes and faces to {@code cells}; an error message, or null. */
    @Nullable
    public static String draw(JsonObject args, Map<Character, IBlockState> palette, IBlockState[] cells) {
        JsonElement layers = args.get("layers");
        if (layers != null) {
            if (!layers.isJsonArray() || layers.getAsJsonArray().size() > 16) {
                return "layers must be an array of at most 16 layers.";
            }
            JsonArray ys = layers.getAsJsonArray();
            for (int y = 0; y < ys.size(); y++) {
                if (!ys.get(y).isJsonArray() || ys.get(y).getAsJsonArray().size() > 16) {
                    return "layers[" + y + "] must be an array of at most 16 strings.";
                }
                JsonArray rows = ys.get(y).getAsJsonArray();
                for (int z = 0; z < rows.size(); z++) {
                    String row = rows.get(z).getAsString();
                    if (row.length() > 16) {
                        return "layers[" + y + "][" + z + "] is " + row.length()
                            + " characters; at most 16.";
                    }
                    for (int x = 0; x < row.length(); x++) {
                        String bad = put(palette, cells, row.charAt(x), x, y, z);
                        if (bad != null) {
                            return "layers[" + y + "][" + z + "]: " + bad;
                        }
                    }
                }
            }
        }

        JsonArray boxes = Json.getArray(args, "boxes");
        for (int i = 0; boxes != null && i < boxes.size(); i++) {
            JsonObject box = boxes.get(i).getAsJsonObject();
            int[] from = intTriple(box, "from");
            int[] to = intTriple(box, "to");
            String key = Json.getString(box, "key", "");
            if (from == null || to == null || !inBlock(from) || !inBlock(to)) {
                return "boxes[" + i + "]: from and to must be [x, y, z] with each 0-15.";
            }
            if (key.length() != 1) {
                return "boxes[" + i + "]: key must be one character.";
            }
            for (int x = Math.min(from[0], to[0]); x <= Math.max(from[0], to[0]); x++) {
                for (int y = Math.min(from[1], to[1]); y <= Math.max(from[1], to[1]); y++) {
                    for (int z = Math.min(from[2], to[2]); z <= Math.max(from[2], to[2]); z++) {
                        String bad = put(palette, cells, key.charAt(0), x, y, z);
                        if (bad != null) {
                            return "boxes[" + i + "]: " + bad;
                        }
                    }
                }
            }
        }

        JsonArray faces = Json.getArray(args, "faces");
        for (int i = 0; faces != null && i < faces.size(); i++) {
            JsonObject face = faces.get(i).getAsJsonObject();
            String side = Json.getString(face, "face", "");
            List<String> rows = Json.getStringList(face, "rows");
            int depth = Json.getInt(face, "depth", 1);
            if (Arrays.asList(FACES).indexOf(side) < 0) {
                return "faces[" + i + "]: face must be one of north, south, west, east, up, down.";
            }
            if (rows.size() > 16 || depth < 1 || depth > 16) {
                return "faces[" + i + "]: at most 16 rows, and depth 1-16.";
            }
            for (int r = 0; r < rows.size(); r++) {
                String row = rows.get(r);
                if (row.length() > 16) {
                    return "faces[" + i + "].rows[" + r + "] is " + row.length()
                        + " characters; at most 16.";
                }
                for (int c = 0; c < row.length(); c++) {
                    for (int d = 0; d < depth; d++) {
                        int[] xyz = faceToBlock(side, r, c, d);
                        String bad = put(palette, cells, row.charAt(c), xyz[0], xyz[1], xyz[2]);
                        if (bad != null) {
                            return "faces[" + i + "].rows[" + r + "]: " + bad;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Where row {@code r}, column {@code c}, {@code d} bits in from the {@code face} side lands, for
     * someone outside that side looking at it: rows run top to bottom, columns left to right.
     * {@code up} and {@code down} read as a map, north at the top.
     */
    public static int[] faceToBlock(String face, int r, int c, int d) {
        int top = 15 - r;
        switch (face) {
            case "north": // looking south: left is east
                return new int[]{15 - c, top, d};
            case "south": // looking north: left is west
                return new int[]{c, top, 15 - d};
            case "west": // looking east: left is north
                return new int[]{d, top, c};
            case "east": // looking west: left is south
                return new int[]{15 - d, top, 15 - c};
            case "up":
                return new int[]{c, 15 - d, r};
            default: // down
                return new int[]{c, d, r};
        }
    }

    @Nullable
    private static String put(Map<Character, IBlockState> palette, IBlockState[] cells, char key,
        int x, int y, int z) {
        if (key == ' ') {
            return null;
        }
        IBlockState state = palette.get(key);
        if (state == null) {
            return "'" + key + "' is not a palette key.";
        }
        cells[ChiselBlob.index(x, y, z)] = state;
        return null;
    }

    private static boolean inBlock(int[] xyz) {
        for (int value : xyz) {
            if (value < 0 || value > 15) {
                return false;
            }
        }
        return true;
    }
}
