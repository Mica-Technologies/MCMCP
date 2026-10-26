package com.micatechnologies.minecraft.mcmcp.regions;

/**
 * A box of cells, one byte each, classified for the space checks: what a flood may pass through, what
 * a person may stand on, and doors, which are either.
 *
 * <p>Read from the world once, on the game thread, and then checked off it: the floods below touch
 * every cell several times, and a box of a cathedral is a million and a half of them.
 *
 * <p>Pure, and not thread-safe.
 */
public final class VoxelGrid {

    /** Blocks movement and light: walls, floors, glass, everything that is not one of the others. */
    public static final byte SOLID = 0;
    /** Air, and blocks that are air for these purposes (lighting helpers). */
    public static final byte OPEN = 1;
    /** A door, trapdoor or gate: open or closed as the check says. */
    public static final byte DOOR = 2;
    /** Not loaded: treated as solid, and counted. */
    public static final byte UNKNOWN = 3;

    public final int minX;
    public final int minY;
    public final int minZ;
    public final int maxX;
    public final int maxY;
    public final int maxZ;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] cells;

    public VoxelGrid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
        this.sizeY = this.maxY - this.minY + 1;
        this.sizeZ = this.maxZ - this.minZ + 1;
        long volume = volume(minX, minY, minZ, maxX, maxY, maxZ);
        if (volume > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("box too large");
        }
        this.cells = new byte[(int) volume];
    }

    public static long volume(int x1, int y1, int z1, int x2, int y2, int z2) {
        return (Math.abs((long) x2 - x1) + 1L) * (Math.abs((long) y2 - y1) + 1L) * (Math.abs((long) z2 - z1) + 1L);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    int index(int x, int y, int z) {
        return ((x - minX) * sizeY + (y - minY)) * sizeZ + (z - minZ);
    }

    public void set(int x, int y, int z, byte kind) {
        cells[index(x, y, z)] = kind;
    }

    /** The cell's kind; outside the box, {@link #SOLID}. */
    public byte get(int x, int y, int z) {
        return contains(x, y, z) ? cells[index(x, y, z)] : SOLID;
    }

    public int volume() {
        return cells.length;
    }

    public long count(byte kind) {
        long n = 0;
        for (byte cell : cells) {
            if (cell == kind) {
                n++;
            }
        }
        return n;
    }
}
