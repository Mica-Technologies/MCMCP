package com.micatechnologies.minecraft.mcmcp.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The compliant switchback stair planner behind server_build_stair: a port of the UIA knowledge base's
 * {@code tools/stairs/design.py}, cell for cell. Given an enclosure box, the floor levels and a door per
 * floor, it lays out two lanes of flights with a solid divider between them, full-width landings at both
 * ends, the solid fill under the lowest flights, a roof deck with a parapet and rail, and a sconce at
 * every landing. When the box cannot hold a stair that meets the rules it refuses, and the refusal says
 * what to change, rather than emitting a stair that fails them.
 *
 * <p>The geometry is in two coordinates: {@code u} runs along the long axis from the wall the first
 * flight leaves from, {@code v} across it from the low side. Lane 0 ({@code v < width}) always climbs
 * towards the high end, lane 1 back. A storey of rise R takes {@code ceil(R / run)} flights, one more if
 * that would land it at the wrong end for its door, split as evenly as possible with the longer flights
 * last, because the last flight of the stair has to be long enough to deck the roof over.
 *
 * <p>Imports no Minecraft class: the output is a map of positions to {@code "ns:id"} or
 * {@code "ns:id:meta"} strings, so the planner is tested exactly against the reference planner's
 * fixtures without a game. Dict iteration order matters in two places in the original (which door's
 * refusal comes first, and which door decides a storey's end); {@link Input#doors} is a
 * {@link LinkedHashMap} for that reason.
 */
public final class StairDesign {

    public static final String AIR = "minecraft:air";
    /** rules.MIN_WIDTH: commercial stairs at least 3 wide. */
    public static final int MIN_WIDTH = 3;
    /** rules.HEADROOM: clear cells above every tread and landing. */
    public static final int HEADROOM = 3;
    /** rules.BULKHEAD_CLEAR: clear rows above the roof landing inside the rooftop bulkhead. */
    public static final int BULKHEAD_CLEAR = 6;

    /** design.py's "no top given": large enough that the bulkhead clearance always wins. */
    private static final int NO_TOP = 999;

    /** The blocks a stair is built of. Treads take {@code tread + ":" + meta}, sconces likewise. */
    public static final class Palette {
        public final String floor;
        public final String wall;
        public final String tread;
        public final String rail;
        public final String sconce;

        public Palette(String floor, String wall, String tread, String rail, String sconce) {
            this.floor = floor;
            this.wall = wall;
            this.tread = tread;
            this.rail = rail;
            this.sconce = sconce;
        }

        /**
         * rules.py's palette. The rail is a glass pane over a cast parapet block, which is what the
         * reference fixtures were generated with; the tool spec's default rail is a CSM pipe railing.
         */
        public static Palette defaults() {
            return new Palette("minecraft:concrete:8", "minecraft:concrete:7", "minecraft:stone_brick_stairs",
                               "minecraft:glass_pane", "csm:sconcehalfshellnickel");
        }
    }

    /** What to plan. Mirrors design.py's config; the fields are public and set directly. */
    public static final class Input {
        /** Enclosure walls, inclusive: {x0, z0, x1, z1}. */
        public int[] box;
        /** Stand y of each floor door, bottom up; the last is the roof landing. */
        public List<Integer> floors = new ArrayList<>();
        public int width = MIN_WIDTH;
        /** "x" or "z", the long axis the flights run along; {@code null} for the longer interior. */
        public String axis;
        /** True when the first flight leaves from the high end of the long axis. */
        public boolean startHi;
        /** Door cells {x, z} in the enclosure wall per floor y, in the order given. */
        public Map<Integer, List<int[]>> doors = new LinkedHashMap<>();
        /** Floor y to the number of cells that storey's high end is shortened by. */
        public Map<Integer, Integer> inset = new HashMap<>();
        /** Last interior air row (one under an existing roof slab); {@code null} for roof + 6. */
        public Integer top;
        /** Also emit the enclosure ring and the bulkhead roof. */
        public boolean walls;
        public Palette palette = Palette.defaults();

        /**
         * Reads design.py's config shape: {@code box, floors, width, axis, start, doors, inset, top,
         * walls, palette}. Other keys (name, labels, signs) are ignored, as design.py ignores them.
         * Keys of {@code doors} and {@code inset} are floor levels as strings, as JSON requires.
         */
        public static Input fromJson(JsonObject cfg) throws DesignException {
            Input in = new Input();
            JsonArray box = Json.getArray(cfg, "box");
            if (box == null || box.size() != 4) {
                throw new DesignException("box must be [x0, z0, x1, z1]");
            }
            in.box = new int[4];
            for (int i = 0; i < 4; i++) {
                in.box[i] = box.get(i).getAsInt();
            }
            JsonArray floors = Json.getArray(cfg, "floors");
            if (floors != null) {
                for (JsonElement f : floors) {
                    in.floors.add(f.getAsInt());
                }
            }
            in.width = Json.getInt(cfg, "width", MIN_WIDTH);
            String axis = Json.getString(cfg, "axis");
            in.axis = axis == null || axis.isEmpty() ? null : axis;
            in.startHi = "hi".equals(Json.getString(cfg, "start", "lo"));
            JsonObject doors = Json.getObject(cfg, "doors");
            if (doors != null) {
                for (Map.Entry<String, JsonElement> e : doors.entrySet()) {
                    List<int[]> cells = new ArrayList<>();
                    for (JsonElement c : e.getValue().getAsJsonArray()) {
                        JsonArray xz = c.getAsJsonArray();
                        cells.add(new int[] {xz.get(0).getAsInt(), xz.get(1).getAsInt()});
                    }
                    in.doors.put(parseLevel(e.getKey()), cells);
                }
            }
            JsonObject inset = Json.getObject(cfg, "inset");
            if (inset != null) {
                for (Map.Entry<String, JsonElement> e : inset.entrySet()) {
                    in.inset.put(parseLevel(e.getKey()), e.getValue().getAsInt());
                }
            }
            if (Json.has(cfg, "top") && cfg.get("top").isJsonPrimitive()) {
                in.top = cfg.get("top").getAsInt();
            }
            in.walls = Json.getBoolean(cfg, "walls", false);
            JsonObject pal = Json.getObject(cfg, "palette");
            if (pal != null) {
                Palette d = Palette.defaults();
                in.palette = new Palette(Json.getString(pal, "floor", d.floor), Json.getString(pal, "wall", d.wall),
                                         Json.getString(pal, "tread", d.tread), Json.getString(pal, "rail", d.rail),
                                         Json.getString(pal, "sconce", d.sconce));
            }
            return in;
        }

        private static int parseLevel(String key) throws DesignException {
            try {
                return Integer.parseInt(key.trim());
            }
            catch (NumberFormatException e) {
                throw new DesignException("'" + key + "' is not a floor level");
            }
        }
    }

    /** A plan refused, with what to change. The message is design.py's DesignError text, verbatim. */
    public static final class DesignException extends Exception {
        public DesignException(String message) {
            super(message);
        }
    }

    /** One flight as design.py reports it: [storey, ys, n, lane]. */
    public static final class Flight {
        /** The floor y of the storey this flight belongs to. */
        public final int storey;
        /** The y of its first tread. */
        public final int ys;
        /** Tread count, which is also its rise. */
        public final int n;
        /** 0 climbs towards the high end, 1 back towards the low end. */
        public final int lane;

        Flight(int storey, int ys, int n, int lane) {
            this.storey = storey;
            this.ys = ys;
            this.n = n;
            this.lane = lane;
        }

        @Override
        public String toString() {
            return "[" + storey + ", " + ys + ", " + n + ", " + lane + "]";
        }
    }

    /** A finished plan. */
    public static final class Plan {
        /**
         * Every cell to write, ordered x, y, z. Includes the air the planner clears inside the box: a
         * stair is written over whatever was there.
         */
        public final SortedMap<Pos, String> cells;
        public final String axis;
        /** Cells of the run between the two landings along the long axis. */
        public final int run;
        public final List<Flight> flights;
        /** The last interior air row; the bulkhead roof, when walls are on, is the row above. */
        public final int top;

        Plan(SortedMap<Pos, String> cells, String axis, int run, List<Flight> flights, int top) {
            this.cells = cells;
            this.axis = axis;
            this.run = run;
            this.flights = flights;
            this.top = top;
        }
    }

    private StairDesign() {
    }

    /** Plans a stair, or refuses with the fix. */
    public static Plan plan(Input in) throws DesignException {
        return new Planner(in).plan();
    }

    /** A sconce before its facing is resolved: design.py's {@code ('light', dir)} tuple. */
    private static final class Light {
        final int dir;

        Light(int dir) {
            this.dir = dir;
        }
    }

    /** A flight during planning: design.py's flight dict. */
    private static final class F {
        int g;
        int lane;
        int d;
        int floor;
        int n;
        int ys;
        int[] us;
        int hiU;
    }

    /** One planning run; the fields are design.py's locals. */
    private static final class Planner {
        private final Input in;
        private final Palette p;
        private int x0;
        private int z0;
        private int x1;
        private int z1;
        private int lg;
        private int sh;
        private int w;
        private boolean flip;
        /** Values are a block string or a {@link Light}. */
        private final Map<Pos, Object> m = new HashMap<>();

        Planner(Input in) {
            this.in = in;
            this.p = in.palette == null ? Palette.defaults() : in.palette;
        }

        /** {x, z} of the cell u along the long axis from the start wall, v across from the low side. */
        private int[] cell(int u, int v, boolean axisZ) {
            if (flip) {
                u = lg - 1 - u;
            }
            return axisZ ? new int[] {x0 + 1 + v, z0 + 1 + u} : new int[] {x0 + 1 + u, z0 + 1 + v};
        }

        private int uOf(int ix, int iz, boolean axisZ) {
            int u = axisZ ? iz - z0 - 1 : ix - x0 - 1;
            return flip ? lg - 1 - u : u;
        }

        private boolean axisZ;

        private void put(int u, int v, int y, Object b) {
            int[] c = cell(u, v, axisZ);
            m.put(new Pos(c[0], y, c[1]), b);
        }

        private int clampX(int x) {
            return Math.min(Math.max(x, x0 + 1), x1 - 1);
        }

        private int clampZ(int z) {
            return Math.min(Math.max(z, z0 + 1), z1 - 1);
        }

        /** Which end the first door of floor y opens onto: 0 low, 1 high, null the run or no door. */
        private Integer doorEnd(int floor, int hiU) {
            List<int[]> ds = in.doors.get(floor);
            if (ds == null || ds.isEmpty()) {
                return null;
            }
            int[] d = ds.get(0);
            int u = uOf(clampX(d[0]), clampZ(d[1]), axisZ);
            return u < w ? Integer.valueOf(0) : u >= hiU ? Integer.valueOf(1) : null;
        }

        Plan plan() throws DesignException {
            w = in.width;
            if (w < MIN_WIDTH) {
                throw refuse("width %d < rules.MIN_WIDTH %d", w, MIN_WIDTH);
            }
            if (in.box == null || in.box.length != 4) {
                throw new DesignException("box must be [x0, z0, x1, z1]");
            }
            x0 = in.box[0];
            z0 = in.box[1];
            x1 = in.box[2];
            z1 = in.box[3];
            int nx = x1 - x0 - 1;
            int nz = z1 - z0 - 1;
            String axis = in.axis != null && !in.axis.isEmpty() ? in.axis : nz >= nx ? "z" : "x";
            if (!"x".equals(axis) && !"z".equals(axis)) {
                throw new DesignException("axis must be \"x\" or \"z\", not \"" + axis + "\"");
            }
            axisZ = "z".equals(axis);
            lg = axisZ ? nz : nx;
            sh = axisZ ? nx : nz;
            String sa = axisZ ? "x" : "z";
            if (sh < 2 * w + 1) {
                throw refuse("core needs %d more cells in %s: two %d-wide flights + a 1-cell divider = %d, interior is %d",
                             2 * w + 1 - sh, sa, w, 2 * w + 1, sh);
            }
            int run = lg - 2 * w;
            if (run < 2) {
                throw refuse("core needs %d more cells in %s for %d-deep landings at both ends + a 2-tread run",
                             2 - run, axis, w);
            }
            // Not in design.py, which fails with an IndexError or a division by zero on these instead.
            List<Integer> fl = in.floors;
            if (fl == null || fl.size() < 2) {
                throw new DesignException("floors needs at least two levels: the bottom floor and the roof landing");
            }
            for (int i = 1; i < fl.size(); i++) {
                if (fl.get(i) <= fl.get(i - 1)) {
                    throw refuse("floors must rise: y%d follows y%d", fl.get(i), fl.get(i - 1));
                }
            }
            int roof = fl.get(fl.size() - 1);
            int top = Math.min(roof + BULKHEAD_CLEAR, in.top == null ? NO_TOP : in.top);
            Map<Integer, Integer> inset = in.inset == null ? Collections.<Integer, Integer>emptyMap() : in.inset;
            flip = in.startHi;

            // interior air first, from the bottom floor's stand row to the bulkhead
            for (int y = fl.get(0); y <= top; y++) {
                for (int u = 0; u < lg; u++) {
                    for (int v = 0; v < sh; v++) {
                        put(u, v, y, AIR);
                    }
                }
            }
            List<F> flights = new ArrayList<>();
            int g = 0;
            for (int s = 0; s + 1 < fl.size(); s++) {
                int floor = fl.get(s);
                int next = fl.get(s + 1);
                int rise = next - floor;
                int rs = run - inset.getOrDefault(floor, 0);
                if (rs < 2) {
                    throw refuse("storey y%d: inset leaves a %d-cell run", floor, rs);
                }
                int k = (rise + rs - 1) / rs;
                Integer want = doorEnd(next, w + (run - inset.getOrDefault(next, 0)));
                if (want != null && ((g + k) % 2 == 1) != (want == 1)) {
                    k++;                                   // one more flight so the storey lands at the door's end
                }
                int hiU = w + rs;                          // first u of the high landing this storey
                int ys = floor;
                for (int i = 0; i < k; i++) {
                    int n = rise / k + (i >= k - rise % k ? 1 : 0);      // longer flights last (the roof deck)
                    F f = new F();
                    f.g = g;
                    f.lane = g % 2;
                    f.d = f.lane == 0 ? 1 : -1;
                    f.floor = floor;
                    f.n = n;
                    f.ys = ys;
                    f.hiU = hiU;
                    f.us = new int[n];
                    for (int t = 0; t < n; t++) {
                        // lane 0 flush with the high landing, lane 1 with the low one
                        f.us[t] = f.lane == 0 ? hiU - n + t : w + n - 1 - t;
                    }
                    flights.add(f);
                    ys += n;
                    g++;
                }
                if (inset.getOrDefault(floor, 0) != 0) {  // the inset band is wall this storey
                    for (int y = floor - 1; y < next; y++) {
                        for (int u = hiU + w; u < lg; u++) {
                            for (int v = 0; v < sh; v++) {
                                put(u, v, y, p.wall);
                            }
                        }
                    }
                }
            }
            for (int i = 0; i + 1 < flights.size(); i++) {
                F a = flights.get(i);
                F b = flights.get(i + 1);
                if (a.n + b.n < HEADROOM + 1) {
                    throw refuse("flights of %d + %d treads at y%d leave < %d headroom over the lane below; use a longer run "
                                 + "(core +%d in %s) or fewer storey splits",
                                 a.n, b.n, a.ys, HEADROOM, HEADROOM + 1 - a.n - b.n, axis);
                }
            }
            F last = flights.get(flights.size() - 1);
            if (last.n < HEADROOM + 1) {
                throw refuse("last flight to the roof has %d treads; the roof deck needs >= %d over the landing below",
                             last.n, HEADROOM + 1);
            }

            // surfaces: treads, landing extensions, end landings, sconces
            for (F f : flights) {
                int lane = f.lane;
                int meta = upMeta(axisZ, flip ? -f.d : f.d);
                int fTop = f.ys + f.n - 1;
                for (int i = 0; i < f.n; i++) {
                    for (int v = laneLo(lane); v < laneHi(lane); v++) {
                        put(f.us[i], v, f.ys + i, p.tread + ":" + meta);
                    }
                }
                // landing extension under the start: run cells before the first tread at ys - 1
                int eLo = lane == 0 ? w : f.us[0] + 1;
                int eHi = lane == 0 ? f.us[0] : f.hiU;
                for (int u = eLo; u < eHi; u++) {
                    for (int v = laneLo(lane); v < laneHi(lane); v++) {
                        put(u, v, f.ys - 1, p.floor);
                    }
                }
                // arrival landing (full width, w deep) at the top, departure landing at ys - 1
                int aLo = lane == 0 ? f.hiU : 0;
                int dLo = lane == 0 ? 0 : f.hiU;
                for (int u = aLo; u < aLo + w; u++) {
                    for (int v = 0; v < sh; v++) {
                        put(u, v, fTop, p.floor);
                    }
                }
                for (int u = dLo; u < dLo + w; u++) {
                    for (int v = 0; v < sh; v++) {
                        put(u, v, f.ys - 1, p.floor);
                    }
                }
                // a sconce on the end wall at the arrival landing, stand + 2
                int ue = lane == 0 ? f.hiU + w - 1 : 0;
                if (f.g == 0) {                            // the bottom floor's departure landing
                    put(lane == 0 ? 0 : f.hiU + w - 1, sh / 2, f.ys + 2, new Light(lane == 0 ? 1 : -1));
                }
                put(ue, sh / 2, fTop + 3, new Light(lane == 0 ? -1 : 1));
            }
            // divider: the run band, bottom floor to the bulkhead
            for (int y = fl.get(0) - 1; y <= top; y++) {
                for (int u = w; u < w + run; u++) {
                    for (int v = w; v < sh - w; v++) {
                        put(u, v, y, p.wall);
                    }
                }
            }
            // solid under the lowest surfaces (no walk-under or fall-in pockets), except the bottom
            // floor's departure landing
            Map<Long, int[]> lowest = new HashMap<>();     // column -> {x, z, min y}
            for (Map.Entry<Pos, Object> e : m.entrySet()) {
                Object b = e.getValue();
                if (b instanceof String && !AIR.equals(b)) {
                    Pos q = e.getKey();
                    int[] col = lowest.get(column(q.x, q.z));
                    if (col == null) {
                        lowest.put(column(q.x, q.z), new int[] {q.x, q.z, q.y});
                    }
                    else if (q.y < col[2]) {
                        col[2] = q.y;
                    }
                }
            }
            Set<Long> dep0 = new HashSet<>();
            for (int u = 0; u < w; u++) {
                for (int v = 0; v < sh; v++) {
                    int[] c = cell(u, v, axisZ);
                    dep0.add(column(c[0], c[1]));
                }
            }
            for (Map.Entry<Long, int[]> e : lowest.entrySet()) {
                if (dep0.contains(e.getKey())) {
                    continue;
                }
                int[] col = e.getValue();
                for (int y = fl.get(0); y < col[2]; y++) {
                    m.put(new Pos(col[0], y, col[1]), p.wall);
                }
            }
            // roof: deck every non-tread cell at roof - 1, a parapet and rail on deck cells beside a lower tread
            Set<Long> treadCells = new HashSet<>();
            for (int u : last.us) {
                for (int v = laneLo(last.lane); v < laneHi(last.lane); v++) {
                    int[] c = cell(u, v, axisZ);
                    treadCells.add(column(c[0], c[1]));
                }
            }
            for (int u = 0; u < lg; u++) {
                for (int v = 0; v < sh; v++) {
                    int[] c = cell(u, v, axisZ);
                    Pos deck = new Pos(c[0], roof - 1, c[1]);
                    Object b = m.get(deck);
                    if (!treadCells.contains(column(c[0], c[1])) && (b == null || AIR.equals(b))) {
                        m.put(deck, p.floor);
                    }
                }
            }
            Map<Long, Integer> highestTread = new HashMap<>();
            for (Map.Entry<Pos, Object> e : m.entrySet()) {
                if (isTread(e.getValue())) {
                    Pos q = e.getKey();
                    Integer h = highestTread.get(column(q.x, q.z));
                    if (h == null || q.y > h) {
                        highestTread.put(column(q.x, q.z), q.y);
                    }
                }
            }
            for (int u = 0; u < lg; u++) {
                for (int v = laneLo(last.lane); v < laneHi(last.lane); v++) {
                    int[] c = cell(u, v, axisZ);
                    if (treadCells.contains(column(c[0], c[1]))) {
                        continue;
                    }
                    for (int du = -1; du <= 1; du += 2) {
                        if (u + du < 0 || u + du >= lg) {
                            continue;
                        }
                        int[] nb = cell(u + du, v, axisZ);
                        if (!treadCells.contains(column(nb[0], nb[1]))) {
                            continue;
                        }
                        Integer ty = highestTread.get(column(nb[0], nb[1]));
                        if (ty != null && ty < roof - 1) {
                            m.put(new Pos(c[0], roof, c[1]), p.floor);
                            m.put(new Pos(c[0], roof + 1, c[1]), p.rail);
                        }
                    }
                }
            }
            // doors
            for (int floor : fl) {
                if (!in.doors.containsKey(floor)) {
                    throw refuse("no door given for floor y%d (rules.DOOR_EVERY_FLOOR)", floor);
                }
            }
            // which end each floor's landing is on
            Map<Integer, Integer> landEnd = new HashMap<>();
            for (F f : flights) {
                if (f.ys == f.floor) {
                    landEnd.put(f.floor, f.lane == 0 ? 0 : 1);
                }
            }
            landEnd.put(roof, last.lane == 0 ? 1 : 0);
            for (Map.Entry<Integer, List<int[]>> e : in.doors.entrySet()) {
                int floor = e.getKey();
                Integer end = landEnd.get(floor);
                if (end == null) {
                    // Not in design.py, which fails with a KeyError.
                    throw refuse("door given for y%d, which is not one of the floors", floor);
                }
                int hiU = w + run;
                for (F f : flights) {
                    if (f.floor == floor) {
                        hiU = f.hiU;
                        break;
                    }
                }
                int zLo = end == 0 ? 0 : hiU;
                for (int[] d : e.getValue()) {
                    int u = uOf(clampX(d[0]), clampZ(d[1]), axisZ);
                    if (u < zLo || u >= zLo + w) {
                        throw refuse("door (%d,%d) at y%d opens onto the run/well, not the %s landing (u %d..%d)",
                                     d[0], d[1], floor, end == 0 ? "lo" : "hi", zLo, zLo + w - 1);
                    }
                    for (int y = floor; y < floor + 3; y++) {
                        m.put(new Pos(d[0], y, d[1]), AIR);
                    }
                }
            }
            SortedMap<Pos, String> out = new TreeMap<>();
            for (Map.Entry<Pos, Object> e : m.entrySet()) {
                Object b = e.getValue();
                if (b instanceof Light) {                  // a sconce facing into the core from the end wall
                    int dir = ((Light) b).dir;
                    int face = sconceFacing(axisZ, flip ? -dir : dir);
                    b = p.sconce + ":" + (face * 4 + 3);
                }
                out.put(e.getKey(), (String) b);
            }
            if (in.walls) {
                for (int y = fl.get(0) - 1; y <= top + 1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            boolean ring = x == x0 || x == x1 || z == z0 || z == z1;
                            Pos q = new Pos(x, y, z);
                            if ((ring || y == top + 1) && !out.containsKey(q)) {
                                out.put(q, p.wall);
                            }
                        }
                    }
                }
            }
            List<Flight> report = new ArrayList<>();
            for (F f : flights) {
                report.add(new Flight(f.floor, f.ys, f.n, f.lane));
            }
            return new Plan(out, axis, run, Collections.unmodifiableList(report), top);
        }

        private boolean isTread(Object b) {
            return b instanceof String && ((String) b).startsWith(p.tread + ":");
        }

        private int laneLo(int lane) {
            return lane == 0 ? 0 : sh - w;
        }

        private int laneHi(int lane) {
            return lane == 0 ? w : sh;
        }
    }

    private static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Stair meta for climbing in direction d along the axis: 0 E, 1 W, 2 S, 3 N. */
    private static int upMeta(boolean axisZ, int d) {
        if (axisZ) {
            return d > 0 ? 2 : 3;
        }
        return d > 0 ? 0 : 1;
    }

    /** Sconce facing into the core from an end wall, with dir pointing into the core: S 0, W 1, N 2, E 3. */
    private static int sconceFacing(boolean axisZ, int dir) {
        if (axisZ) {
            return dir > 0 ? 0 : 2;
        }
        return dir > 0 ? 3 : 1;
    }

    private static DesignException refuse(String format, Object... args) {
        return new DesignException(String.format(Locale.ROOT, format, args));
    }
}
