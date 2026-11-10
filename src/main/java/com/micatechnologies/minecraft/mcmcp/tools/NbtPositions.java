package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.regions.QuarterTurns;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.util.math.BlockPos;

/**
 * Moves the block positions a tile entity's NBT holds, when the structure it is part of is copied
 * somewhere else and turned (#191).
 *
 * <p>A thermostat names the rooftop unit it runs, a button names its door, a controller lists its
 * signals: each as a position, each under a key of the mod's own choosing. Copied as they are, the
 * copy's thermostat runs the original's unit. Rewritten by regular expression over the SNBT text,
 * which is how it was done from outside the game, a mod that keeps its position under another key is
 * missed.
 *
 * <h2>What counts as a position</h2>
 *
 * Shape, not name, and only inside the box being copied:
 *
 * <ul>
 *   <li>three whole-number tags in one compound whose names differ only in a final {@code x}, {@code y}
 *       and {@code z} ({@code x/y/z}, {@code linkX/linkY/linkZ}, {@code tx/ty/tz}), or the {@code x} and
 *       {@code z} of such a set with no {@code y};</li>
 *   <li>an int array of three;</li>
 *   <li>a long that unpacks, as {@code BlockPos.toLong} packs it, to a cell.</li>
 * </ul>
 *
 * A candidate inside the source box is something that was copied, so it moves with the copy. One
 * outside it points at something that was not, and is left: the copy still refers to the original's,
 * and the report says so, since that is as likely a mistake as an intent. A long or an int array
 * outside the box is not counted at all: most longs are not positions.
 *
 * <p>The tile entity's own {@code x}, {@code y} and {@code z} at the root are not touched; whoever
 * places it sets them.
 */
final class NbtPositions {

    /** What a rewrite did, by key: for the caller's reply. */
    static final class Report {

        /** Positions inside the box that were moved, by key shape ({@code linkX/Y/Z}, {@code lU[3]}). */
        final Map<String, Integer> moved = new TreeMap<>();
        /** Positions found by name that lie outside the box and were left pointing where they did. */
        final Map<String, Integer> outside = new TreeMap<>();
        /** Root keys that look like a direction kept in NBT, which nothing here can turn. */
        final Map<String, Integer> directionKeys = new TreeMap<>();

        private static void count(Map<String, Integer> map, String key) {
            map.merge(key, 1, Integer::sum);
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            add(json, "moved", moved);
            add(json, "outside", outside);
            add(json, "directionKeys", directionKeys);
            return json;
        }

