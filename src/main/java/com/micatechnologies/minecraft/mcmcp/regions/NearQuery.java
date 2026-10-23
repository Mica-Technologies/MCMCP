package com.micatechnologies.minecraft.mcmcp.regions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The proximity join behind server_find_blocks' {@code near} option (#125): whether a cell has (or
 * lacks) a block of another kind within a radius. "Distance" is Chebyshev, the box distance: a radius
 * of 1 is the 26 cells touching the centre, faces, edges and corners. With {@code faces}, only the
 * cells straight along an axis count: radius 1 is the 6 cells sharing a face, the "exposed surface"
 * question (#135).
 *
 * <p>Imports no Minecraft class. The world is reached through a {@link Neighbourhood}, which the tool
 * implements over loaded chunks and a test implements over a map, so the scan order, early stopping
 * and the unloaded-neighbour verdict are tested without a game.
 */
public final class NearQuery {

    /** The largest radius taken. A neighbourhood is (2r + 1)^3 cells, read per candidate. */
    public static final int MAX_RADIUS = 8;
    /** Neighbour positions reported per hit in mode "with". */
    public static final int MAX_REPORTED = 3;

    /** What {@link Neighbourhood#probe} says of one cell. */
    public static final int NO_MATCH = 0;
    public static final int MATCH = 1;
    public static final int UNLOADED = -1;

    public enum Mode {
        /** Keep the candidates with a matching neighbour. */
        WITH,
        /** Keep the candidates with none. */
        WITHOUT;

        /** {@code null} for anything but "with" or "without". */
        public static Mode parse(String text) {
            if (text == null) {
                return null;
            }
            String mode = text.trim().toLowerCase(Locale.ROOT);
            return "with".equals(mode) ? WITH : "without".equals(mode) ? WITHOUT : null;
        }
    }

    /** What a candidate's neighbourhood decided. */
    public enum Verdict {
        /** The candidate is a result. */
        LISTED,
        /** The candidate is not. */
        DROPPED,
        /**
         * No matching neighbour among the loaded cells, but some of the neighbourhood is in an unloaded
         * chunk, so the answer could go either way.
         */
        UNRESOLVED
    }

    /** One cell of the world as the query sees it. */
    public interface Neighbourhood {
        /** {@link #MATCH}, {@link #NO_MATCH} or {@link #UNLOADED} for the cell at x, y, z (y in 0..255). */
        int probe(int x, int y, int z);
    }

    /** One candidate's outcome: the verdict and, for mode "with", the matching neighbours found. */
    public static final class Outcome {
        public final Verdict verdict;
        /** Up to {@link #MAX_REPORTED} positions as {x, y, z}, nearest first. Empty unless LISTED in "with". */
        public final List<int[]> found;

        Outcome(Verdict verdict, List<int[]> found) {
            this.verdict = verdict;
            this.found = found;
        }
    }

    private final Mode mode;
    private final int radius;
    private final boolean faces;
    /** Offsets as dx, dy, dz triples, nearest first, the centre left out. */
    private final int[] offsets;

    public NearQuery(int radius, Mode mode) {
        this(radius, mode, false);
    }

    public NearQuery(int radius, Mode mode, boolean faces) {
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius " + radius + " is outside 1.." + MAX_RADIUS);
        }
        this.radius = radius;
        this.mode = mode;
        this.faces = faces;
        this.offsets = faces ? axisOffsets(radius) : offsets(radius);
    }

    /** The id {@link #namesAir} tests patterns against. */
    public static final String AIR = "minecraft:air";

    /**
     * Whether block patterns ask for air (#135). Air counts only when a pattern names it:
     * "minecraft:air", "*:air", "*air". A bare namespace or "*" does not sweep it in, or "anything from
     * minecraft: within 1" would match every cell with an open side.
     */
    public static boolean namesAir(List<String> patterns) {
        for (String pattern : patterns) {
            String p = pattern.trim().toLowerCase(Locale.ROOT);
            if (p.contains("air") && NbtProjection.matches(AIR, Collections.singletonList(p))) {
                return true;
            }
        }
        return false;
    }

    public Mode mode() {
        return mode;
    }

    public int radius() {
        return radius;
    }

    /** Whether only the cells straight along an axis count. */
    public boolean faces() {
        return faces;
    }

    /**
     * Decides one candidate at x, y, z. The candidate's own cell is never its own neighbour, so "lamps
     * next to a lamp" does not find every lamp. Cells above or below the world match nothing and are
     * not unloaded. Stops at the first match in mode "without", and at the {@link #MAX_REPORTED}th in
     * mode "with".
     */
    public Outcome check(int x, int y, int z, Neighbourhood world) {
        int wanted = mode == Mode.WITH ? MAX_REPORTED : 1;
        List<int[]> found = new ArrayList<>(wanted);
        boolean unloaded = false;
        for (int i = 0; i < offsets.length && found.size() < wanted; i += 3) {
            int ny = y + offsets[i + 1];
            if (ny < 0 || ny > 255) {
                continue;
            }
            int nx = x + offsets[i];
            int nz = z + offsets[i + 2];
            int seen = world.probe(nx, ny, nz);
            if (seen == MATCH) {
                found.add(new int[] {nx, ny, nz});
            }
            else if (seen == UNLOADED) {
                unloaded = true;
            }
        }
        if (!found.isEmpty()) {
            return mode == Mode.WITH ? new Outcome(Verdict.LISTED, found)
                : new Outcome(Verdict.DROPPED, Collections.<int[]>emptyList());
        }
        if (unloaded) {
            return new Outcome(Verdict.UNRESOLVED, Collections.<int[]>emptyList());
        }
        return new Outcome(mode == Mode.WITH ? Verdict.DROPPED : Verdict.LISTED, Collections.<int[]>emptyList());
    }

    /** The offsets straight along an axis within {@code radius}, nearest first, then x, y, z. */
    static int[] axisOffsets(int radius) {
        int[] flat = new int[radius * 6 * 3];
        int n = 0;
        for (int d = 1; d <= radius; d++) {
            int[][] ring = {{-d, 0, 0}, {0, -d, 0}, {0, 0, -d}, {0, 0, d}, {0, d, 0}, {d, 0, 0}};
            for (int[] offset : ring) {
                flat[n++] = offset[0];
                flat[n++] = offset[1];
                flat[n++] = offset[2];
            }
        }
        return flat;
    }

    /** Every offset within {@code radius}, by box distance, then straight-line distance, then x, y, z. */
    static int[] offsets(int radius) {
        int side = 2 * radius + 1;
        Integer[][] all = new Integer[side * side * side - 1][];
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        all[n++] = new Integer[] {dx, dy, dz};
                    }
                }
            }
        }
        Arrays.sort(all, (a, b) -> {
            int box = Integer.compare(chebyshev(a), chebyshev(b));
            if (box != 0) {
                return box;
            }
            int straight = Integer.compare(squared(a), squared(b));
            if (straight != 0) {
                return straight;
            }
            for (int i = 0; i < 3; i++) {
                int axis = Integer.compare(a[i], b[i]);
                if (axis != 0) {
                    return axis;
                }
            }
            return 0;
        });
        int[] flat = new int[all.length * 3];
        for (int i = 0; i < all.length; i++) {
            flat[i * 3] = all[i][0];
            flat[i * 3 + 1] = all[i][1];
            flat[i * 3 + 2] = all[i][2];
        }
        return flat;
    }

    private static int squared(Integer[] offset) {
        return offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2];
    }

    private static int chebyshev(Integer[] offset) {
        return Math.max(Math.abs(offset[0]), Math.max(Math.abs(offset[1]), Math.abs(offset[2])));
    }
}
