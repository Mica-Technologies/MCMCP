package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.regions.PhysicsLedger;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockStaticLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * {@code holdPhysics} for a bulk write (#118): no sand falls and no fluid flows until the call ends.
 *
 * <p>Both happen only on a scheduled block tick, which the write schedules and a later world tick runs,
 * between the write's game-thread slices. At the end of every slice {@link #capture} takes the ticks of
 * gravity blocks and fluids in the write's box (and one cell around it) out of the world's queue and
 * keeps them; every other tick it takes is put straight back. Nothing global is set, so there is
 * nothing to reset if the call fails: a tick not given back leaves a block still, not the world wrong.
 * {@link #finish} gives every held tick back with what was left of its delay, and reports what would
 * still move. {@code BlockFalling.fallInstantly} was the other way in, and is not one: it makes sand
 * fall at once instead of not at all, and it is a static every world shares.
 *
 * <p>Every method but {@link #finish} runs on the game thread.
 */
final class PhysicsHold {

    private static final long SLICE_NANOS = 25_000_000L;

    private final PhysicsLedger<Block> ledger;
    private final boolean wake;
    private long[] candidates;
    private int cursor;
    private boolean released;

    /**
     * @param box  the cells the write may change: min x, y, z, max x, y, z, inclusive
     * @param wake whether the write sends neighbour updates. With them, a cell found unsupported or
     *             exposed with no held tick is given one, as the updates would have; without, it is only
     *             reported, as a write without updates leaves it
     */
    PhysicsHold(int[] box, boolean wake) {
        this.ledger = new PhysicsLedger<>(box[0], box[1], box[2], box[3], box[4], box[5]);
        this.wake = wake;
    }

    static boolean isPhysics(Block block) {
        return block instanceof BlockFalling || block instanceof BlockLiquid || block instanceof IFluidBlock;
    }

    /** The write changed this cell. */
    void changed(BlockPos pos) {
        ledger.changed(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Game thread: takes the pending gravity and fluid ticks in the box out of the world's queue. */
    void capture(WorldServer world) {
        int[] box = ledger.box();
        // The world's own lookup takes x and z, exclusive at the top, and every y.
        List<NextTickListEntry> taken = world.getPendingBlockUpdates(
            new StructureBoundingBox(box[0], 0, box[2], box[3] + 1, 255, box[5] + 1), true);
        if (taken == null) {
            return;
        }
        long now = world.getTotalWorldTime();
        for (NextTickListEntry entry : taken) {
            BlockPos pos = entry.position;
            Block block = entry.getBlock();
            if (block != null && isPhysics(block) && ledger.inBox(pos.getX(), pos.getY(), pos.getZ())) {
                ledger.hold(pos.getX(), pos.getY(), pos.getZ(), block, entry.scheduledTime, entry.priority);
            }
            else {
                world.scheduleBlockUpdate(pos, block, PhysicsLedger.delayFrom(entry.scheduledTime, now), entry.priority);
            }
        }
    }

    /**
     * Ends the hold: looks for what would still move, in slices, then gives every held tick back. The
     * ticks go back even if the look fails. Call it once, from the tool's thread, whatever happened to
     * the write.
     *
     * @return the result's {@code physics} object
     */
    JsonObject finish(ToolContext context, int dimension) {
        final int[] restored = {0};
        final int[] woken = {0};
        try {
            boolean done = false;
            while (!done) {
                done = context.onGameThread(() -> scan(ServerWorldTools.requireWorld(dimension)));
            }
        }
        finally {
            try {
                context.onGameThread(() -> {
                    restore(ServerWorldTools.requireWorld(dimension), restored, woken);
                    return null;
                });
            }
            catch (RuntimeException e) {
                // Cancelled, or the emergency stop was set: onGameThread refuses, but the ticks still go
                // back, or every block they belong to would stay still until something touched it.
                context.onGameThreadAsync(() -> {
                    try {
                        restore(ServerWorldTools.requireWorld(dimension), restored, woken);
                    }
                    catch (RuntimeException ignored) {
                        // No world any more: nothing to give the ticks back to.
                    }
                });
                throw e;
            }
        }
        return ledger.toJson(restored[0], woken[0], wake);
    }

    /** Game thread: gives every held tick back, and wakes what would move. Runs once. */
    private void restore(WorldServer world, int[] restored, int[] woken) {
        if (released) {
            return;
        }
        released = true;
        // Last, so a tick a slice scheduled after its capture is held too.
        capture(world);
        long now = world.getTotalWorldTime();
        for (PhysicsLedger.Held<Block> held : ledger.held()) {
            BlockPos pos = new BlockPos(held.x, held.y, held.z);
            if (world.isBlockLoaded(pos)) {
                world.scheduleBlockUpdate(pos, held.block, PhysicsLedger.delayFrom(held.scheduledTime, now),
                    held.priority);
                restored[0]++;
            }
        }
        if (wake && candidates != null) {
            woken[0] = wakeMovers(world);
        }
    }

    /** Game thread: one slice of the look. @return true when every candidate has been looked at */
    private boolean scan(WorldServer world) {
        if (candidates == null) {
            capture(world);
            candidates = ledger.candidates();
        }
        long started = System.nanoTime();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        while (cursor < candidates.length) {
            if ((cursor & 255) == 0 && System.nanoTime() - started >= SLICE_NANOS) {
                return false;
            }
            long key = candidates[cursor++];
            int x = PhysicsLedger.x(key);
            int y = PhysicsLedger.y(key);
            int z = PhysicsLedger.z(key);
            check(world, at.setPos(x, y, z));
            for (EnumFacing facing : EnumFacing.VALUES) {
                check(world, at.setPos(x + facing.getXOffset(), y + facing.getYOffset(), z + facing.getZOffset()));
            }
        }
        return true;
    }

    /** Game thread: counts the cell if it would move. */
    private void check(WorldServer world, BlockPos pos) {
        if (pos.getY() < 0 || pos.getY() > 255 || !world.isBlockLoaded(pos)) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof BlockFalling) {
            BlockPos below = pos.down();
            if (pos.getY() > 0 && world.isBlockLoaded(below) && BlockFalling.canFallThrough(world.getBlockState(below))) {
                ledger.mover(PhysicsLedger.Kind.GRAVITY, pos.getX(), pos.getY(), pos.getZ(), id(block));
            }
        }
        else if (block instanceof BlockLiquid || block instanceof IFluidBlock) {
            if (open(world, pos.down())) {
                ledger.mover(PhysicsLedger.Kind.FLUID, pos.getX(), pos.getY(), pos.getZ(), id(block));
                return;
            }
            for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) {
                if (open(world, pos.offset(facing))) {
                    ledger.mover(PhysicsLedger.Kind.FLUID, pos.getX(), pos.getY(), pos.getZ(), id(block));
                    return;
                }
            }
        }
    }

    /** A cell a fluid beside or above it would flow into: air, or something it washes away. */
    private static boolean open(WorldServer world, BlockPos pos) {
        if (pos.getY() < 0 || pos.getY() > 255 || !world.isBlockLoaded(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        Material material = state.getMaterial();
        return material == Material.AIR || !material.isLiquid() && !material.blocksMovement()
            && state.getBlock().isReplaceable(world, pos) && !(state.getBlock() instanceof IFluidBlock);
    }

    /**
     * Game thread: gives a tick to each cell that would move and has none, as the write's neighbour
     * updates would have. @return how many were given one
     */
    private int wakeMovers(WorldServer world) {
        int woken = 0;
        for (long key : ledger.movers()) {
            BlockPos pos = new BlockPos(PhysicsLedger.x(key), PhysicsLedger.y(key), PhysicsLedger.z(key));
            if (!world.isBlockLoaded(pos)) {
                continue;
            }
            Block block = world.getBlockState(pos).getBlock();
            if (!isPhysics(block) || world.isUpdateScheduled(pos, block)) {
                continue;
            }
            try {
                if (block instanceof BlockStaticLiquid) {
                    // A still fluid flows only once it is told of a neighbour: it turns flowing and
                    // schedules its own tick.
                    world.neighborChanged(pos, block, pos.down());
                }
                else {
                    world.scheduleUpdate(pos, block, block.tickRate(world));
                }
                woken++;
            }
            catch (RuntimeException e) {
                // A mod's hook threw (#81); the cell stays as it is and the server log has the trace.
                BlockWriteFaults.reason(e, pos, id(block));
            }
        }
        return woken;
    }

    private static String id(Block block) {
        return String.valueOf(block.getRegistryName());
    }
}
