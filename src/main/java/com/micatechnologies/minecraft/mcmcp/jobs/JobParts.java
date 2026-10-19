package com.micatechnologies.minecraft.mcmcp.jobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.regions.CommandOps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Splits a job's arguments into parts that each fit one ordinary tool call, and folds the parts'
 * results back into one set of totals.
 *
 * <p>A part is exactly the call a script would have made by hand: the same tool, the same arguments,
 * a slice of the list or the box. That keeps every tool's own checks, limits and undo points, and means
 * a job does nothing a sequence of calls could not.
 *
 * <p>Pure: JSON in, JSON out.
 */
public final class JobParts {

    private JobParts() {
    }

    /** The kinds a job may be, and the tool each runs. */
    public static final String[][] KINDS = {
        {"apply_ops", "server_apply_ops"},
        {"set_blocks", "server_set_blocks"},
        {"get_blocks", "server_get_blocks"},
        {"relight", "server_relight"},
        {"test", "server_run_test"},
    };

    @Nullable
    public static String toolFor(String kind) {
        for (String[] pair : KINDS) {
            if (pair[0].equals(kind)) {
                return pair[1];
            }
        }
        return null;
    }

    /** The server's limits a part must fit. */
    public static final class Limits {
        final long writeCells;
        final long replayCells;
        final long listPlacements;
        final long fillCells;
        final long readCells;
        final long relightCells;

        public Limits(long writeCells, long replayCells, long listPlacements, long fillCells, long readCells,
                      long relightCells) {
            this.writeCells = writeCells;
            this.replayCells = replayCells;
            this.listPlacements = listPlacements;
            this.fillCells = fillCells;
            this.readCells = readCells;
            this.relightCells = relightCells;
        }
    }

    /** One part: a tool call's arguments, and where it sits in the whole. */
    public static final class Part {
        public final int index;
        public final JsonObject args;
        /** For an op replay: the index of this part's first op in the whole list. */
        public final int opOffset;
        /** For an op replay: the index of this part's last op. -1 otherwise. */
        public final int lastOp;
        /** For a box split: the part's box. Null otherwise. */
        @Nullable
        public final JsonObject region;

        Part(int index, JsonObject args, int opOffset, int lastOp, @Nullable JsonObject region) {
            this.index = index;
            this.args = args;
            this.opOffset = opOffset;
            this.lastOp = lastOp;
            this.region = region;
        }
    }

    /**
     * The parts of a job.
     *
     * @throws IllegalArgumentException with a message for the caller when the arguments cannot be split
     */
    public static List<Part> split(String kind, JsonObject args, Limits limits) {
        switch (kind) {
            case "apply_ops":
                return splitOps(args, limits.replayCells);
            case "set_blocks":
                return splitWrite(args, limits);
            case "get_blocks":
                return splitBox(args, limits.readCells, 0, 255);
            case "relight":
                return splitBox(args, limits.relightCells, 0, 255);
            case "test":
                // A script is one run: its own steps are its pace, and it restores as a whole.
                return Collections.singletonList(new Part(0, args, 0, -1, null));
            default:
                throw new IllegalArgumentException("'kind' is one of apply_ops, set_blocks, get_blocks, relight, test.");
        }
    }

