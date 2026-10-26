package com.micatechnologies.minecraft.mcmcp.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.regions.BuildTarget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A building as a short declarative spec, expanded by the server into the fill and setblock ops
 * {@code server_apply_ops} replays (#138 plan, Phase 2).
 *
 * <p>The builder this is for wrote a Python generator per building and ran it, again and again: that was
 * the largest share of what it re-read every turn (measured 2026-10-26). A spec says the same thing in a
 * few lines, and the reply is counts. Its shape follows the generators: a storey is a 2D plan extruded
 * up, a facade is a rhythm of bays, a building is shells and slabs.
 *
 * <pre>
 * {"origin": [x, y, z],
 *  "palette": {"W": "csm:brick", "G": "minecraft:glass", "D": "minecraft:stone_slab:8"},
 *  "parts": [
 *    {"shell": [0, 0, 0, 20, 24, 12], "wall": "W", "floor": "D", "floorsEvery": 6, "roof": "D"},
 *    {"repeat": {"box": [2, 2, 0, 3, 4, 0], "fill": "G"}, "step": [4, 0, 0], "count": 4},
 *    {"plan": ["WWW.WWW", "W.....W", "WWWDWWW"], "at": [0, 1, 0], "height": 3},
 *    {"mirror": {"line": [0, 1, 0, 5, 1, 0], "fill": "W"}, "axis": "x", "about": 10},
 *    {"stamp": "components/window-a", "at": [4, 2, 0]}]}
 * </pre>
 *
 * <p>Coordinates are relative to {@code origin}; boxes are two corners, inclusive. A palette value is a
 * block id, {@code id:meta}, or {@code id[prop=value,...]}. Later parts overwrite earlier ones, as ops do.
 *
 * <p>Pure: spec in, ops out. Components come from a {@link Components} the caller supplies.
 */
public final class BuildProgram {

    /** Most ops one spec may expand to. */
    public static final int MAX_OPS = 50_000;
    /** Most ops a spec may expand to at all, as a job. */
    public static final int MAX_JOB_OPS = 5_000_000;
    /** Most nesting of repeat and mirror. */
    private static final int MAX_DEPTH = 8;

    /** Saved build targets, to stamp as components. */
    public interface Components {
        @Nullable
        BuildTarget get(String name);
    }

    /** A spec that cannot be expanded, with where in it. */
    public static final class SpecException extends IllegalArgumentException {
        public SpecException(String message) {
            super(message);
        }
    }

    private final int[] origin;
    private final Map<String, String> palette = new HashMap<>();
    private final Components components;
    private final List<String> ops = new ArrayList<>();
    private final int maxOps;

    private BuildProgram(int[] origin, Components components, int maxOps) {
        this.origin = origin;
        this.components = components;
        this.maxOps = maxOps;
    }

    /** Expands a spec into op lines, at most {@link #MAX_OPS}: what one call replays. */
    public static List<String> expand(JsonObject spec, Components components) {
        return expand(spec, components, MAX_OPS);
    }

    /** As {@link #expand(JsonObject, Components)}, with another ceiling: a job splits the ops into calls. */
    public static List<String> expand(JsonObject spec, Components components, int maxOps) {
        JsonArray at = spec.getAsJsonArray("origin");
        if (at == null || at.size() != 3) {
            throw new SpecException("'origin' is [x, y, z]: every other coordinate is relative to it.");
        }
        BuildProgram program = new BuildProgram(new int[] {at.get(0).getAsInt(), at.get(1).getAsInt(),
            at.get(2).getAsInt()}, components, Math.min(maxOps, MAX_JOB_OPS));
        JsonObject palette = Json.getObject(spec, "palette");
        if (palette != null) {
            for (Map.Entry<String, JsonElement> entry : palette.entrySet()) {
                program.palette.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        JsonArray parts = spec.getAsJsonArray("parts");
        if (parts == null || parts.size() == 0) {
            throw new SpecException("'parts' is the list of what to build.");
        }
        for (int i = 0; i < parts.size(); i++) {
            program.part(parts.get(i), new int[3], "parts[" + i + "]", 0);
        }
        return program.ops;
    }

    // ------------------------------------------------------------------
    // Parts
    // ------------------------------------------------------------------

    /** Emits a part, shifted by {@code offset} (from repeats) and optionally mirrored. */
    private void part(JsonElement element, int[] offset, String where, int depth) {
        if (!element.isJsonObject()) {
            throw new SpecException(where + " is not an object.");
        }
        if (depth > MAX_DEPTH) {
            throw new SpecException(where + ": repeats and mirrors nest at most " + MAX_DEPTH + " deep.");
        }
        JsonObject part = element.getAsJsonObject();
        if (part.has("box")) {
            box(part, offset, where);
        }
        else if (part.has("shell")) {
            shell(part, offset, where);
        }
        else if (part.has("line")) {
            line(part, offset, where);
        }
        else if (part.has("columns")) {
            columns(part, offset, where);
        }
        else if (part.has("plan")) {
            plan(part, offset, where);
        }
        else if (part.has("repeat")) {
            int[] step = ints(part, "step", 3, where);
            int count = Json.getInt(part, "count", 0);
            if (count < 1 || count > 10_000) {
                throw new SpecException(where + ": 'count' is 1 to 10000.");
            }
            for (int i = 0; i < count; i++) {
                part(part.get("repeat"), new int[] {offset[0] + step[0] * i, offset[1] + step[1] * i, offset[2] + step[2] * i},
                    where + ".repeat#" + i, depth + 1);
            }
        }
        else if (part.has("mirror")) {
            // The part as given, then its reflection across the plane axis = about.
            part(part.get("mirror"), offset, where + ".mirror", depth + 1);
            String axis = Json.getString(part, "axis", "");
            if (!"x".equals(axis) && !"z".equals(axis)) {
                throw new SpecException(where + ": mirror 'axis' is 'x' or 'z'.");
            }
            int about = Json.getInt(part, "about", 0);
            int start = ops.size();
            part(part.get("mirror"), offset, where + ".mirror", depth + 1);
            for (int i = start; i < ops.size(); i++) {
                ops.set(i, reflect(ops.get(i), "x".equals(axis) ? 0 : 2, origin["x".equals(axis) ? 0 : 2] + about));
            }
        }
        else if (part.has("stamp")) {
            stamp(part, offset, where);
        }
        else {
            throw new SpecException(where + " is none of box, shell, line, columns, plan, repeat, mirror, stamp.");
        }
        if (ops.size() > maxOps) {
            throw new SpecException("The spec expands past " + maxOps + " ops at " + where
                + (maxOps < MAX_JOB_OPS ? ": submit it as a job (server_job_submit kind 'build')." : "."));
        }
    }

    private void box(JsonObject part, int[] offset, String where) {
        int[] b = ints(part, "box", 6, where);
        String block = block(part, "fill", where);
        String mode = Json.getString(part, "mode", null);
        if (mode != null && !mode.matches("hollow|outline|keep|replace|destroy")) {
            throw new SpecException(where + ": box 'mode' is hollow, outline, keep or replace.");
        }
        fill(b, offset, block, mode);
    }

    /** Four walls, with optional base, roof and floors every N blocks up. */
    private void shell(JsonObject part, int[] offset, String where) {
        int[] b = sorted(ints(part, "shell", 6, where));
        String wall = block(part, "wall", where);
        if (Json.getBoolean(part, "clear", true)) {
            fill(new int[] {b[0] + 1, b[1], b[2] + 1, b[3] - 1, b[4], b[5] - 1}, offset, "minecraft:air", null);
        }
        fill(new int[] {b[0], b[1], b[2], b[3], b[4], b[2]}, offset, wall, null);
        fill(new int[] {b[0], b[1], b[5], b[3], b[4], b[5]}, offset, wall, null);
        fill(new int[] {b[0], b[1], b[2], b[0], b[4], b[5]}, offset, wall, null);
        fill(new int[] {b[3], b[1], b[2], b[3], b[4], b[5]}, offset, wall, null);
        String floor = part.has("floor") ? block(part, "floor", where) : null;
        if (floor != null) {
            fill(new int[] {b[0] + 1, b[1], b[2] + 1, b[3] - 1, b[1], b[5] - 1}, offset, floor, null);
            int every = Json.getInt(part, "floorsEvery", 0);
            if (every > 0) {
                for (int y = b[1] + every; y < b[4]; y += every) {
                    fill(new int[] {b[0] + 1, y, b[2] + 1, b[3] - 1, y, b[5] - 1}, offset, floor, null);
                }
            }
        }
        if (part.has("roof")) {
            fill(new int[] {b[0], b[4], b[2], b[3], b[4], b[5]}, offset, block(part, "roof", where), null);
        }
    }

    /** A straight line between two cells: a fill when it runs along one axis, otherwise cell by cell. */
    private void line(JsonObject part, int[] offset, String where) {
        int[] l = ints(part, "line", 6, where);
        String block = block(part, "fill", where);
        int axes = (l[0] != l[3] ? 1 : 0) + (l[1] != l[4] ? 1 : 0) + (l[2] != l[5] ? 1 : 0);
        if (axes <= 1) {
            fill(l, offset, block, null);
            return;
        }
        int steps = Math.max(Math.abs(l[3] - l[0]), Math.max(Math.abs(l[4] - l[1]), Math.abs(l[5] - l[2])));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : i / (double) steps;
            setblock(new int[] {(int) Math.round(l[0] + (l[3] - l[0]) * t), (int) Math.round(l[1] + (l[4] - l[1]) * t),
                (int) Math.round(l[2] + (l[5] - l[2]) * t)}, offset, block);
        }
    }

    /** Columns on a grid: every [dx, dz] across the box, each [sx, sz] in section, the box's full height. */
    private void columns(JsonObject part, int[] offset, String where) {
        int[] b = sorted(ints(part, "columns", 6, where));
        int[] every = ints(part, "every", 2, where);
        int[] size = part.has("size") ? ints(part, "size", 2, where) : new int[] {1, 1};
        if (every[0] < 1 || every[1] < 1 || size[0] < 1 || size[1] < 1) {
            throw new SpecException(where + ": 'every' and 'size' are positive.");
        }
        String block = block(part, "fill", where);
        for (int x = b[0]; x <= b[3]; x += every[0]) {
            for (int z = b[2]; z <= b[5]; z += every[1]) {
                fill(new int[] {x, b[1], z, Math.min(b[3], x + size[0] - 1), b[4], Math.min(b[5], z + size[1] - 1)},
                    offset, block, null);
            }
        }
    }

    /**
     * A plan of palette characters, run-length merged into fills. Rows run along z (or down y for a facade),
     * characters along x (or z); '.' and ' ' leave a cell alone. 'axis': 'xz' (a floor plan, default; each
     * cell extruded up 'height'), 'xy' (a facade at fixed z, rows top to bottom), 'zy' (a facade at fixed x).
     */
    private void plan(JsonObject part, int[] offset, String where) {
        JsonArray rows = part.getAsJsonArray("plan");
        int[] at = part.has("at") ? ints(part, "at", 3, where) : new int[3];
        String axis = Json.getString(part, "axis", "xz");
        int height = Json.getInt(part, "height", 1);
        if (height < 1 || height > 256) {
            throw new SpecException(where + ": 'height' is 1 to 256.");
        }
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r).getAsString();
            int c = 0;
            while (c < row.length()) {
                char ch = row.charAt(c);
                int run = 1;
                while (c + run < row.length() && row.charAt(c + run) == ch) {
                    run++;
                }
                if (ch != '.' && ch != ' ') {
                    String block = paletteBlock(String.valueOf(ch), where + ".plan[" + r + "]");
                    int[] box;
                    switch (axis) {
                        case "xz":
                            box = new int[] {at[0] + c, at[1], at[2] + r, at[0] + c + run - 1, at[1] + height - 1, at[2] + r};
                            break;
                        case "xy":
                            box = new int[] {at[0] + c, at[1] - r, at[2], at[0] + c + run - 1, at[1] - r, at[2] + height - 1};
                            break;
                        case "zy":
                            box = new int[] {at[0], at[1] - r, at[2] + c, at[0] + height - 1, at[1] - r, at[2] + c + run - 1};
                            break;
                        default:
                            throw new SpecException(where + ": plan 'axis' is xz, xy or zy.");
                    }
                    fill(box, offset, block, null);
                }
                c += run;
            }
        }
    }

    /** A saved target, placed with its smallest corner at 'at'. */
    private void stamp(JsonObject part, int[] offset, String where) {
        String name = Json.getString(part, "stamp", "");
        BuildTarget component = components.get(name);
        int[] bounds = component == null ? null : component.bounds();
        if (bounds == null) {
            throw new SpecException(where + ": no component '" + name + "' (a build target; save one with "
                + "server_target capture or saveTarget).");
        }
        int[] at = ints(part, "at", 3, where);
        final int dx = origin[0] + offset[0] + at[0] - bounds[0];
        final int dy = origin[1] + offset[1] + at[1] - bounds[1];
        final int dz = origin[2] + offset[2] + at[2] - bounds[2];
        component.forEach((x, y, z, state) -> ops.add(setblockLine(x + dx, y + dy, z + dz, state)));
    }

    // ------------------------------------------------------------------
    // Ops
    // ------------------------------------------------------------------

    private void fill(int[] b, int[] offset, String block, @Nullable String mode) {
        int[] s = sorted(b);
        int x1 = origin[0] + offset[0] + s[0];
        int y1 = origin[1] + offset[1] + s[1];
        int z1 = origin[2] + offset[2] + s[2];
        int x2 = origin[0] + offset[0] + s[3];
        int y2 = origin[1] + offset[1] + s[4];
        int z2 = origin[2] + offset[2] + s[5];
        if (x1 == x2 && y1 == y2 && z1 == z2 && mode == null) {
            ops.add(setblockLine(x1, y1, z1, block));
            return;
        }
        ops.add("fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + blockArgs(block)
            + (mode == null ? "" : " " + mode));
    }

    private void setblock(int[] cell, int[] offset, String block) {
        ops.add(setblockLine(origin[0] + offset[0] + cell[0], origin[1] + offset[1] + cell[1],
            origin[2] + offset[2] + cell[2], block));
    }

    static String setblockLine(int x, int y, int z, String block) {
        return "setblock " + x + " " + y + " " + z + " " + blockArgs(block);
    }

    /** "id", "id:meta" or "id[props]" as the op's block and data arguments. */
    static String blockArgs(String block) {
        int open = block.indexOf('[');
        if (open > 0 && block.endsWith("]")) {
            String props = block.substring(open + 1, block.length() - 1);
            return block.substring(0, open) + (props.isEmpty() ? "" : " " + props);
        }
        int colon = block.lastIndexOf(':');
        if (colon > 0 && colon < block.length() - 1 && block.substring(colon + 1).matches("\\d+")
            && block.indexOf(':') != colon) {
            return block.substring(0, colon) + " " + block.substring(colon + 1);
        }
        return block;
    }

    /** An op reflected across the plane {@code coordinate = about} on one axis (0 = x, 2 = z). */
    static String reflect(String op, int axis, int about) {
        String[] t = op.split(" ");
        boolean fill = "fill".equals(t[0]);
        int first = 1 + axis;
        t[first] = String.valueOf(2 * about - Integer.parseInt(t[first]));
        if (fill) {
            t[first + 3] = String.valueOf(2 * about - Integer.parseInt(t[first + 3]));
        }
        return String.join(" ", t);
    }

    private String block(JsonObject part, String key, String where) {
        String name = Json.getString(part, key, null);
        if (name == null) {
            throw new SpecException(where + ": '" + key + "' is missing (a palette key or a block id).");
        }
        return paletteBlock(name, where);
    }

    private String paletteBlock(String name, String where) {
        String block = palette.get(name);
        if (block != null) {
            return block;
        }
        if (name.contains(":")) {
            return name;
        }
        throw new SpecException(where + ": '" + name + "' is not in the palette and is not a block id.");
    }

    private static int[] ints(JsonObject part, String key, int count, String where) {
        JsonElement raw = part.get(key);
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != count) {
            throw new SpecException(where + ": '" + key + "' is " + count + " numbers.");
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = raw.getAsJsonArray().get(i).getAsInt();
        }
        return out;
    }

    private static int[] sorted(int[] b) {
        return new int[] {Math.min(b[0], b[3]), Math.min(b[1], b[4]), Math.min(b[2], b[5]),
            Math.max(b[0], b[3]), Math.max(b[1], b[4]), Math.max(b[2], b[5])};
    }
}
