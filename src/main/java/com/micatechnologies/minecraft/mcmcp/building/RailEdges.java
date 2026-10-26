package com.micatechnologies.minecraft.mcmcp.building;

/**
 * Which edges of a railing face a drop (#138 plan, Phase 2b; the UIA builder's {@code swap_rails.py}, 615
 * cells verified live).
 *
 * <p>An edge faces a drop when the neighbouring column has nothing solid in the {@code depth - 1} cells
 * below the rail's level: a terrace's outside, an atrium's void. A cell with no such side is a ring
 * corner, and the diagonal decides: both edges toward a dropping diagonal. A cell with neither is a
 * short step, not a drop, and is left alone.
 *
 * <p>Edges as a mask, north 1, east 2, south 4, west 8: CSM's railing metadata, and its boolean
 * {@code north} / {@code east} / {@code south} / {@code west} properties.
 *
 * <p>Pure.
 */
public final class RailEdges {

    public static final int NORTH = 1;
    public static final int EAST = 2;
    public static final int SOUTH = 4;
    public static final int WEST = 8;

    /** Whether a cell holds something a person could not fall through. */
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    private RailEdges() {
    }

    /** The edge mask for a rail at a cell; 0 when no side or diagonal drops. */
    public static int edges(Solid solid, int x, int y, int z, int depth) {
        int mask = 0;
        if (drops(solid, x, y, z, 0, -1, depth)) {
            mask |= NORTH;
        }
        if (drops(solid, x, y, z, 1, 0, depth)) {
            mask |= EAST;
        }
        if (drops(solid, x, y, z, 0, 1, depth)) {
            mask |= SOUTH;
        }
        if (drops(solid, x, y, z, -1, 0, depth)) {
            mask |= WEST;
        }
        if (mask == 0) {
            if (drops(solid, x, y, z, 1, 1, depth)) {
                mask |= EAST | SOUTH;
            }
            if (drops(solid, x, y, z, 1, -1, depth)) {
                mask |= EAST | NORTH;
            }
            if (drops(solid, x, y, z, -1, 1, depth)) {
                mask |= WEST | SOUTH;
            }
            if (drops(solid, x, y, z, -1, -1, depth)) {
                mask |= WEST | NORTH;
            }
        }
        return mask;
    }

    private static boolean drops(Solid solid, int x, int y, int z, int dx, int dz, int depth) {
        for (int yy = y - (depth - 1); yy < y; yy++) {
            if (solid.at(x + dx, yy, z + dz)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Drop depth for a rail standing on a parapet: nothing solid in the two cells below the rail beside
     * it. One cell would also count the deck behind the parapet (its walking cell is air), and give the
     * rail every edge; the deck's own block is the second cell down (the UIA builder's review, fixture
     * stairs_alto1873_N_csmrail, 2026-10-26).
     */
    public static final int PARAPET_DEPTH = 3;

    /**
     * What is solid in a planned build, for its rails: anything planned but air, lighting helpers and the
     * rail itself; outside the plan, solid (a stair core's walls enclose it).
     */
    public static Solid ofPlan(final java.util.Map<Pos, String> plan, final String rail) {
        return (x, y, z) -> {
            String block = plan.get(new Pos(x, y, z));
            return block == null || !(block.startsWith("minecraft:air") || block.contains("lightupair")
                || block.equals(rail) || block.startsWith(rail + ":"));
        };
    }

    /** "NE", "SW"... as a mask. */
    public static int parse(String edges) {
        int mask = 0;
        for (char c : edges.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            switch (c) {
                case 'N':
                    mask |= NORTH;
                    break;
                case 'E':
                    mask |= EAST;
                    break;
                case 'S':
                    mask |= SOUTH;
                    break;
                case 'W':
                    mask |= WEST;
                    break;
                default:
                    throw new IllegalArgumentException("edges is a string of N, E, S and W, not '" + edges + "'.");
            }
        }
        return mask;
    }
}