    private static List<Part> splitOps(JsonObject args, long cap) {
        List<String> ops = Json.getStringList(args, "ops");
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("An apply_ops job needs 'ops'.");
        }
        List<Part> parts = new ArrayList<>();
        int start = 0;
        long reach = 0L;
        for (int i = 0; i < ops.size(); i++) {
            long cells = 1L;
            try {
                cells = CommandOps.parse(ops.get(i)).cells();
            }
            catch (CommandOps.ParseException e) {
                // Refused when its part runs, with the reason; it costs nothing to carry.
            }
            boolean full = i > start && (reach + cells > cap || i - start >= 50_000);
            if (full) {
                parts.add(opsPart(parts.size(), args, ops, start, i));
                start = i;
                reach = 0L;
            }
            reach += cells;
        }
        parts.add(opsPart(parts.size(), args, ops, start, ops.size()));
        return parts;
    }

    private static Part opsPart(int index, JsonObject args, List<String> ops, int from, int to) {
        JsonObject part = copy(args);
        part.add("ops", Json.arrayOfStrings(ops.subList(from, to)));
        return new Part(index, part, from, to - 1, null);
    }

    private static List<Part> splitWrite(JsonObject args, Limits limits) {
        if (args.has("cells")) {
            JsonArray cells = args.getAsJsonArray("cells");
            int stride = args.has("expectPalette") ? 5 : 4;
            long perPart = Math.max(1L, limits.writeCells) * stride;
            if (cells.size() <= perPart) {
                return Collections.singletonList(new Part(0, args, 0, -1, null));
            }
            List<Part> parts = new ArrayList<>();
            for (int from = 0; from < cells.size(); from += perPart) {
                JsonObject part = copy(args);
                JsonArray slice = new JsonArray();
                for (int i = from; i < Math.min(cells.size(), from + perPart); i++) {
                    slice.add(cells.get(i));
                }
                part.add("cells", slice);
                parts.add(new Part(parts.size(), part, 0, -1, null));
            }
            return parts;
        }
        if (args.has("blocks")) {
            JsonArray blocks = args.getAsJsonArray("blocks");
            List<Part> parts = new ArrayList<>();
            for (int from = 0; from < Math.max(1, blocks.size()); from += limits.listPlacements) {
                JsonObject part = copy(args);
                JsonArray slice = new JsonArray();
                for (int i = from; i < Math.min(blocks.size(), from + limits.listPlacements); i++) {
                    slice.add(blocks.get(i));
                }
                part.add("blocks", slice);
                parts.add(new Part(parts.size(), part, 0, -1, null));
            }
            return parts;
        }
        if (args.has("indices")) {
            return Collections.singletonList(new Part(0, args, 0, -1, null));
        }
        // Fill mode: y defaults to itself rather than the whole column.
        int y = Json.getInt(args, "y", 0);
        JsonObject withY = copy(args);
        if (!withY.has("toY")) {
            withY.addProperty("toY", y);
        }
        return splitBox(withY, limits.fillCells, y, y);
    }

    /** Splits a box into parts of at most {@code maxCells}: by layers, then rows, then columns. */
    private static List<Part> splitBox(JsonObject args, long maxCells, int defaultY, int defaultToY) {
        if (!args.has("x") || !args.has("z")) {
            throw new IllegalArgumentException("A box job needs x and z, and usually y, toX, toY, toZ.");
        }
        int x1 = Json.getInt(args, "x", 0);
        int z1 = Json.getInt(args, "z", 0);
        int y1 = Json.getInt(args, "y", defaultY);
        int x2 = Json.getInt(args, "toX", x1);
        int z2 = Json.getInt(args, "toZ", z1);
        int y2 = Json.getInt(args, "toY", args.has("y") ? y1 : defaultToY);
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.max(0, Math.min(y1, y2));
        int maxY = Math.min(255, Math.max(y1, y2));
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        long sizeX = maxX - minX + 1L;
        long sizeZ = maxZ - minZ + 1L;
        long layer = sizeX * sizeZ;
        List<Part> parts = new ArrayList<>();
        if (layer <= maxCells) {
            int layers = (int) Math.max(1L, maxCells / layer);
            for (int y = minY; y <= maxY; y += layers) {
                parts.add(boxPart(parts.size(), args, minX, y, minZ, maxX, Math.min(maxY, y + layers - 1), maxZ));
            }
            return parts;
        }
        if (sizeX <= maxCells) {
            int rows = (int) Math.max(1L, maxCells / sizeX);
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z += rows) {
                    parts.add(boxPart(parts.size(), args, minX, y, z, maxX, y, Math.min(maxZ, z + rows - 1)));
                }
            }
            return parts;
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (long x = minX; x <= maxX; x += maxCells) {
                    parts.add(boxPart(parts.size(), args, (int) x, y, z, (int) Math.min(maxX, x + maxCells - 1), y, z));
                }
            }
        }
        return parts;
    }

    private static Part boxPart(int index, JsonObject args, int x1, int y1, int z1, int x2, int y2, int z2) {
        JsonObject part = copy(args);
        JsonObject region = new JsonObject();
        region.addProperty("x", x1);
        region.addProperty("y", y1);
        region.addProperty("z", z1);
        region.addProperty("toX", x2);
        region.addProperty("toY", y2);
        region.addProperty("toZ", z2);
        for (Map.Entry<String, JsonElement> entry : region.entrySet()) {
            part.add(entry.getKey(), entry.getValue());
        }
        return new Part(index, part, 0, -1, region);
    }

    private static JsonObject copy(JsonObject args) {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            copy.add(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    /** List fields of a part's result worth keeping across parts, at most this many items in all. */
    private static final String[] LISTS = {"refusedOps", "refusedAt", "conflictAt", "conflictList", "failedList"};
    private static final int MAX_LISTED = 100;

    /**
     * Adds a part's result to the job's totals: every top-level number summed, the lists of refusals
     * appended (an op's line made relative to the whole list), the undo point remembered.
     */
    static void fold(JsonObject totals, JsonArray lists, JsonObject result, int opOffset) {
        for (Map.Entry<String, JsonElement> entry : result.entrySet()) {
            JsonElement value = entry.getValue();
            if ("dimension".equals(entry.getKey()) || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                continue;
            }
            JsonElement before = totals.get(entry.getKey());
            double sum = (before == null ? 0.0D : before.getAsDouble()) + value.getAsDouble();
            totals.add(entry.getKey(), sum == Math.rint(sum) ? new JsonPrimitive((long) sum) : new JsonPrimitive(sum));
        }
        for (String name : LISTS) {
            JsonElement list = result.get(name);
            if (list == null || !list.isJsonArray()) {
                continue;
            }
            for (JsonElement item : list.getAsJsonArray()) {
                if (lists.size() >= MAX_LISTED) {
                    return;
                }
                if (item.isJsonObject()) {
                    JsonObject copy = copy(item.getAsJsonObject());
                    if (copy.has("line")) {
                        copy.addProperty("line", copy.get("line").getAsInt() + opOffset);
                    }
                    copy.addProperty("list", name);
                    lists.add(copy);
                }
            }
        }
        if (result.has("undoPoint") && lists.size() < MAX_LISTED) {
            JsonObject undo = new JsonObject();
            undo.addProperty("list", "undoPoints");
            undo.add("undoPoint", result.get("undoPoint"));
            lists.add(undo);
        }
    }
}
