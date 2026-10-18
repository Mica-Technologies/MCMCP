package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Neighbour updates held back until a slice of a bulk write is complete (#85).
 *
 * <p>A block several cells big checks its other cells when a neighbour changes, and removes itself if
 * they are not there: CSM's 2x2 beds take any cell whose cell 0 is missing, LBE's casino cabinets a
 * lone half. A bulk write sets one cell at a time, and with neighbour updates each one tells the cells
 * next to it straight away, so a cell written before the rest of its block hears of a neighbour,
 * finds the block incomplete and goes. All 108 king beds of one casino build lost their heads that
 * way, and 60 of 62 cabinets vanished, each reported as written. Held back to the end of the slice,
 * the updates arrive once every cell of the block is in.
 *
 * <p>The notification is what a write with neighbour updates would have sent: the block that was
 * there, then the block now there when it differs (see {@link BlockWriteFaults#set}), and the
 * comparator update. Also reads back what was written, so a cell the world did not keep is reported
 * rather than counted as written. Game thread.
 */
final class DeferredNeighbours {

    /** Cells listed in a result. */
    private static final int MAX_LISTED = 20;

    private final List<BlockPos> positions = new ArrayList<>();
    private final List<Block> before = new ArrayList<>();
    private final List<IBlockState> written = new ArrayList<>();

    int notKept;
    int faults;
    final JsonArray notKeptAt = new JsonArray();

    /** A cell changed from {@code was} to {@code now}, whose neighbours are still to be told. */
    void add(BlockPos pos, Block was, IBlockState now) {
        positions.add(pos.toImmutable());
        before.add(was);
        written.add(now);
    }

    /** Tells every changed cell's neighbours, then checks each still holds what was written. */
    void flush(World world) {
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            IBlockState now = world.getBlockState(pos);
            try {
                world.notifyNeighborsRespectDebug(pos, before.get(i), true);
                if (now.getBlock() != before.get(i)) {
                    world.notifyNeighborsRespectDebug(pos, now.getBlock(), false);
                }
                if (now.hasComparatorInputOverride()) {
                    world.updateComparatorOutputLevel(pos, now.getBlock());
                }
            }
            catch (RuntimeException e) {
                // A neighbour's hook threw (#81). The cell is written; its neighbours heard of it in part.
                faults++;
                BlockWriteFaults.reason(e, pos, String.valueOf(now.getBlock().getRegistryName()));
            }
        }
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            IBlockState now = world.getBlockState(pos);
            // The block, not the state: a block may set its own facing or connections once placed.
            if (now.getBlock() != written.get(i).getBlock()) {
                notKept++;
                if (notKeptAt.size() < MAX_LISTED) {
                    JsonObject item = GameJson.blockPos(pos);
                    item.addProperty("wrote", String.valueOf(written.get(i).getBlock().getRegistryName()) + ":"
                        + written.get(i).getBlock().getMetaFromState(written.get(i)));
                    item.addProperty("now", String.valueOf(now.getBlock().getRegistryName()) + ":"
                        + now.getBlock().getMetaFromState(now));
                    notKeptAt.add(item);
                }
            }
        }
        positions.clear();
        before.clear();
        written.clear();
    }

    /** Adds what was not kept, when anything was not, to a write's result. */
    void report(JsonObject result) {
        if (notKept > 0) {
            result.addProperty("notKept", notKept);
            result.add("notKeptAt", notKeptAt);
            result.addProperty("notKeptNote", "These cells were written but the world did not keep them: usually "
                + "part of a block several cells big that removed itself. Their undo point still has them.");
        }
        if (faults > 0) {
            result.addProperty("neighbourUpdateFaults", faults);
        }
    }

    /** The ids of the item entities in a box, to tell afterwards which ones a write dropped. Game thread. */
    static Set<Integer> itemsIn(World world, AxisAlignedBB box) {
        Set<Integer> ids = new HashSet<>();
        for (EntityItem item : world.getEntitiesWithinAABB(EntityItem.class, box)) {
            ids.add(item.getEntityId());
        }
        return ids;
    }

    /** How many item entities in the box were not there before. Game thread. */
    static int itemsDropped(World world, AxisAlignedBB box, Set<Integer> before) {
        int dropped = 0;
        for (EntityItem item : world.getEntitiesWithinAABB(EntityItem.class, box)) {
            if (!before.contains(item.getEntityId())) {
                dropped++;
            }
        }
        return dropped;
    }
}
