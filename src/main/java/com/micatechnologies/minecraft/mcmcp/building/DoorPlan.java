package com.micatechnologies.minecraft.mcmcp.building;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Which door halves go where for a set of openings: single doors, and double doors with their hinges on
 * the outer edges (#138 plan, Phase 2b; the rule every one of the UIA builder's generators re-derived).
 *
 * <p>In block-state terms, so it serves any door: the lower half carries {@code facing}, the upper half
 * {@code hinge} (and, where the block has one, a door closer). For CSM's doors that is exactly the
 * metadata the builder verified live: lower = facing's horizontal index (S0 W1 N2 E3), upper = 8 +
 * hinge right + 2 x closer.
 *
 * <p>{@code facing} is the way you walk in. A pair hinges on its outer edges: the leaf at the lower
 * coordinate along the pair is hinged left when facing north or east, right otherwise.
 *
 * <p>Pure.
 */
public final class DoorPlan {

    private DoorPlan() {
    }

    /** S, W, N, E, as 1.12's horizontal index. */
    public enum Facing {
        SOUTH, WEST, NORTH, EAST;

        public static Facing parse(String text) {
            switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "S":
                case "SOUTH":
                    return SOUTH;
                case "W":
                case "WEST":
                    return WEST;
                case "N":
                case "NORTH":
                    return NORTH;
                case "E":
                case "EAST":
                    return EAST;
                default:
                    throw new IllegalArgumentException("facing is N, E, S or W, not '" + text + "'.");
            }
        }

        public int index() {
            return ordinal();
        }

        public String stateName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One opening to fill. */
    public static final class Opening {
        public final int x;
        public final int y;
        public final int z;
        public final String block;
        public final Facing facing;
        public final int width;
        /** For a pair, the axis it spans: 'x' or 'z'. Default: across the facing. */
        public final char axis;
        public final boolean closer;
        /** For a single door. */
        public final boolean hingeRight;

        public Opening(int x, int y, int z, String block, Facing facing, int width, Character axis, boolean closer,
                       boolean hingeRight) {
            if (width != 1 && width != 2) {
                throw new IllegalArgumentException("width is 1 (a single door) or 2 (a pair).");
            }
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.facing = facing;
            this.width = width;
            this.axis = axis != null ? axis : facing == Facing.SOUTH || facing == Facing.NORTH ? 'x' : 'z';
            if (this.axis != 'x' && this.axis != 'z') {
                throw new IllegalArgumentException("axis is 'x' or 'z'.");
            }
            this.closer = closer;
            this.hingeRight = hingeRight;
        }
    }

    /** One door leaf: its lower cell, and how its halves are set. */
    public static final class Leaf {
        public final int x;
        public final int y;
        public final int z;
        public final String block;
        public final Facing facing;
        public final boolean hingeRight;
        public final boolean closer;

        Leaf(int x, int y, int z, String block, Facing facing, boolean hingeRight, boolean closer) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.facing = facing;
            this.hingeRight = hingeRight;
            this.closer = closer;
        }

        /** The lower half's metadata in the CSM / vanilla-index convention: the facing index. */
        public int lowerMeta() {
            return facing.index();
        }

        /** The upper half's metadata in the CSM convention: 8 + hinge right + 2 x closer. */
        public int upperMeta() {
            return 8 + (hingeRight ? 1 : 0) + (closer ? 2 : 0);
        }
    }

    /** The leaves of one opening, lower coordinate first. */
    public static List<Leaf> leaves(Opening opening) {
        if (opening.width == 1) {
            return Collections.singletonList(new Leaf(opening.x, opening.y, opening.z, opening.block, opening.facing,
                opening.hingeRight, opening.closer));
        }
        boolean lowLeft = opening.facing == Facing.NORTH || opening.facing == Facing.EAST;
        int x2 = opening.axis == 'x' ? opening.x + 1 : opening.x;
        int z2 = opening.axis == 'z' ? opening.z + 1 : opening.z;
        List<Leaf> pair = new ArrayList<>(2);
        pair.add(new Leaf(opening.x, opening.y, opening.z, opening.block, opening.facing, !lowLeft, opening.closer));
        pair.add(new Leaf(x2, opening.y, z2, opening.block, opening.facing, lowLeft, opening.closer));
        return pair;
    }
}
