package com.micatechnologies.minecraft.mcmcp.building;

/**
 * A block position as a value: the key of a planned cell map. Ordered x, then y, then z, the order the
 * planner's reference fixtures list their cells in, so a sorted map of these reads like the fixture.
 *
 * <p>Not {@code BlockPos}: this package imports no Minecraft class, so the planners in it are tested as
 * plain Java.
 */
public final class Pos implements Comparable<Pos> {

    public final int x;
    public final int y;
    public final int z;

    public Pos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public int compareTo(Pos o) {
        if (x != o.x) {
            return Integer.compare(x, o.x);
        }
        if (y != o.y) {
            return Integer.compare(y, o.y);
        }
        return Integer.compare(z, o.z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pos)) {
            return false;
        }
        Pos p = (Pos) o;
        return x == p.x && y == p.y && z == p.z;
    }

    @Override
    public int hashCode() {
        return (x * 31 + y) * 31 + z;
    }

    /** As Python prints a tuple, "(x, y, z)": the audit's failure texts quote positions this way. */
    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
