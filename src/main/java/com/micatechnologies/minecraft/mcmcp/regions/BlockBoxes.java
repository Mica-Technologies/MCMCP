package com.micatechnologies.minecraft.mcmcp.regions;

import java.util.ArrayList;
import java.util.List;

/**
 * A region's palette indices as cuboids of one block each.
 *
 * <p>A room, a floor or a facade is a few large runs of the same block: a 40 x 10 x 30 shell that is
 * 95% air and three materials is 12,000 index entries in palette form, and a few dozen boxes here.
 * Greedy, not optimal: from the first cell not yet covered (x fastest, then z, then y), a box grows
 * along x while the cells match, then along z while every cell of its row does, then along y while
 * every cell of its rectangle does. That is exact, which is the property that matters: every cell is
 * in exactly one box, or skipped, so painting the boxes over the skipped value rebuilds the region.
 *
 * <p>Pure, and round-trip tested.
 */
public final class BlockBoxes {

    private BlockBoxes() {
    }

    /** The boxes, and whether {@code maxBoxes} stopped the merge before every cell was covered. */
    public static final class Result {

        /** Each box as {@code {index, x0, y0, z0, x1, y1, z1}}, local offsets, both corners inclusive. */
        public final List<int[]> boxes;
        public final boolean truncated;

        Result(List<int[]> boxes, boolean truncated) {
            this.boxes = boxes;
            this.truncated = truncated;
        }
    }

    /**
     * @param indices palette indices, x fastest then z then y
     * @param skip    an index left out of the boxes (air), or -1 to box every cell
     */
    public static Result merge(int[] indices, int sizeX, int sizeY, int sizeZ, int skip, int maxBoxes) {
        boolean[] covered = new boolean[indices.length];
        List<int[]> boxes = new ArrayList<>();
        int layer = sizeX * sizeZ;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int start = x + sizeX * (z + sizeZ * y);
                    int value = indices[start];
                    if (covered[start] || value == skip) {
                        continue;
                    }
                    if (boxes.size() >= maxBoxes) {
                        return new Result(boxes, true);
                    }
                    int x1 = x;
                    while (x1 + 1 < sizeX && matches(indices, covered, start + (x1 + 1 - x), value)) {
                        x1++;
                    }
                    int z1 = z;
                    while (z1 + 1 < sizeZ && rowMatches(indices, covered, start + (z1 + 1 - z) * sizeX, x1 - x, value)) {
                        z1++;
                    }
                    int y1 = y;
                    while (y1 + 1 < sizeY && rectMatches(indices, covered, start + (y1 + 1 - y) * layer,
                        x1 - x, z1 - z, sizeX, value)) {
                        y1++;
                    }
                    for (int by = y; by <= y1; by++) {
                        for (int bz = z; bz <= z1; bz++) {
                            int row = sizeX * (bz + sizeZ * by);
                            for (int bx = x; bx <= x1; bx++) {
                                covered[row + bx] = true;
                            }
                        }
                    }
                    boxes.add(new int[] {value, x, y, z, x1, y1, z1});
                }
            }
        }
        return new Result(boxes, false);
    }

    /** Paints boxes over {@code fill}: the inverse of {@link #merge}. */
    public static int[] paint(List<int[]> boxes, int sizeX, int sizeY, int sizeZ, int fill) {
        int[] cells = new int[sizeX * sizeY * sizeZ];
        java.util.Arrays.fill(cells, fill);
        for (int[] box : boxes) {
            for (int y = box[2]; y <= box[5]; y++) {
                for (int z = box[3]; z <= box[6]; z++) {
                    for (int x = box[1]; x <= box[4]; x++) {
                        cells[x + sizeX * (z + sizeZ * y)] = box[0];
                    }
                }
            }
        }
        return cells;
    }

    private static boolean matches(int[] indices, boolean[] covered, int i, int value) {
        return !covered[i] && indices[i] == value;
    }

    private static boolean rowMatches(int[] indices, boolean[] covered, int rowStart, int width, int value) {
        for (int i = 0; i <= width; i++) {
            if (!matches(indices, covered, rowStart + i, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean rectMatches(int[] indices, boolean[] covered, int rectStart, int width, int depth,
                                       int sizeX, int value) {
        for (int dz = 0; dz <= depth; dz++) {
            if (!rowMatches(indices, covered, rectStart + dz * sizeX, width, value)) {
                return false;
            }
        }
        return true;
    }
}
