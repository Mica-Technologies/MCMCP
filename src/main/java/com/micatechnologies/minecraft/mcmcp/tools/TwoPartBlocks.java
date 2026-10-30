package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
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
 * <p>Two-part means a block state with a {@code half} property whose values include {@code lower}
 * and {@code upper} (every {@code BlockDoor} and {@code BlockDoublePlant}, and mods' blocks built on
 * them); a bed; or a stored boolean {@code upper} or {@code top} with evidence that it really is a
 * half (CSM's tall furniture, LBE's casino cabinets, #85). {@link TwoPartRule} says what counts as
 * evidence: the flag alone does not, since CSM's cable trays use {@code top} for a one-cell tray
 * (#130). The first half is the lower one, or a bed's foot.
 *
 * <p>The boolean counts only when it is stored in the metadata. A pipe's "connected on top" is a
 * boolean called {@code top} too, but it is worked out in {@code getActualState} and never stored, so
 * it is not mistaken for a half and nothing is written above it.
 *
 * <p>Game thread only.
 */
final class TwoPartBlocks {

    /** No neighbour updates, no observer updates, but sent to clients. */
    private static final int QUIET = 2 | 16;
    /** How many derived second halves a result names by position. */
    private static final int LISTED = 32;
    /** How many suspected blocks a result names. */
    private static final int SUSPECTED_LISTED = 16;

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
        return verdict(state) == TwoPartRule.Verdict.PAIR;
    }

    /** How the bulk writes treat a state: as a pair, as one cell, or as one cell worth reporting. */
    static TwoPartRule.Verdict verdict(IBlockState state) {
        Block block = state.getBlock();
        boolean half = halfProperty(state) != null;
        boolean bed = block instanceof BlockBed;
        PropertyBool flag = half || bed ? null : upperFlag(state);
        return TwoPartRule.decide(notPaired(block), flag != null && paired(block), half, bed, flag != null,
            flag != null && onlyLowerHalfDrops(state, flag));
    }

    @Nullable
    private static List<String> notPairedSource;
    private static BlockPatterns notPairedPatterns = BlockPatterns.of(Collections.<String>emptyList());
    @Nullable
    private static List<String> pairedSource;
    private static BlockPatterns pairedPatterns = BlockPatterns.of(Collections.<String>emptyList());
    /** The drop probe's answer per block: it depends on the class's code, which does not change. */
    private static final Map<Block, Boolean> DROP_PROBES = new ConcurrentHashMap<>();

    /**
     * Whether {@code writes.notPairedBlocks} names this block. A stored flag says a class can be two
     * cells, not that every block of it is: LBE's casino machines are one class, and its tables carry
     * the cabinets' 'top' flag but are always one cell (#105). Only the class knows, so the list says.
     */
    static boolean notPaired(Block block) {
        List<String> source = McmcpConfig.getNotPairedBlocks();
        if (source != notPairedSource) {
            notPairedPatterns = BlockPatterns.of(source);
            notPairedSource = source;
        }
        return matches(notPairedPatterns, block);
    }

    /** Whether {@code writes.pairedBlocks} names this block: its stored flag is a half, whatever it drops. */
    static boolean paired(Block block) {
        List<String> source = McmcpConfig.getPairedBlocks();
        if (source != pairedSource) {
            pairedPatterns = BlockPatterns.of(source);
            pairedSource = source;
        }
        return matches(pairedPatterns, block);
    }

    private static boolean matches(BlockPatterns patterns, Block block) {
        if (patterns.isEmpty()) {
            return false;
        }
        ResourceLocation name = block.getRegistryName();
        return name != null && patterns.matches(name.toString());
    }

    /**
     * Whether the half with the flag set drops nothing and the half with it clear drops an item. A real
     * two-cell block drops from one half only, or breaking it would hand back two; a one-cell block
     * whose flag is a variant (a cable tray hung high) drops itself either way.
     */
    private static boolean onlyLowerHalfDrops(IBlockState state, PropertyBool flag) {
        Block block = state.getBlock();
        Boolean known = DROP_PROBES.get(block);
        if (known != null) {
            return known;
        }
        boolean result;
        try {
            Random random = new Random(0);
            Item upper = block.getItemDropped(state.withProperty(flag, true), random, 0);
            Item lower = block.getItemDropped(state.withProperty(flag, false), random, 0);
            result = isNothing(upper) && !isNothing(lower);
        }
        catch (RuntimeException e) {
            // A mod's drop code that needs a world: no evidence either way, so not a pair.
            result = false;
        }
        DROP_PROBES.put(block, result);
        return result;
    }

    private static boolean isNothing(@Nullable Item item) {
        return item == null || item == Items.AIR;
    }

    /** Whether a state is the second half: a door's or plant's upper half, or a bed's head. */
    static boolean isSecondHalf(IBlockState state) {
        IProperty<?> half = halfProperty(state);
        if (half != null) {
            return "upper".equals(valueName(state, half));
        }
        PropertyBool upper = upperFlag(state);
        if (upper != null) {
            return state.getValue(upper);
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
        PropertyBool upper = upperFlag(first);
        if (upper != null) {
            return first.withProperty(upper, true);
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
        /** Second halves this writer changed while writing their first half. */
        private final java.util.Set<Long> changedPartners = new java.util.HashSet<>();
        int partnersWritten;
        int oldPartnersCleared;

        /** Whether this writer put a second half at {@code pos} while writing its first half. */
        boolean wrotePartnerAt(BlockPos pos) {
            return changedPartners.contains(pos.toLong());
        }
        /** False when the caller asked for every cell to be written as given (pairTwoPart: false). */
        boolean pairing = true;
        /** Second halves made up rather than given, the first few by position: a caller can check them. */
        final JsonArray derivedAt = new JsonArray();
        /** Blocks with an upper/top flag and no other sign of being two cells, written as given (#130). */
        private final Set<String> suspected = new LinkedHashSet<>();
        int suspectedCells;
        /** Where neighbour updates go when they are held to the end of a slice, or null. */
        @Nullable
        DeferredNeighbours deferred;

        /**
         * @param given the two-part cells in this write, by {@link BlockPos#toLong()}
         * @param flags the write's flags: 3 notifies neighbours once both halves are in place
         */
        Writer(Map<Long, IBlockState> given, int flags) {
            this.given = given == null ? Collections.<Long, IBlockState>emptyMap() : given;
            this.flags = flags;
        }

        /**
         * Counts one cell of the write whose block {@link #verdict} called {@link TwoPartRule.Verdict#SUSPECTED}:
         * written as given, and named in the reply so a caller whose block really is two cells can say so.
         * Nothing is counted when pairing is off for the call.
         */
        void suspect(IBlockState state) {
            if (!pairing) {
                return;
            }
            suspectedCells++;
            ResourceLocation name = state.getBlock().getRegistryName();
            if (name != null && suspected.size() < SUSPECTED_LISTED) {
                suspected.add(name.toString());
            }
        }

        /**
         * Writes one cell if it is two-part.
         *
         * @param partner the second half to use, overriding what the write gives or what would be
         *                derived; null for the usual choice
         */
        Outcome write(World world, BlockPos pos, IBlockState state, @Nullable IBlockState partner,
                      @Nullable Recorder recorder) {
            if (!pairing || !isTwoPart(state)) {
                return Outcome.NOT_TWO_PART;
            }
            BlockPos otherPos = partnerPos(state, pos);
            if (isSecondHalf(state)) {
                IBlockState first = given.get(otherPos.toLong());
                if (first != null && !isSecondHalf(first) && first.getBlock() == state.getBlock()) {
                    // Written with its first half, now or later in this write; unchanged if it already was.
                    return changedPartners.contains(pos.toLong()) || world.getBlockState(pos) != state
                        ? Outcome.DONE_BY_PARTNER : Outcome.UNCHANGED;
                }
                // A second half on its own: written quietly, and left to whoever places its first half.
                return set(world, pos, state, recorder) ? Outcome.WRITTEN : unchangedOrFailed(world, pos, state);
            }
            IBlockState other = partner;
            boolean derived = false;
            if (other == null) {
                IBlockState fromWrite = given.get(otherPos.toLong());
                derived = fromWrite == null || !isSecondHalf(fromWrite) || fromWrite.getBlock() != state.getBlock();
                other = derived ? derivedSecondHalf(state) : fromWrite;
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
            if (secondChanged) {
                changedPartners.add(otherPos.toLong());
                if (!given.containsKey(otherPos.toLong())) {
                    partnersWritten++;
                }
                if (derived && derivedAt.size() < LISTED) {
                    derivedAt.add(GameJson.blockPos(otherPos));
                }
            }
            if ((flags & 1) != 0 && deferred != null) {
                deferred.add(pos, state.getBlock(), state);
                deferred.add(otherPos, other.getBlock(), other);
            }
            else if ((flags & 1) != 0) {
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
            IBlockState old = world.getBlockState(pos);
            if (old == state) {
                return false;
            }
            if (recorder != null) {
                recorder.beforeChange(pos);
            }
            boolean changed;
            try {
                changed = SkyLight.setBlockState(world, pos, state, QUIET);
            }
            catch (RuntimeException e) {
                // A hook threw (#81): what it changed still belongs in the undo point.
                if (recorder != null && world.getBlockState(pos) != old) {
                    recorder.afterChange(pos);
                }
                throw e;
            }
            if (changed) {
                if (recorder != null) {
                    recorder.afterChange(pos);
                }
            }
            return changed;
        }

        /**
         * Adds {@code derivedAt} to a write's {@code pairs} block, when any half was made up, and
         * {@code suspected}/{@code suspectedCells} when a cell had an upper/top flag that was not taken
         * for a half: a caller whose block really is two cells names it in {@code writes.pairedBlocks}.
         */
        void report(JsonObject json) {
            JsonObject pairs = json.getAsJsonObject("pairs");
            if (pairs != null && derivedAt.size() > 0) {
                pairs.add("derivedAt", derivedAt);
            }
            if (suspectedCells > 0) {
                if (pairs == null) {
                    pairs = new JsonObject();
                    json.add("pairs", pairs);
                }
                JsonArray names = new JsonArray();
                for (String name : suspected) {
                    names.add(name);
                }
                pairs.add("suspected", names);
                pairs.addProperty("suspectedCells", suspectedCells);
            }
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

    /**
     * A stored boolean {@code upper} or {@code top}, or null. Stored means the metadata of the state
     * with it set differs from the state with it clear.
     */
    @Nullable
    @SuppressWarnings("deprecation")
    static PropertyBool upperFlag(IBlockState state) {
        for (IProperty<?> property : state.getPropertyKeys()) {
            if (!(property instanceof PropertyBool)
                || !"upper".equals(property.getName()) && !"top".equals(property.getName())) {
                continue;
            }
            PropertyBool flag = (PropertyBool) property;
            Block block = state.getBlock();
            try {
                if (block.getMetaFromState(state.withProperty(flag, true))
                    != block.getMetaFromState(state.withProperty(flag, false))) {
                    return flag;
                }
            }
            catch (RuntimeException e) {
                // A mod's getMetaFromState that throws on a state it does not expect: not a pair.
                return null;
            }
        }
        return null;
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
