package com.micatechnologies.minecraft.mcmcp.regions;

import javax.annotation.Nullable;

/**
 * Where a block coordinate from a caller may be, checked before anything is derived from it (#219).
 *
 * <p>A coordinate a tool is given is a number a model or a script made up. One that is far outside
 * the world costs nothing by itself, but the things computed from it do: a box drawn round every cell
 * of a call is as large as its farthest cell, and a scan of that box on the game thread is paid for by
 * its area, not by the cells in it. A box whose corners were never set at all (a call with no cells)
 * is the whole integer range, and one vanilla entity lookup over it hung a live server until it was
 * killed. So every tool that takes positions checks them here first, in {@code long} so that an
 * origin plus an offset cannot wrap round into a valid-looking position, and names the one that is
 * out of range.
 *
 * <p>Pure: no Minecraft class.
 */
public final class WorldBounds {

    /** The farthest x or z a block can have: vanilla's own limit on a world's size. */
    public static final int MAX_HORIZONTAL = 30_000_000;

    public static final int MIN_Y = 0;

    public static final int MAX_Y = 255;

    /**
     * The widest a box that is scanned as one piece may be on x or z, in blocks: 4,096 is 256 chunks
     * a side. Not a limit on how far apart the cells of a write may be, which are handled chunk by
     * chunk; a limit on the boxes that are walked whole.
     */
    public static final int MAX_BOX_SPAN = 4_096;

    private WorldBounds() {
    }

    /** Whether {@code value} can be the x or z of a block. */
    public static boolean isHorizontal(long value) {
        return value >= -MAX_HORIZONTAL && value <= MAX_HORIZONTAL;
    }

    /** Whether {@code value} can be the y of a block. */
    public static boolean isY(long value) {
        return value >= MIN_Y && value <= MAX_Y;
    }

    /**
     * Why a block position is not one a tool may act on, or null when it is.
     *
     * @param what how the caller knows the position, to start the sentence: "Cell 12", "blocks[3]"
     */
    @Nullable
    public static String refusal(String what, long x, long y, long z) {
        if (!isY(y)) {
            return what + " is at y=" + y + "; y must be " + MIN_Y + "-" + MAX_Y + ".";
        }
        return horizontalRefusal(what, x, z);
    }

    /** As {@link #refusal}, for a position whose y is checked elsewhere or is not a block's. */
    @Nullable
    public static String horizontalRefusal(String what, long x, long z) {
        if (!isHorizontal(x) || !isHorizontal(z)) {
            return what + " is at x=" + x + ", z=" + z + ", which is outside the world: x and z must be within "
                + MAX_HORIZONTAL + " of 0. Nothing was done.";
        }
        return null;
    }

    /**
     * Why a box given by two corners is not one a tool may walk whole, or null when it is: a corner
     * outside the world, or a side longer than {@code maxSpan} blocks.
     *
     * @param what how the caller knows the box, to start the sentence: "The box", "The region"
     */
    @Nullable
    public static String boxRefusal(String what, long x1, long z1, long x2, long z2, int maxSpan) {
        String outside = horizontalRefusal(what + "'s corner", x1, z1);
        if (outside == null) {
            outside = horizontalRefusal(what + "'s corner", x2, z2);
        }
        if (outside != null) {
            return outside;
        }
        long spanX = Math.abs(x2 - x1) + 1L;
        long spanZ = Math.abs(z2 - z1) + 1L;
        if (spanX > maxSpan || spanZ > maxSpan) {
            return what + " is " + spanX + " x " + spanZ + " blocks on x and z; at most " + maxSpan
                + " a side is handled in one call. Split it.";
        }
        return null;
    }

    /** As {@link #boxRefusal(String, long, long, long, long, int)} with {@link #MAX_BOX_SPAN}. */
    @Nullable
    public static String boxRefusal(String what, long x1, long z1, long x2, long z2) {
        return boxRefusal(what, x1, z1, x2, z2, MAX_BOX_SPAN);
    }
}
