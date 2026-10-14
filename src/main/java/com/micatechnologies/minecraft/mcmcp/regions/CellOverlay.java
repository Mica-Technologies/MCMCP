package com.micatechnologies.minecraft.mcmcp.regions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a replay of fill and setblock ops leaves in each cell it touched: a value per cell, kept in
 * 16 x 16 x 16 sections allocated only where an op reaches.
 *
 * <p>A district's op list touches a million cells spread over a square kilometre. A map from position
 * to value costs tens of bytes a cell, and one array over the bounding box would be mostly empty. A
 * section is 16 KB and is allocated the first time any op writes into it.
 *
 * <p>Values are what the caller stores, 0 meaning untouched, so a caller storing a block state id
 * stores it plus one. Pure, and not thread-safe.
 */
public final class CellOverlay {

    private final Map<Long, int[]> sections = new HashMap<>();
    private long cells;

    /** A section's key: section x, y and z packed like a position. */
    static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFFF) << 38) | ((long) (sy & 0xFFF) << 26) | (sz & 0x3FFFFFF);
    }

    static int sectionX(long key) {
        return (int) (key >> 38);
    }

    static int sectionY(long key) {
        return (int) (key << 26 >> 52);
    }

    static int sectionZ(long key) {
        return (int) (key << 38 >> 38);
    }

    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /** The value at a cell, or 0 if no op touched it. */
    public int get(int x, int y, int z) {
        int[] section = sections.get(sectionKey(x >> 4, y >> 4, z >> 4));
        return section == null ? 0 : section[index(x, y, z)];
    }

    /** Sets a cell's value; {@code value} must not be 0. */
    public void set(int x, int y, int z, int value) {
        long key = sectionKey(x >> 4, y >> 4, z >> 4);
        int[] section = sections.get(key);
        if (section == null) {
            section = new int[4096];
            sections.put(key, section);
        }
        int at = index(x, y, z);
        if (section[at] == 0) {
            cells++;
        }
        section[at] = value;
    }

    /** Cells holding a value. */
    public long cells() {
        return cells;
    }

    public int sectionCount() {
        return sections.size();
    }

    /** Receives each touched cell. */
    public interface Visitor {
        void visit(int x, int y, int z, int value);
    }

    /**
     * The sections in the order to write them: bottom up, so a torch or a door is written after the
     * floor it stands on, then by x and z for a fixed order.
     */
    public List<Long> sectionsBottomUp() {
        List<Long> keys = new ArrayList<>(sections.keySet());
        Collections.sort(keys, (a, b) -> {
            int c = Integer.compare(sectionY(a), sectionY(b));
            if (c != 0) {
                return c;
            }
            c = Integer.compare(sectionX(a), sectionX(b));
            return c != 0 ? c : Integer.compare(sectionZ(a), sectionZ(b));
        });
        return keys;
    }

    /** Visits one section's touched cells, bottom layer first. */
    public void visit(long sectionKey, Visitor visitor) {
        int[] section = sections.get(sectionKey);
        if (section == null) {
            return;
        }
        int baseX = sectionX(sectionKey) << 4;
        int baseY = sectionY(sectionKey) << 4;
        int baseZ = sectionZ(sectionKey) << 4;
        for (int i = 0; i < 4096; i++) {
            if (section[i] != 0) {
                visitor.visit(baseX + (i & 15), baseY + (i >> 8), baseZ + ((i >> 4) & 15), section[i]);
            }
        }
    }
}
