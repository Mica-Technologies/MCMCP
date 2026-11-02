package com.micatechnologies.minecraft.mcmcp.tools;

import javax.annotation.Nullable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Items a direct write knocks loose, discarded as they spawn (#184).
 *
 * <p>Overwriting a cell runs the old block's {@code breakBlock}, with or without neighbour updates, and
 * many mods drop the block from there: CSM's doors and 2x2 beds left an item per door in a hotel
 * re-fitted with {@code server_set_blocks}, a hundred floating in the lobby before anyone looked. A
 * write is not a player breaking a block, so its drops are cancelled at {@code EntityJoinWorldEvent},
 * within a couple of cells of the cell being set (a door's other half drops at its own cell), and
 * counted. A write that wants them asks with {@code drops: "keep"}.
 *
 * <p>Only items and experience orbs, only in the world being written, and only while a cell is being
 * set or its neighbours told: nothing a mob or a player drops elsewhere in the same tick is touched.
 * Server thread only; the fields are never read for another world.
 */
final class WriteDrops {

    /** How far from the cell being set a drop is taken to be its own: a two-cell block's other half. */
    private static final double MARGIN = 2.5;

    /** What one call's drops came to; {@code keep} lets them spawn. */
    static final class Tally {
        final boolean keep;
        int dropped;

        Tally(boolean keep) {
            this.keep = keep;
        }
    }

    /** The {@code drops} argument's description, shared by every write that takes it. */
    static final String SCHEMA = "What an overwritten block drops (a door, a bed, a chest's contents): "
        + "'discard' (default) cancels the items, counted as itemsDropped; 'keep' leaves them in the world.";

    /**
     * Adds {@code itemsDropped} to a write's result when anything dropped: those caught as they spawned,
     * plus {@code later}, items found in the box afterwards that were not caught (they spawned a tick on).
     */
    static void add(com.google.gson.JsonObject json, Tally drops, int later) {
        int total = drops.keep ? Math.max(drops.dropped, later) : drops.dropped + later;
        if (total == 0) {
            return;
        }
        json.addProperty("itemsDropped", total);
        json.addProperty("drops", drops.keep ? "kept" : later > 0 ? "discarded " + drops.dropped + ", "
            + later + " left in the world" : "discarded");
    }

    private static volatile boolean registered;

    // Server thread only. A cell's write may run another (a pair's other half), hence the depth.
    private static World world;
    private static int depth;
    private static double minX;
    private static double minY;
    private static double minZ;
    private static double maxX;
    private static double maxY;
    private static double maxZ;
    @Nullable
    private static Tally tally;

    private WriteDrops() {
    }

    /** Makes {@code next} the tally drops are counted in, returning the one it replaces. */
    @Nullable
    static Tally count(@Nullable Tally next) {
        Tally previous = tally;
        tally = next;
        return previous;
    }

    /** Starts watching for drops around {@code pos}. Pair every call with {@link #end()}. */
    static void begin(World target, BlockPos pos) {
        if (!registered) {
            register();
        }
        double x0 = pos.getX() - MARGIN;
        double y0 = pos.getY() - MARGIN;
        double z0 = pos.getZ() - MARGIN;
        double x1 = pos.getX() + 1 + MARGIN;
        double y1 = pos.getY() + 1 + MARGIN;
        double z1 = pos.getZ() + 1 + MARGIN;
        if (depth++ == 0 || world != target) {
            world = target;
            minX = x0;
            minY = y0;
            minZ = z0;
            maxX = x1;
            maxY = y1;
            maxZ = z1;
            return;
        }
        minX = Math.min(minX, x0);
        minY = Math.min(minY, y0);
        minZ = Math.min(minZ, z0);
        maxX = Math.max(maxX, x1);
        maxY = Math.max(maxY, y1);
        maxZ = Math.max(maxZ, z1);
    }

    static void end() {
        if (depth > 0 && --depth == 0) {
            world = null;
        }
    }

    private static synchronized void register() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.register(new Events());
            registered = true;
        }
    }

    /** Nested so that a second {@link #register()} cannot subscribe a second copy. */
    public static class Events {

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onJoin(EntityJoinWorldEvent event) {
            World watched = world;
            if (watched == null || event.getWorld() != watched || event.getWorld().isRemote) {
                return;
            }
            Entity entity = event.getEntity();
            boolean item = entity instanceof EntityItem;
            if (!item && !(entity instanceof EntityXPOrb)) {
                return;
            }
            if (entity.posX < minX || entity.posX > maxX || entity.posY < minY || entity.posY > maxY
                || entity.posZ < minZ || entity.posZ > maxZ) {
                return;
            }
            Tally current = tally;
            if (item && current != null) {
                current.dropped++;
            }
            if (current == null || !current.keep) {
                event.setCanceled(true);
            }
        }
    }
}