        private static void add(JsonObject json, String name, Map<String, Integer> map) {
            if (map.isEmpty()) {
                return;
            }
            JsonObject counts = new JsonObject();
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                counts.addProperty(entry.getKey(), entry.getValue());
            }
            json.add(name, counts);
        }
    }

    private NbtPositions() {
    }

    /**
     * Rewrites every position in {@code tag} that lies in {@code box} to where the box's turn puts it,
     * in place.
     */
    static void move(NBTTagCompound tag, QuarterTurns.Box box, Report report) {
        if (box.turns != 0) {
            for (String key : tag.getKeySet()) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (tag.getTag(key) instanceof NBTPrimitive || tag.hasKey(key, 8)) {
                    if (lower.equals("facing") || lower.equals("rotation") || lower.equals("rot") || lower.equals("dir")
                        || lower.equals("direction") || lower.equals("orientation") || lower.equals("side")) {
                        Report.count(report.directionKeys, key);
                    }
                }
            }
        }
        compound(tag, box, report, true);
    }

    private static boolean wholeNumber(NBTBase tag) {
        // Byte, short, int, long: ids 1 to 4. A float or double is a position within a cell, or not one.
        return tag != null && tag.getId() >= 1 && tag.getId() <= 4;
    }

    private static void compound(NBTTagCompound tag, QuarterTurns.Box box, Report report, boolean root) {
        List<String> keys = new ArrayList<>(tag.getKeySet());
        // The parts of a named position, so a long among them is not then read as a packed one.
        java.util.Set<String> named = new java.util.HashSet<>();
        for (String key : keys) {
            if (key.isEmpty()) {
                continue;
            }
            char last = key.charAt(key.length() - 1);
            if (last != 'x' && last != 'X') {
                continue;
            }
            String stem = key.substring(0, key.length() - 1);
            String keyY = stem + (last == 'x' ? 'y' : 'Y');
            String keyZ = stem + (last == 'x' ? 'z' : 'Z');
            if (root && stem.isEmpty()) {
                // The tile entity's own position.
                continue;
            }
            if (!wholeNumber(tag.getTag(key)) || !wholeNumber(tag.getTag(keyZ))) {
                continue;
            }
            boolean hasY = wholeNumber(tag.getTag(keyY));
            named.add(key);
            named.add(keyZ);
            if (hasY) {
                named.add(keyY);
            }
            String label = stem + (last == 'x' ? (hasY ? "x/y/z" : "x/z") : (hasY ? "X/Y/Z" : "X/Z"));
            long x = ((NBTPrimitive) tag.getTag(key)).getLong();
            long z = ((NBTPrimitive) tag.getTag(keyZ)).getLong();
            long y = hasY ? ((NBTPrimitive) tag.getTag(keyY)).getLong() : box.minY;
            if (!inInts(x, y, z) || !(hasY ? box.contains((int) x, (int) y, (int) z) : box.containsColumn((int) x, (int) z))) {
                Report.count(report.outside, label);
                continue;
            }
            int[] to = box.map((int) x, (int) y, (int) z);
            put(tag, key, to[0]);
            put(tag, keyZ, to[2]);
            if (hasY) {
                put(tag, keyY, to[1]);
            }
            Report.count(report.moved, label);
        }
        for (String key : keys) {
            NBTBase value = tag.getTag(key);
            if (value instanceof NBTTagCompound) {
                compound((NBTTagCompound) value, box, report, false);
            }
            else if (value instanceof NBTTagList) {
                list(key, (NBTTagList) value, box, report);
            }
            else if (value instanceof NBTTagIntArray) {
                if (triple(((NBTTagIntArray) value).getIntArray(), box)) {
                    Report.count(report.moved, key + "[3]");
                }
            }
            else if (value instanceof NBTTagLong && !named.contains(key)) {
                Long moved = packed(((NBTTagLong) value).getLong(), box);
                if (moved != null) {
                    tag.setLong(key, moved);
                    Report.count(report.moved, key + "(long)");
                }
            }
        }
    }

    private static void list(String key, NBTTagList list, QuarterTurns.Box box, Report report) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTBase value = list.get(i);
            if (value instanceof NBTTagCompound) {
                compound((NBTTagCompound) value, box, report, false);
            }
            else if (value instanceof NBTTagList) {
                list(key, (NBTTagList) value, box, report);
            }
            else if (value instanceof NBTTagIntArray) {
                if (triple(((NBTTagIntArray) value).getIntArray(), box)) {
                    Report.count(report.moved, key + "[][3]");
                }
            }
            else if (value instanceof NBTTagLong) {
                Long moved = packed(((NBTTagLong) value).getLong(), box);
                if (moved != null) {
                    list.set(i, new NBTTagLong(moved));
                    Report.count(report.moved, key + "[](long)");
                }
            }
        }
    }

    /** Moves an {@code [x, y, z]} array in place when it is a cell of the box. */
    private static boolean triple(int[] values, QuarterTurns.Box box) {
        if (values.length != 3 || !box.contains(values[0], values[1], values[2])) {
            return false;
        }
        int[] to = box.map(values[0], values[1], values[2]);
        System.arraycopy(to, 0, values, 0, 3);
        return true;
    }

    /** The packed position moved, or null when {@code value} does not unpack to a cell of the box. */
    private static Long packed(long value, QuarterTurns.Box box) {
        BlockPos pos = BlockPos.fromLong(value);
        if (pos.toLong() != value || !box.contains(pos.getX(), pos.getY(), pos.getZ())) {
            return null;
        }
        int[] to = box.map(pos.getX(), pos.getY(), pos.getZ());
        return new BlockPos(to[0], to[1], to[2]).toLong();
    }

    private static boolean inInts(long x, long y, long z) {
        return x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE && y >= Integer.MIN_VALUE && y <= Integer.MAX_VALUE
            && z >= Integer.MIN_VALUE && z <= Integer.MAX_VALUE;
    }

    /** Writes a number back with the tag type it had. */
    private static void put(NBTTagCompound tag, String key, int value) {
        switch (tag.getTagId(key)) {
            case 1:
                tag.setByte(key, (byte) value);
                break;
            case 2:
                tag.setShort(key, (short) value);
                break;
            case 4:
                tag.setLong(key, value);
                break;
            default:
                tag.setInteger(key, value);
                break;
        }
    }
}
