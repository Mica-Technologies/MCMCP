package com.micatechnologies.minecraft.mcmcp.building;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The stair compliance audit: a port of the UIA knowledge base's {@code tools/stairs/audit.py}. It reads
 * a stair core as a map of positions to block strings (the box plus a one-cell ring outside it) and
 * checks every stair rule, returning the failures by rule:
 *
 * <ul>
 *   <li>WIDTH: every flight at least the given width.</li>
 *   <li>STEP: every tread rises exactly one: ahead is a stair one up or a flush landing, behind a stair
 *       one down or a floor.</li>
 *   <li>HEAD: three clear cells over every tread, and over every landing stand cell.</li>
 *   <li>LANDING: a landing at least the flight's width deep along travel, top and bottom of each flight,
 *       door cells not counting.</li>
 *   <li>GAP: no stand cell beside an open drop without a guard.</li>
 *   <li>LIGHT: a light within 6 cells, -1..+4 high, of every landing.</li>
 *   <li>DOOR: a door at every floor, with an exit sign and a floor-ID panel near it when signs are
 *       checked.</li>
 *   <li>CONT: a walker, two high and never jumping, reaches every floor's door from the lowest's.</li>
 *   <li>TWO: across several cores, every floor level has doors in at least two ({@link #twoStairs}).</li>
 * </ul>
 *
 * <p>Blocks are classified by keyword, exactly as audit.py does, because the audit has to judge modded
 * blocks it has never seen: anything named like a sconce or a sign is walked through, anything named
 * like a pane or fence guards an edge. A position missing from the grid reads {@code "?"}, which is
 * neither passable nor solid.
 *
 * <p>Imports no Minecraft class. The order of the grid is the order audit.py's dict had, and decides the
 * order failures and flights come out in; a {@link LinkedHashMap} keeps it.
 */
public final class StairAudit {

    public static final String UNKNOWN = "?";
    /** rules.LIGHT_REACH: a light within this many cells of a landing. */
    public static final int LIGHT_REACH = 6;

    static final String[] PASS_KW = {"sconce", "sign", "panel", "pull", "lightupair", "cehalo", "cesquare", "door", "torch",
        "button", "pressure_plate", "lever", "carpet", "detector", "strobe", "horn", "speaker", "eep", "standpipe",
        "snow_layer", "thermostat"};
    static final String[] LIGHT_KW = {"sconce", "lamp", "light", "glowstone", "lantern", "torch", "fluorescent", "led"};
    static final String[] GUARD_KW = {"pane", "fence", "rail", "wall", "bars", "glass"};

    /** Stair meta & 3 to the step {dx, dz} it ascends along: 0 E, 1 W, 2 S, 3 N. */
    private static final int[][] D = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** One rule broken. Coordinates are null where audit.py reported None (a whole floor). */
    public static final class Failure {
        public final String rule;
        public final Integer x;
        public final Integer y;
        public final Integer z;
        public final String detail;

        Failure(String rule, Integer x, Integer y, Integer z, String detail) {
            this.rule = rule;
            this.x = x;
            this.y = y;
            this.z = z;
            this.detail = detail;
        }

        Failure(String rule, Pos at, String detail) {
            this(rule, at.x, at.y, at.z, detail);
        }

        @Override
        public String toString() {
            return rule + " (" + (x == null ? "None" : x) + ", " + (y == null ? "None" : y) + ", "
                   + (z == null ? "None" : z) + ") " + detail;
        }
    }

    /** A flight found in the grid. */
    public static final class FlightInfo {
        /** Ascending direction, stair meta & 3. */
        public final int d;
        public final int ylo;
        public final int yhi;
        /** The narrowest row's width. */
        public final int w;
        /** Rows, which is treads along travel. */
        public final int n;
        /** Its least cell, x then y then z. */
        public final Pos at;

        FlightInfo(int d, int ylo, int yhi, int w, int n, Pos at) {
            this.d = d;
            this.ylo = ylo;
            this.yhi = yhi;
            this.w = w;
            this.n = n;
            this.at = at;
        }
    }

    /** A stair core to audit. */
    public static final class Core {
        public final String name;
        /** Enclosure walls, inclusive. */
        public final int x0;
        public final int z0;
        public final int x1;
        public final int z1;
        /** The levels checked. */
        public final int y0;
        public final int y1;
        /** The blocks, the box plus a one-cell ring outside, y0 - 1 to y1 + 1 at least. */
        public final Map<Pos, String> grid;
        /**
         * Open public stair: boxes {x0, z0, x1, z1, ylo, yhi} the stand-cell checks are limited to;
         * null for an enclosed core.
         */
        public List<int[]> zones;
        /** An open stair between floors rather than an enclosure: no DOOR, CONT walks landing to landing. */
        public boolean open;

        public Core(String name, int x0, int z0, int x1, int z1, int y0, int y1, Map<Pos, String> grid) {
            this.name = name;
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            this.y0 = y0;
            this.y1 = y1;
            this.grid = grid;
        }

        String b(int x, int y, int z) {
            String s = grid.get(new Pos(x, y, z));
            return s == null ? UNKNOWN : s;
        }

        boolean inside(int x, int z) {
            return x0 < x && x < x1 && z0 < z && z < z1;
        }

        /** Feet cell: two passable, standing on something solid or a stair that is not a guard. */
        boolean stand(int x, int y, int z) {
            String s = b(x, y - 1, z);
            return passable(b(x, y, z)) && passable(b(x, y + 1, z)) && !passable(s) && !UNKNOWN.equals(s)
                   && !anyOf(s, GUARD_KW);
        }
    }

    /** What an audit found. */
    public static final class Result {
        public final List<Failure> failures;
        /** The floors checked: those asked for, or the door levels found. */
        public final List<Integer> floors;
        public final List<FlightInfo> flights;
        /** Door cells found per level. Empty for an open stair. */
        public final Map<Integer, List<Pos>> doors;
        /** The floors that count as having a door here, for {@link #twoStairs}. */
        public final Set<Integer> doorLevels;

        Result(List<Failure> failures, List<Integer> floors, List<FlightInfo> flights, Map<Integer, List<Pos>> doors,
               Set<Integer> doorLevels) {
            this.failures = failures;
            this.floors = floors;
            this.flights = flights;
            this.doors = doors;
            this.doorLevels = doorLevels;
        }

        /** The failures grouped by rule, rules in name order, each group in the order found. */
        public Map<String, List<Failure>> byRule() {
            Map<String, List<Failure>> out = new TreeMap<>();
            for (Failure f : failures) {
                out.computeIfAbsent(f.rule, k -> new ArrayList<>()).add(f);
            }
            return out;
        }
    }

    private StairAudit() {
    }

    // ------------------------------------------------------------------
    // Block classes
    // ------------------------------------------------------------------

    static boolean anyOf(String s, String[] keywords) {
        for (String k : keywords) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** The meta of {@code "ns:id:meta"}, 0 for a bare id or a meta that is not a number. */
    static int meta(String s) {
        int colons = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ':') {
                colons++;
            }
        }
        if (colons != 2) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(s.lastIndexOf(':') + 1).trim());
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean isAir(String s) {
        return s == null || "minecraft:air".equals(s);
    }

    static boolean isStair(String s) {
        return s != null && s.contains("stairs");
    }

    static boolean isDoor(String s) {
        return s != null && s.contains("door") && !s.contains("trapdoor");
    }

    /** Walked through: air, or a thin fitting (a sconce, a sign, a door), but never a stair or a lit block. */
    static boolean passable(String s) {
        if (isAir(s)) {
            return true;
        }
        if (s.contains("illumin")) {
            return false;
        }
        return !isStair(s) && anyOf(s, PASS_KW) && !s.contains("exit_sign") || isDoor(s);
    }

    static boolean solid(String s) {
        return !UNKNOWN.equals(s) && !passable(s) && !isStair(s);
    }

    // ------------------------------------------------------------------
    // The audit
    // ------------------------------------------------------------------

    /**
     * Audits one core.
     *
     * @param floorsReq the floor levels to check; empty or null for the door levels found
     * @param signs     whether DOOR also wants an exit sign and a floor-ID panel at each door
     */
    public static Result audit(Core c, int width, List<Integer> floorsReq, boolean signs) {
        List<Failure> fails = new ArrayList<>();
        int x0 = c.x0;
        int z0 = c.z0;
        int x1 = c.x1;
        int z1 = c.z1;
        int y0 = c.y0;
        int y1 = c.y1;

        Map<Pos, Integer> treads = new LinkedHashMap<>();
        List<Map.Entry<Pos, String>> lights = new ArrayList<>();
        for (Map.Entry<Pos, String> e : c.grid.entrySet()) {
            Pos q = e.getKey();
            String s = e.getValue();
            if (isStair(s) && (meta(s) & 4) == 0 && c.inside(q.x, q.z) && y0 <= q.y && q.y <= y1) {
                treads.put(q, meta(s) & 3);
            }
            if (s != null && anyOf(s, LIGHT_KW) && !s.contains("lightupair")) {
                lights.add(e);
            }
        }
        // flights: union same-facing treads across (same y) and along (d, +1)
        Map<Pos, Pos> par = new HashMap<>();
        for (Pos q : treads.keySet()) {
            par.put(q, q);
        }
        for (Map.Entry<Pos, Integer> e : treads.entrySet()) {
            Pos q = e.getKey();
            int d = e.getValue();
            int dx = D[d][0];
            int dz = D[d][1];
            Pos[] near = {new Pos(q.x + dz, q.y, q.z + dx), new Pos(q.x - dz, q.y, q.z - dx),
                new Pos(q.x + dx, q.y + 1, q.z + dz)};
            for (Pos n : near) {
                Integer nd = treads.get(n);
                if (nd != null && nd == d) {
                    par.put(root(par, q), root(par, n));
                }
            }
        }
        Map<Pos, List<Pos>> groups = new LinkedHashMap<>();
        for (Pos q : treads.keySet()) {
            groups.computeIfAbsent(root(par, q), k -> new ArrayList<>()).add(q);
        }
        List<FlightInfo> flights = new ArrayList<>();
        for (List<Pos> cells : groups.values()) {
            int d = treads.get(cells.get(0));
            int dx = D[d][0];
            int dz = D[d][1];
            TreeMap<Integer, List<Pos>> rows = new TreeMap<>();
            for (Pos q : cells) {
                rows.computeIfAbsent(q.y, k -> new ArrayList<>()).add(q);
            }
            int w = Integer.MAX_VALUE;
            for (List<Pos> r : rows.values()) {
                w = Math.min(w, r.size());
            }
            Pos least = Collections.min(cells);
            flights.add(new FlightInfo(d, rows.firstKey(), rows.lastKey(), w, rows.size(), least));
            if (w < width) {
                fails.add(new Failure("WIDTH", least, fmt("flight %d wide < %d", w, width)));
            }
            for (Pos q : cells) {
                String up = c.b(q.x + dx, q.y + 1, q.z + dz);
                String upa = c.b(q.x + dx, q.y, q.z + dz);
                if (!(isStair(up) || solid(upa) && passable(up))) {
                    fails.add(new Failure("STEP", q, fmt("up: ahead y=%s y+1=%s", upa, up)));
                }
                String bk = c.b(q.x - dx, q.y, q.z - dz);
                String bkb = c.b(q.x - dx, q.y - 1, q.z - dz);
                if (!(isStair(bkb) || passable(bk) && !passable(bkb))) {
                    fails.add(new Failure("STEP", q, fmt("down: behind y=%s y-1=%s", bk, bkb)));
                }
                for (int h = 1; h <= 3; h++) {
                    String above = c.b(q.x, q.y + h, q.z);
                    if (!passable(above)) {
                        fails.add(new Failure("HEAD", q, fmt("tread clear %d < 3 (%s at y+%d)", h - 1, above, h)));
                        break;
                    }
                }
            }
            // LANDING depth, top (stand y + 1) and bottom (stand ylo), and a LIGHT near each
            for (int end = 0; end < 2; end++) {
                boolean isTop = end == 0;
                List<Pos> row = isTop ? rows.lastEntry().getValue() : rows.firstEntry().getValue();
                int sgn = isTop ? 1 : -1;
                int sy = isTop ? rows.lastKey() + 1 : rows.firstKey();
                String name = isTop ? "top" : "bottom";
                int minDep = Integer.MAX_VALUE;
                for (Pos q : row) {
                    int k = 0;
                    while (k < 12) {
                        int nx = q.x + sgn * dx * (k + 1);
                        int nz = q.z + sgn * dz * (k + 1);
                        if (!c.inside(nx, nz)
                            && !(x0 <= nx && nx <= x1 && z0 <= nz && nz <= z1 && passable(c.b(nx, sy, nz)))) {
                            break;
                        }
                        if (isDoor(c.b(nx, sy, nz)) || !c.stand(nx, sy, nz) || !c.inside(nx, nz)) {
                            break;
                        }
                        k++;
                    }
                    minDep = Math.min(minDep, k);
                }
                int need = Math.max(width, w);
                if (minDep < need) {
                    fails.add(new Failure("LANDING", row.get(0),
                                          fmt("%s landing (stand y%d) %d deep < %d", name, sy, minDep, need)));
                }
                int lx = row.get(0).x + sgn * dx * 2;
                int lz = row.get(0).z + sgn * dz * 2;
                boolean lit = false;
                for (Map.Entry<Pos, String> e : lights) {
                    Pos p = e.getKey();
                    if (Math.abs(p.x - lx) <= LIGHT_REACH && Math.abs(p.z - lz) <= LIGHT_REACH && -1 <= p.y - sy
                        && p.y - sy <= 4) {
                        lit = true;
                        break;
                    }
                }
                if (!lit) {
                    fails.add(new Failure("LIGHT", lx, sy, lz, fmt("%s landing has no light", name)));
                }
            }
        }
        // stand cells inside the core
        Set<Pos> stands = new LinkedHashSet<>();
        for (Pos q : c.grid.keySet()) {
            if (c.inside(q.x, q.z) && y0 <= q.y && q.y <= y1 && c.stand(q.x, q.y, q.z) && inZones(c.zones, q)) {
                stands.add(q);
            }
        }
        for (Pos q : stands) {
            if (!isStair(c.b(q.x, q.y - 1, q.z))) {
                String above = c.b(q.x, q.y + 2, q.z);
                if (!passable(above)) {
                    fails.add(new Failure("HEAD", q, fmt("landing clear 2 < 3 (%s)", above)));
                }
            }
            // GAP: a neighbour at walking level that drops away. From solid support any drop that is not
            // onto a stair; from a tread, a drop of 2 or more (one half step down is the flight itself)
            boolean onStair = isStair(c.b(q.x, q.y - 1, q.z));
            for (int[] dir : D) {
                Pos n = new Pos(q.x + dir[0], q.y, q.z + dir[1]);
                String nb = c.b(n.x, n.y, n.z);
                if (!c.inside(n.x, n.z) || !passable(nb) || isDoor(nb)) {
                    continue;
                }
                int k = onStair ? 2 : 1;
                boolean open = true;
                for (int j = 1; j <= k; j++) {
                    if (!passable(c.b(n.x, q.y - j, n.z))) {
                        open = false;
                        break;
                    }
                }
                if (open && !isStair(c.b(n.x, q.y - k - 1, n.z))) {
                    fails.add(new Failure("GAP", q, "open drop/gap beside at " + n + " (no floor, stair, wall or guard)"));
                }
            }
        }
        // DOORS: perimeter cells (not corners) with a stand inside and a stand or an opening outside
        Map<Integer, List<Pos>> doors = new LinkedHashMap<>();
        for (Pos q : c.grid.keySet()) {
            int x = q.x;
            int y = q.y;
            int z = q.z;
            boolean onX = x == x0 || x == x1;
            boolean onZ = z == z0 || z == z1;
            if (y0 <= y && y <= y1 && x0 <= x && x <= x1 && z0 <= z && z <= z1 && (onX || onZ) && !(onX && onZ)) {
                if (passable(c.b(x, y, z)) && passable(c.b(x, y + 1, z))) {
                    int ix = x + (x == x0 ? 1 : x == x1 ? -1 : 0);
                    int iz = z + (z == z0 ? 1 : z == z1 ? -1 : 0);
                    int ox = 2 * x - ix;
                    int oz = 2 * z - iz;
                    if (stands.contains(new Pos(ix, y, iz)) || c.stand(ix, y, iz)) {
                        if (c.stand(ox, y, oz) || passable(c.b(ox, y, oz))) {
                            doors.computeIfAbsent(y, k -> new ArrayList<>()).add(q);
                        }
                    }
                }
            }
        }
        // door levels one apart are one door's two cells
        List<Integer> floors = new ArrayList<>();
        if (floorsReq != null && !floorsReq.isEmpty()) {
            floors.addAll(floorsReq);
        }
        else {
            for (int y : new TreeSet<>(doors.keySet())) {
                if (!doors.containsKey(y - 1)) {
                    floors.add(y);
                }
            }
        }
        if (c.open) {
            return auditOpen(c, stands, floors, fails, flights);
        }
        Set<Integer> doorLevels = new LinkedHashSet<>();
        for (int fy : floors) {
            List<Pos> dl = doors.get(fy);
            if (dl == null || dl.isEmpty()) {
                fails.add(new Failure("DOOR", null, fy, null, fmt("no door at floor y%d", fy)));
                continue;
            }
            doorLevels.add(fy);
            if (!signs) {
                continue;
            }
            double cx = 0;
            double cz = 0;
            for (Pos p : dl) {
                cx += p.x;
                cz += p.z;
            }
            cx /= dl.size();
            cz /= dl.size();
            boolean exit = false;
            boolean floorId = false;
            for (Map.Entry<Pos, String> e : c.grid.entrySet()) {
                Pos p = e.getKey();
                String s = e.getValue();
                if (s == null) {
                    continue;
                }
                if (Math.abs(p.x - cx) <= 4 && Math.abs(p.z - cz) <= 4 && fy - 1 <= p.y && p.y <= fy + 7
                    && s.contains("exit")) {
                    exit = true;
                }
                if (Math.abs(p.x - cx) <= 6 && Math.abs(p.z - cz) <= 6 && fy - 1 <= p.y && p.y <= fy + 4
                    && (s.contains("wayfinding") || s.contains("panel") || s.contains("sign")) && !s.contains("exit")
                    && !s.contains("lightupair")) {
                    floorId = true;
                }
            }
            if (!exit) {
                fails.add(new Failure("DOOR", dl.get(0), fmt("no exit sign near the y%d door", fy)));
            }
            if (!floorId) {
                fails.add(new Failure("DOOR", dl.get(0), fmt("no floor-ID panel/sign near the y%d door", fy)));
            }
        }
        // CONT: a walker from the lowest floor's door
        if (!floors.isEmpty() && doors.get(floors.get(0)) != null && !doors.get(floors.get(0)).isEmpty()) {
            Set<Pos> seen = walk(c, new LinkedHashSet<>(doors.get(floors.get(0))), q -> c.x0 <= q.x && q.x <= c.x1
                && c.z0 <= q.z && q.z <= c.z1 && passable(c.b(q.x, q.y, q.z)) && passable(c.b(q.x, q.y + 1, q.z))
                && !passable(c.b(q.x, q.y - 1, q.z)));
            for (int i = 1; i < floors.size(); i++) {
                int fy = floors.get(i);
                boolean reached = false;
                List<Pos> dl = doors.get(fy);
                if (dl != null) {
                    for (Pos p : dl) {
                        if (seen.contains(p)) {
                            reached = true;
                            break;
                        }
                    }
                }
                if (!reached) {
                    fails.add(new Failure("CONT", null, fy, null, fmt("floor y%d not reached from y%d", fy, floors.get(0))));
                }
            }
        }
        return new Result(fails, floors, flights, doors, doorLevels);
    }

    /** An open stair: no DOOR; CONT walks from the lowest floor's landing to every other floor's. */
    private static Result auditOpen(Core c, Set<Pos> stands, List<Integer> floors, List<Failure> fails,
                                    List<FlightInfo> flights) {
        if (!floors.isEmpty()) {
            Set<Pos> start = new LinkedHashSet<>();
            for (Pos p : stands) {
                if (p.y == floors.get(0)) {
                    start.add(p);
                }
            }
            Set<Pos> seen = walk(c, start, q -> stands.contains(q) || c.inside(q.x, q.z) && passable(c.b(q.x, q.y, q.z))
                && passable(c.b(q.x, q.y + 1, q.z)) && isStair(c.b(q.x, q.y - 1, q.z)));
            for (int i = 1; i < floors.size(); i++) {
                int fy = floors.get(i);
                boolean reached = false;
                for (Pos p : seen) {
                    if (p.y == fy) {
                        reached = true;
                        break;
                    }
                }
                if (!reached) {
                    fails.add(new Failure("CONT", null, fy, null,
                                          fmt("open stair: floor y%d not reached from y%d", fy, floors.get(0))));
                }
            }
        }
        return new Result(fails, floors, flights, new LinkedHashMap<>(), new LinkedHashSet<>(floors));
    }

    /** What a walker can stand in. */
    private interface Standable {
        boolean test(Pos q);
    }

    /** Flood fill: level, one up with headroom over the start, one down with headroom over the landing. */
    private static Set<Pos> walk(Core c, Set<Pos> seen, Standable st) {
        Deque<Pos> todo = new ArrayDeque<>(seen);
        while (!todo.isEmpty()) {
            Pos q = todo.pop();
            for (int[] dir : D) {
                for (int dy : new int[] {0, 1, -1}) {
                    Pos n = new Pos(q.x + dir[0], q.y + dy, q.z + dir[1]);
                    if (seen.contains(n) || !st.test(n)) {
                        continue;
                    }
                    if (dy == 1 && !passable(c.b(q.x, q.y + 2, q.z))) {
                        continue;
                    }
                    if (dy == -1 && !passable(c.b(n.x, n.y + 2, n.z))) {
                        continue;
                    }
                    seen.add(n);
                    todo.push(n);
                }
            }
        }
        return seen;
    }

    /**
     * TWO: with several cores, every floor level that has a door in any of them needs doors in at least
     * two. One failure per level short of that, levels ascending.
     */
    public static List<Failure> twoStairs(Map<String, Result> cores) {
        List<Failure> fails = new ArrayList<>();
        if (cores.size() < 2) {
            return fails;
        }
        TreeSet<Integer> all = new TreeSet<>();
        for (Result r : cores.values()) {
            all.addAll(r.doorLevels);
        }
        for (int y : all) {
            int n = 0;
            for (Result r : cores.values()) {
                if (r.doorLevels.contains(y)) {
                    n++;
                }
            }
            if (n < 2) {
                fails.add(new Failure("TWO", null, y, null, fmt("y%d: only %d core door(s)", y, n)));
            }
        }
        return fails;
    }

    // ------------------------------------------------------------------
    // Self audit
    // ------------------------------------------------------------------

    /**
     * The world a plan would make, as design.self_audit models it: the planned cells, the enclosure wall
     * where the plan leaves it out, and outside the box a floor slab at each floor's level and air
     * elsewhere. Covers the box plus one ring, from the bottom floor - 2 to the roof + 8.
     */
    public static Map<Pos, String> selfAuditGrid(StairDesign.Input in, StairDesign.Plan plan) {
        int x0 = in.box[0];
        int z0 = in.box[1];
        int x1 = in.box[2];
        int z1 = in.box[3];
        List<Integer> fl = in.floors;
        String wall = in.palette.wall;
        String floor = in.palette.floor;
        Set<Integer> slabs = new HashSet<>();
        for (int f : fl) {
            slabs.add(f - 1);
        }
        int roof = fl.get(fl.size() - 1);
        Map<Pos, String> grid = new LinkedHashMap<>();
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int y = fl.get(0) - 2; y < roof + StairDesign.BULKHEAD_CLEAR + 3; y++) {
                    Pos q = new Pos(x, y, z);
                    String b = plan.cells.get(q);
                    boolean inside = x0 < x && x < x1 && z0 < z && z < z1;
                    if (b == null) {
                        if (inside) {
                            b = StairDesign.AIR;
                        }
                        else if (x0 <= x && x <= x1 && z0 <= z && z <= z1) {
                            b = wall;
                        }
                        else {
                            b = slabs.contains(y) ? floor : StairDesign.AIR;
                        }
                    }
                    grid.put(q, b);
                }
            }
        }
        return grid;
    }

    /** Audits a plan as if it were built (design.self_audit): signs are not checked, they are not planned. */
    public static Result selfAudit(StairDesign.Input in, StairDesign.Plan plan) {
        List<Integer> fl = in.floors;
        Core c = new Core("plan", in.box[0], in.box[1], in.box[2], in.box[3], fl.get(0), fl.get(fl.size() - 1) + 2,
                          selfAuditGrid(in, plan));
        return audit(c, in.width, fl, false);
    }

    private static Pos root(Map<Pos, Pos> par, Pos p) {
        while (!par.get(p).equals(p)) {
            par.put(p, par.get(par.get(p)));
            p = par.get(p);
        }
        return p;
    }

    private static boolean inZones(List<int[]> zones, Pos p) {
        if (zones == null || zones.isEmpty()) {
            return true;
        }
        for (int[] zn : zones) {
            if (zn[0] <= p.x && p.x <= zn[2] && zn[1] <= p.z && p.z <= zn[3] && zn[4] <= p.y && p.y <= zn[5]) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }
}
