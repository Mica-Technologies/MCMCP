package com.micatechnologies.minecraft.mcmcp.tools;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * An exception thrown while one cell of a bulk write is set, made into that cell's refusal (#81).
 *
 * <p>Setting a block runs the old block's {@code breakBlock}, the new one's {@code onBlockAdded}, and
 * with neighbour updates every neighbour's {@code neighborChanged}: mod code, any of which can throw.
 * Escaping the slice, one such exception ended the whole call with the cells before it written and
 * nothing said about them. Caught per cell instead, it costs that cell, and the reason names the class
 * and method that threw, so the fault can be taken to the mod it belongs to.
 *
 * <p>The cell may be left half-changed: whatever the hook did before it threw stays done.
 */
final class BlockWriteFaults {

    /** Distinct faults whose stack trace has been logged; bounded so a long run cannot grow it. */
    private static final Set<String> LOGGED = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 64;
        }
    });

    private BlockWriteFaults() {
    }

    /**
     * The refusal reason for {@code e}, thrown writing {@code block} at {@code pos}. Logs the full
     * stack trace the first time this fault is seen, since the reply carries only its top.
     */
    static String reason(RuntimeException e, BlockPos pos, String block) {
        StackTraceElement blamed = blamed(e);
        String where = blamed == null ? "" : " in " + blamed.getClassName() + "." + blamed.getMethodName();
        String message = e.getMessage() == null ? "" : ": " + e.getMessage();
        if (message.length() > 300) {
            message = message.substring(0, 300) + "...";
        }
        String key = e.getClass().getName() + where;
        boolean first;
        synchronized (LOGGED) {
            first = LOGGED.add(key);
        }
        if (first) {
            Mcmcp.LOGGER.warn("Writing {} at {} threw; the cell is refused and the write goes on", block, pos, e);
        }
        return "writing it threw " + e.getClass().getSimpleName() + where + message
            + ". That is the block's own code; the cell may be left half-changed";
    }

    /**
     * {@code world.setBlockState} through {@link SkyLight#setBlockState}, except that a cell the
     * write changed before a hook threw still goes into the undo point, so undoing the call restores it. The exception is rethrown.
     *
     * <p>With neighbour updates (flag 1) the neighbours are told twice when the block changes.
     * {@code World.setBlockState} tells them the block that <em>was</em> there, as a player's placement
     * does; {@code /setblock} tells them the block now there. Some mods' {@code neighborChanged} looks
     * at that block and reacts only to the second: a redstone block written beside an LDME elevator
     * controller powered it through {@code /setblock} and not through a write here (#92).
     */
    static boolean set(World world, BlockPos pos, IBlockState state, int flags,
                       @Nullable UndoPoints.Recorder recorder, @Nullable UndoPoints.Before was) {
        return set(world, pos, state, flags, recorder, was, null);
    }

    /**
     * As {@link #set(World, BlockPos, IBlockState, int, UndoPoints.Recorder, UndoPoints.Before)}, but with
     * neighbour updates (flag 1) held in {@code deferred}, when given, until its slice is complete.
     */
    static boolean set(World world, BlockPos pos, IBlockState state, int flags,
                       @Nullable UndoPoints.Recorder recorder, @Nullable UndoPoints.Before was,
                       @Nullable DeferredNeighbours deferred) {
        if (deferred != null && (flags & 1) != 0) {
            Block before = world.getBlockState(pos).getBlock();
            boolean changed;
            try {
                changed = SkyLight.setBlockState(world, pos, state, flags & ~1);
            }
            catch (RuntimeException e) {
                IBlockState now = world.getBlockState(pos);
                if (recorder != null && was != null && now != was.state) {
                    recorder.record(pos, was, now);
                }
                throw e;
            }
            if (changed) {
                deferred.add(pos, before, state);
            }
            return changed;
        }
        try {
            Block before = world.getBlockState(pos).getBlock();
            boolean changed = SkyLight.setBlockState(world, pos, state, flags);
            if (changed && (flags & 1) != 0 && before != state.getBlock()) {
                world.notifyNeighborsRespectDebug(pos, state.getBlock(), false);
            }
            return changed;
        }
        catch (RuntimeException e) {
            IBlockState now = world.getBlockState(pos);
            if (recorder != null && was != null && now != was.state) {
                recorder.record(pos, was, now);
            }
            throw e;
        }
    }

    /** The first frame outside Java, Minecraft, Forge and MCMCP: the mod whose hook threw. */
    static StackTraceElement blamed(Throwable e) {
        StackTraceElement[] trace = e.getStackTrace();
        for (StackTraceElement frame : trace) {
            if (!isPlatform(frame.getClassName())) {
                return frame;
            }
        }
        return trace.length == 0 ? null : trace[0];
    }

    static boolean isPlatform(String className) {
        return className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("sun.")
            || className.startsWith("jdk.") || className.startsWith("net.minecraft.")
            || className.startsWith("net.minecraftforge.") || className.startsWith("com.google.")
            || className.startsWith("com.micatechnologies.minecraft.mcmcp.");
    }
}
