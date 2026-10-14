package com.micatechnologies.minecraft.mcmcp.tools;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Writes blocks that are two cells — doors, tall plants, beds — as one placement (#71).
 *
 * <p>Each half checks for the other when a neighbour changes, and breaks itself if it is missing. A
 * bulk write sets one cell at a time, so a door's lower half written with neighbour updates sees no
 * upper half yet and drops as an item; an upper half written first is removed as soon as anything
 * next to it changes. So both halves are set with no neighbour updates, and neighbours are told only
 * once both exist.
 *
 * <p>Two-part means either a block state with a {@code half} property whose values include
 * {@code lower} and {@code upper} (every {@code BlockDoor} and {@code BlockDoublePlant}, and mods'
 * blocks built on them), or a bed. The first half is the lower one, or a bed's foot.
 *
 * <p>Game thread only.
 */
final class TwoPartBlocks {

    /** No neighbour updates, no observer updates, but sent to clients. */
    private static final int QUIET = 2 | 16;

    private TwoPartBlocks() {
    }

    /** What happened to one cell handed to {@link Writer#write}. */
    enum Outcome {
        /** Not a two-part block; the caller writes it as usual. */
        NOT_TWO_PART,
        /** Written, with its partner (counted in {@link Writer#partnersWritten} if not in the write). */
        WRITTEN,
        /** Already as asked, partner included. */
        UNCHANGED,
        /** A second half whose first half is in the same write and wrote it, or will. */
        DONE_BY_PARTNER,
        /** The world refused it. */
        FAILED
    }

    /** Receives each cell's before and after, for undo points. */
    interface Recorder {
        void beforeChange(BlockPos pos);

        void afterChange(BlockPos pos);
    }

    /** Whether a state is one half of a two-part block. */
    static boolean isTwoPart(IBlockState state) {
        return halfProperty(state) != null || state.getBlock() instanceof BlockBed;
    }

    /** Whether a state is the second half: a door's or plant's upper half, or a bed's head. */
    static boolean isSecondHalf(IBlockState state) {
        IProperty<?> half = halfProperty(state);
        if (half != null) {
            return "upper".equals(valueName(state, half));
        }
        return state.getBlock() instanceof BlockBed && state.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD;
    }

    /** Where this half's partner is. */
    static BlockPos partnerPos(IBlockState state, BlockPos pos) {
        if (state.getBlock() instanceof BlockBed) {
            EnumFacing facing = state.getValue(BlockBed.FACING);
            return isSecondHalf(state) ? pos.offset(facing.getOpposite()) : pos.offset(facing);
        }
        return isSecondHalf(state) ? pos.down() : pos.up();
    }

    /** The second half that goes with a first half, when none is given. */
    static IBlockState derivedSecondHalf(IBlockState first) {
        if (first.getBlock() instanceof BlockBed) {
            return first.withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD);
        }
        return withValue(first, halfProperty(first), "upper");
    }

    /**
     * One bulk write's two-part cells. Built from every two-part cell in the write before any is
     * written, so a first half can take the second half the caller gave for its partner cell — from
     * this batch or a later one — rather than deriving one.
     */
    static final class Writer {

        private final Map<Long, IBlockState> given;
        private final int flags;
        int partnersWritten;
        int oldPartnersCleared;

        /**
         * @param given the two-part cells in this write, by {@link BlockPos#toLong()}
         * @param flags the write's flags: 3 notifies neighbours once both halves are in place
         */
        Writer(Map<Long, IBlockState> given, int flags) {
            this.given = given == null ? Collections.<Long, IBlockState>emptyMap() : given;
            this.flags = flags;
        }

        /**
         * Writes one cell if it is two-part.
         *
         * @param partner the second half to use, overriding what the write gives or what would be
         *                derived; null for the usual choice
         */
        Outcome write(World world, BlockPos pos, IBlockState state, @Nullable IBlockState partner,
                      @Nullable Recorder recorder) {
            if (!isTwoPart(state)) {
                return Outcome.NOT_TWO_PART;
            }
            BlockPos otherPos = partnerPos(state, pos);
            if (isSecondHalf(state)) {
                IBlockState first = given.get(otherPos.toLong());
                if (first != null && !isSecondHalf(first) && first.getBlock() == state.getBlock()) {
                    return Outcome.DONE_BY_PARTNER;
                }
                // A second half on its own: written quietly, and left to whoever places its first half.
                return set(world, pos, state, recorder) ? Outcome.WRITTEN : unchangedOrFailed(world, pos, state);
            }
            IBlockState other = partner;
            if (other == null) {
                IBlockState fromWrite = given.get(otherPos.toLong());
                other = fromWrite != null && isSecondHalf(fromWrite) && fromWrite.getBlock() == state.getBlock()
                    ? fromWrite : derivedSecondHalf(state);
            }
            if (!world.isBlockLoaded(otherPos) || otherPos.getY() < 0 || otherPos.getY() > 255) {
                return Outcome.FAILED;
            }
            if (world.getBlockState(pos) == state && world.getBlockState(otherPos) == other) {
                return Outcome.UNCHANGED;
            }
            clearOldPartner(world, pos, otherPos, recorder);
            clearOldPartner(world, otherPos, pos, recorder);
            boolean firstChanged = set(world, pos, state, recorder);
            boolean secondChanged = set(world, otherPos, other, recorder);
            if (world.getBlockState(pos) != state) {
                return Outcome.FAILED;
            }
            if (secondChanged && !given.containsKey(otherPos.toLong())) {
                partnersWritten++;
            }
            if ((flags & 1) != 0) {
                world.notifyNeighborsOfStateChange(pos, state.getBlock(), true);
                world.notifyNeighborsOfStateChange(otherPos, other.getBlock(), true);
            }
            return firstChanged || secondChanged ? Outcome.WRITTEN : Outcome.UNCHANGED;
        }

        /**
         * A different door already in one of the two cells keeps its other half somewhere this write
         * does not touch; left there it is an orphan half, and the new door's half may fail to place
         * against it. Removed quietly, before the new pair goes in.
         */
        private void clearOldPartner(World world, BlockPos cell, BlockPos newPartner, @Nullable Recorder recorder) {
            IBlockState old = world.getBlockState(cell);
            if (!isTwoPart(old)) {
                return;
            }
            BlockPos oldPartner = partnerPos(old, cell);
            if (oldPartner.equals(newPartner) || !world.isBlockLoaded(oldPartner)
                || world.getBlockState(oldPartner).getBlock() != old.getBlock()) {
                return;
            }
            if (set(world, oldPartner, Blocks.AIR.getDefaultState(), recorder)) {
                oldPartnersCleared++;
            }
        }

        private boolean set(World world, BlockPos pos, IBlockState state, @Nullable Recorder recorder) {
            if (world.getBlockState(pos) == state) {
                return false;
            }
            if (recorder != null) {
                recorder.beforeChange(pos);
            }
            boolean changed = world.setBlockState(pos, state, QUIET);
            if (changed) {
                if (recorder != null) {
                    recorder.afterChange(pos);
                }
            }
            return changed;
        }

        private static Outcome unchangedOrFailed(World world, BlockPos pos, IBlockState state) {
            return world.getBlockState(pos) == state ? Outcome.UNCHANGED : Outcome.FAILED;
        }
    }

    @Nullable
    private static IProperty<?> halfProperty(IBlockState state) {
        for (IProperty<?> property : state.getPropertyKeys()) {
            if ("half".equals(property.getName())) {
                boolean lower = false;
                boolean upper = false;
                for (Object value : property.getAllowedValues()) {
                    String name = nameOf(property, value);
                    lower |= "lower".equals(name);
                    upper |= "upper".equals(name);
                }
                if (lower && upper) {
                    return property;
                }
            }
        }
        return null;
    }

    private static String valueName(IBlockState state, IProperty<?> property) {
        return nameOf(property, state.getValue(property));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String nameOf(IProperty property, Object value) {
        return property.getName((Comparable) value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IBlockState withValue(IBlockState state, IProperty property, String name) {
        for (Object value : property.getAllowedValues()) {
            if (name.equals(property.getName((Comparable) value))) {
                return state.withProperty(property, (Comparable) value);
            }
        }
        return state;
    }

    /** Whether {@code block} can be written as a pair at all, for a refusal that names it. */
    static boolean canPair(Block block) {
        return isTwoPart(block.getDefaultState());
    }

    /** A {@link Recorder} feeding an undo point, or null when there is none. */
    @Nullable
    static Recorder undo(final World world, @Nullable final UndoPoints.Recorder undo) {
        if (undo == null) {
            return null;
        }
        final Map<Long, UndoPoints.Before> pending = new HashMap<>();
        return new Recorder() {
            @Override
            public void beforeChange(BlockPos pos) {
                pending.put(pos.toLong(), undo.capture(world, pos));
            }

            @Override
            public void afterChange(BlockPos pos) {
                UndoPoints.Before was = pending.remove(pos.toLong());
                if (was != null) {
                    undo.record(pos, was, world.getBlockState(pos));
                }
            }
        };
    }
}
