package com.micatechnologies.minecraft.mcmcp.tools;

import com.micatechnologies.minecraft.mcmcp.regions.QuarterTurns;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.Rotation;

/**
 * A block state turned about the vertical, for a copied structure (#191).
 *
 * <p>The game's own answer is {@code IBlockState.withRotation}, which every vanilla block implements
 * and a mod's block may: it is asked first, and whatever it changes is taken. A mod block that does
 * not implement it gets its state back unchanged, though it has a {@code facing} like any other. So a
 * state the game left alone is then turned by the names of its properties and their values, which is
 * how its blockstate file spells them: see {@link QuarterTurns#value}.
 *
 * <p>What is never turned here, because it is not in the state: a direction kept in a tile entity's
 * NBT, in the block's id, or in a chiselled block's voxels.
 */
final class StateRotation {

    /** What turning one state came to. */
    static final class Turned {

        final IBlockState state;
        /**
         * True when the state has a direction in it that could not be turned: a property whose value
         * names a compass point or a horizontal axis, with no value for where that would now point.
         */
        final boolean stuck;

        Turned(IBlockState state, boolean stuck) {
            this.state = state;
            this.stuck = stuck;
        }
    }

    private static final String[] SIDES = {"north", "east", "south", "west"};

    private StateRotation() {
    }

    static Rotation rotation(int turns) {
        switch (((turns % 4) + 4) % 4) {
            case 1:
                return Rotation.CLOCKWISE_90;
            case 2:
                return Rotation.CLOCKWISE_180;
            case 3:
                return Rotation.COUNTERCLOCKWISE_90;
            default:
                return Rotation.NONE;
        }
    }

    /** {@code state} turned {@code turns} quarter turns clockwise. */
    static Turned turn(IBlockState state, int turns) {
        int quarter = ((turns % 4) + 4) % 4;
        if (quarter == 0) {
            return new Turned(state, false);
        }
        IBlockState byGame;
        try {
            byGame = state.withRotation(rotation(quarter));
        }
        catch (RuntimeException e) {
            byGame = state;
        }
        if (byGame != state) {
            return new Turned(byGame, false);
        }
        return byName(state, quarter);
    }

    /** The fallback: by what the properties and their values are called. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Turned byName(IBlockState state, int quarter) {
        IBlockState turned = state;
        boolean stuck = false;
        Map<String, IProperty> sides = new HashMap<>();
        for (IProperty property : state.getPropertyKeys()) {
            String name = property.getName();
            if (QuarterTurns.compass(name, 0) != null) {
                // north, east, south, west as properties: a fence's arms, a railing's edges. Their
                // values change places; done together below.
                sides.put(name, property);
                continue;
            }
            Comparable value = state.getValue(property);
            if (value instanceof Integer && "rotation".equals(name) && property.getAllowedValues().size() == 16) {
                // A standing sign's sixteen steps: four to a quarter turn.
                Integer next = ((Integer) value + 4 * quarter) % 16;
                if (property.getAllowedValues().contains(next)) {
                    turned = turned.withProperty(property, next);
                }
                continue;
            }
            Collection<Comparable> allowed = property.getAllowedValues();
            List<String> names = new ArrayList<>(allowed.size());
            for (Comparable candidate : allowed) {
                names.add(property.getName(candidate));
            }
            String current = property.getName(value);
            String next = QuarterTurns.value(current, quarter, names);
            if (next == null) {
                // No direction in the name, or nowhere for it to go.
                stuck |= directional(current);
                continue;
            }
            if (!next.equals(current)) {
                for (Comparable candidate : allowed) {
                    if (property.getName(candidate).equals(next)) {
                        turned = turned.withProperty(property, candidate);
                        break;
                    }
                }
            }
        }
        if (sides.size() == 4) {
            // All four, of one kind: the value on each side moves to the side it turns to.
            IBlockState before = turned;
            for (String side : SIDES) {
                IProperty from = sides.get(side);
                IProperty to = sides.get(QuarterTurns.compass(side, quarter));
                Comparable value = before.getValue(from);
                if (to.getAllowedValues().contains(value)) {
                    turned = turned.withProperty(to, value);
                }
                else {
                    stuck = true;
                }
            }
        }
        else if (!sides.isEmpty()) {
            // Some sides but not all four cannot be permuted; a set one is a direction left as it was.
            for (IProperty property : sides.values()) {
                Comparable value = state.getValue(property);
                if (Boolean.TRUE.equals(value)) {
                    stuck = true;
                }
            }
        }
        return new Turned(turned, stuck);
    }

    /** Whether a value's name has a compass word or a horizontal axis in it. */
    private static boolean directional(@Nullable String name) {
        if (name == null) {
            return false;
        }
        for (String part : name.split("_", -1)) {
            if (QuarterTurns.compass(part, 0) != null || "x".equals(part) || "z".equals(part)) {
                return true;
            }
        }
        return false;
    }
}
