package com.micatechnologies.minecraft.mcmcp.regions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A region as text: one character per block, one row per line, one slice per layer.
 *
 * <p>The cheapest way for a model to see a layout. A 60 x 40 floor is about 2,500 characters here,
 * against some 1,500 tokens for a screenshot that shows less, or tens of kilobytes of index JSON
 * that a model has to decode in its head. Each block gets one character, its name's first letter
 * where that is free ({@code s} for stone, {@code G} for a second block starting with g), so the
 * legend is mostly guessable; air is {@code .} and an unloaded cell {@code ?}.
 *
 * <p>Orientation is fixed, so two reads compare by eye. A y slice is a map with north (-z) at the
 * top and west (-x) on the left. An x or z slice is an elevation with the top layer first. Every row
 * starts with its coordinate.
 *
 * <p>Pure.
 */
public final class BlockSlices {

    public static final char AIR = '.';
    public static final char UNLOADED = '?';
    /** What every block past the last free character draws as. */
    public static final char OTHER = '~';

    private static final String POOL =
        "#@%&*+=$ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private BlockSlices() {
    }

    /** One slice: the coordinate it is at on the slice axis, and its rows, labelled. */
    public static final class Slice {

        public final int at;
        public final List<String> rows;

        Slice(int at, List<String> rows) {
            this.at = at;
            this.rows = rows;
        }
    }

    /**
     * A character for each palette entry, most common first so the rarest are the ones that run out.
     *
     * @return the character per palette index; the legend is this read back with the ids
     */
    public static char[] assign(String[] ids, int[] counts) {
        char[] chars = new char[ids.length];
        Integer[] order = new Integer[ids.length];
        for (int i = 0; i < ids.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(counts[b], counts[a]));
        StringBuilder used = new StringBuilder();
        for (int i : order) {
            char c;
            if ("minecraft:air".equals(ids[i])) {
                c = AIR;
            }
            else if ("mcmcp:unloaded".equals(ids[i])) {
                c = UNLOADED;
            }
            else {
                c = pick(ids[i], used);
            }
            chars[i] = c;
            if (c != OTHER) {
                used.append(c);
            }
        }
        return chars;
    }

    /** The legend, character to block, in the order the characters were handed out. */
    public static Map<String, String> legend(String[] ids, char[] chars) {
        Map<String, String> legend = new LinkedHashMap<>();
        boolean other = false;
        for (int i = 0; i < ids.length; i++) {
            if (chars[i] == OTHER) {
                other = true;
            }
            else {
                legend.put(String.valueOf(chars[i]), ids[i]);
            }
        }
        if (other) {
            legend.put(String.valueOf(OTHER), "any other block (more distinct blocks than characters)");
        }
        return legend;
    }

    /**
     * Every layer of the region along {@code axis}, in order of increasing coordinate.
     *
     * @param indices palette indices, x fastest then z then y
     */
    public static List<Slice> render(int[] indices, char[] chars, int originX, int originY, int originZ,
                                     int sizeX, int sizeY, int sizeZ, char axis) {
        List<Slice> slices = new ArrayList<>();
        if (axis == 'y') {
            int label = labelWidth(originZ, originZ + sizeZ - 1);
            for (int y = 0; y < sizeY; y++) {
                List<String> rows = new ArrayList<>(sizeZ);
                for (int z = 0; z < sizeZ; z++) {
                    StringBuilder row = label(originZ + z, label, sizeX);
                    for (int x = 0; x < sizeX; x++) {
                        row.append(chars[indices[x + sizeX * (z + sizeZ * y)]]);
                    }
                    rows.add(row.toString());
                }
                slices.add(new Slice(originY + y, rows));
            }
            return slices;
        }
        int label = labelWidth(originY, originY + sizeY - 1);
        if (axis == 'x') {
            for (int x = 0; x < sizeX; x++) {
                List<String> rows = new ArrayList<>(sizeY);
                for (int y = sizeY - 1; y >= 0; y--) {
                    StringBuilder row = label(originY + y, label, sizeZ);
                    for (int z = 0; z < sizeZ; z++) {
                        row.append(chars[indices[x + sizeX * (z + sizeZ * y)]]);
                    }
                    rows.add(row.toString());
                }
                slices.add(new Slice(originX + x, rows));
            }
            return slices;
        }
        for (int z = 0; z < sizeZ; z++) {
            List<String> rows = new ArrayList<>(sizeY);
            for (int y = sizeY - 1; y >= 0; y--) {
                StringBuilder row = label(originY + y, label, sizeX);
                for (int x = 0; x < sizeX; x++) {
                    row.append(chars[indices[x + sizeX * (z + sizeZ * y)]]);
                }
                rows.add(row.toString());
            }
            slices.add(new Slice(originZ + z, rows));
        }
        return slices;
    }

    private static char pick(String id, StringBuilder used) {
        String path = id.substring(id.indexOf(':') + 1);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isLetter(c) && c < 128) {
                char lower = Character.toLowerCase(c);
                if (used.indexOf(String.valueOf(lower)) < 0) {
                    return lower;
                }
                char upper = Character.toUpperCase(c);
                if (used.indexOf(String.valueOf(upper)) < 0) {
                    return upper;
                }
                break;
            }
        }
        for (int i = 0; i < POOL.length(); i++) {
            char c = POOL.charAt(i);
            if (used.indexOf(String.valueOf(c)) < 0) {
                return c;
            }
        }
        return OTHER;
    }

    private static int labelWidth(int from, int to) {
        return Math.max(String.valueOf(from).length(), String.valueOf(to).length());
    }

    private static StringBuilder label(int coordinate, int width, int cells) {
        StringBuilder row = new StringBuilder(width + 1 + cells);
        String text = String.valueOf(coordinate);
        for (int i = text.length(); i < width; i++) {
            row.append(' ');
        }
        return row.append(text).append('|');
    }
}
