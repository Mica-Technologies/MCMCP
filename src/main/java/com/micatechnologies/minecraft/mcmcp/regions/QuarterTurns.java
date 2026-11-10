package com.micatechnologies.minecraft.mcmcp.regions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Turning a structure a quarter turn at a time, clockwise seen from above with north up: where each
 * cell goes, and what a direction's name becomes (#191).
 *
 * <p>A copied building has to face the street it is pasted on, and the tool that did it from outside
 * the game rotated positions, then guessed each block's state from its metadata and rewrote
 * coordinates in tile-entity NBT by regular expression. The positions and the names are the parts
 * that are plain arithmetic, so they are here, with no Minecraft class, and are unit-tested.
 *
 * <p>One quarter turn takes north to east, east to south: a point at (x, z) relative to the pivot
 * goes to (-z, x), which is what {@code Rotation.CLOCKWISE_90} does.
 */
public final class QuarterTurns {

    private static final String[] COMPASS = {"north", "east", "south", "west"};

    private QuarterTurns() {
    }

    /** Degrees (0, 90, 180, 270, or their negatives and multiples) as 0-3 quarter turns clockwise. */
    public static int ofDegrees(int degrees) {
        if (degrees % 90 != 0) {
            throw new IllegalArgumentException("rotate takes 0, 90, 180 or 270 (clockwise seen from above); got "
                + degrees + ".");
        }
        return ((degrees / 90) % 4 + 4) % 4;
    }

    // ------------------------------------------------------------------
    // Positions
    // ------------------------------------------------------------------

    /**
     * A box of cells turned about its own corner and put down somewhere else: maps each source cell to
     * its destination, so that the turned box's minimum corner lands on a given point.
     */
    public static final class Box {

        public final int minX;
        public final int minY;
        public final int minZ;
        public final int maxX;
        public final int maxY;
        public final int maxZ;
        public final int turns;
        private final int toX;
        private final int toY;
        private final int toZ;

        /**
         * @param turns quarter turns clockwise, 0-3
         * @param toX   where the turned box's minimum corner goes
         */
        public Box(int x1, int y1, int z1, int x2, int y2, int z2, int turns, int toX, int toY, int toZ) {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
            this.turns = ((turns % 4) + 4) % 4;
            this.toX = toX;
            this.toY = toY;
            this.toZ = toZ;
        }

        public int sizeX() {
            return maxX - minX + 1;
        }

        public int sizeY() {
            return maxY - minY + 1;
        }

        public int sizeZ() {
            return maxZ - minZ + 1;
        }

        public long cells() {
            return (long) sizeX() * sizeY() * sizeZ();
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** Whether a column is inside the box, whatever its height: for a position kept as x and z only. */
        public boolean containsColumn(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** Where a source cell goes. Works for any point, so a position just outside the box turns with it. */
        public int[] map(int x, int y, int z) {
            int lx = x - minX;
            int lz = z - minZ;
            int outX;
            int outZ;
            switch (turns) {
                case 1:
                    outX = sizeZ() - 1 - lz;
                    outZ = lx;
                    break;
                case 2:
                    outX = sizeX() - 1 - lx;
                    outZ = sizeZ() - 1 - lz;
                    break;
                case 3:
                    outX = lz;
                    outZ = sizeX() - 1 - lx;
                    break;
                default:
                    outX = lx;
                    outZ = lz;
                    break;
            }
            return new int[] {toX + outX, toY + (y - minY), toZ + outZ};
        }

        /** The destination box, {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
        public int[] destination() {
            boolean swapped = turns == 1 || turns == 3;
            int dx = swapped ? sizeZ() : sizeX();
            int dz = swapped ? sizeX() : sizeZ();
            return new int[] {toX, toY, toZ, toX + dx - 1, toY + sizeY() - 1, toZ + dz - 1};
        }
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    /** A compass word turned, or null when {@code word} is not one. */
    @Nullable
    public static String compass(String word, int turns) {
        for (int i = 0; i < COMPASS.length; i++) {
            if (COMPASS[i].equals(word)) {
                return COMPASS[(i + (((turns % 4) + 4) % 4)) % 4];
            }
        }
        return null;
    }

    /**
     * What a block state property's value becomes when the block is turned, going by its name alone:
     * for a mod's block that does not say how it rotates. A name is taken apart at its underscores;
     * each compass word turns, and {@code x} and {@code z} change places on an odd number of quarter
     * turns. So {@code east} becomes {@code south}, {@code north_south} becomes {@code east_west},
     * {@code ascending_north} becomes {@code ascending_east}, {@code up_x} becomes {@code up_z}.
     *
     * @param allowed the property's values; the answer is always one of them
     * @return the new value's name; {@code name} itself when turning does not change it; or null when
     *         the name says nothing about direction, or what it turns into is not a value the property
     *         has
     */
    @Nullable
    public static String value(String name, int turns, Collection<String> allowed) {
        int quarter = ((turns % 4) + 4) % 4;
        String[] parts = name.split("_", -1);
        List<Integer> compassAt = new ArrayList<>();
        boolean directional = false;
        for (int i = 0; i < parts.length; i++) {
            String turned = compass(parts[i], quarter);
            if (turned != null) {
                parts[i] = turned;
                compassAt.add(i);
                directional = true;
            }
            else if ("x".equals(parts[i]) || "z".equals(parts[i])) {
                directional = true;
                if (quarter % 2 == 1) {
                    parts[i] = "x".equals(parts[i]) ? "z" : "x";
                }
            }
        }
        if (!directional) {
            return null;
        }
        String turned = String.join("_", parts);
        if (allowed.contains(turned)) {
            return turned;
        }
        // north_south turns into east_west, but east_west into south_north: the same pair, named the
        // other way round.
        if (compassAt.size() == 2) {
            String first = parts[compassAt.get(0)];
            parts[compassAt.get(0)] = parts[compassAt.get(1)];
            parts[compassAt.get(1)] = first;
            String swapped = String.join("_", parts);
            if (allowed.contains(swapped)) {
                return swapped;
            }
        }
        return null;
    }
}
