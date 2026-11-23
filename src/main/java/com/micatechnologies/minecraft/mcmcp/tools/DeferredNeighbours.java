package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

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
            // A door's other half that removes itself on hearing of this one drops here (#184).
            WriteDrops.begin(world, pos);
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
            finally {
                WriteDrops.end();
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

    /** Chunks looked at for items in one game-thread task: a hash lookup and a short list each. */
    private static final int SCAN_SLICE = 2048;

    /**
     * The chunks a write's dropped items can be in: every cell's own, and its neighbour's where the cell
     * is on a chunk's edge, since an item spawns up to a block from the cell that dropped it.
     *
     * <p>A set of chunks, never a box round the cells (#219). The cost of looking for entities in a box
     * is its area in chunks, and a box round a call's cells is as large as its two farthest cells: with
     * no cells at all it was the whole integer range, and vanilla's lookup walked it on the server
     * thread until the server was killed. A set of chunks costs what the cells do.
     */
    static long[] dropChunks(int[] xs, int[] zs) {
        Set<Long> chunks = new java.util.LinkedHashSet<>();
        for (int i = 0; i < xs.length; i++) {
            int minX = (xs[i] - 1) >> 4;
            int maxX = (xs[i] + 1) >> 4;
            int minZ = (zs[i] - 1) >> 4;
            int maxZ = (zs[i] + 1) >> 4;
            for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cx = minX; cx <= maxX; cx++) {
                    chunks.add(ChunkKeys.of(cx, cz));
                }
            }
        }
        long[] keys = new long[chunks.size()];
        int at = 0;
        for (Long key : chunks) {
            keys[at++] = key;
        }
        return keys;
    }

    /**
     * The ids of the item entities in {@code chunks}, to tell afterwards which ones a write dropped.
     * From the tool's thread: the look goes through the game thread a slice of chunks at a time, so its
     * longest task is bounded whatever the call's size. Chunks not loaded hold no items and load nothing.
     *
     * @param followUp after the write, when the look must not be dropped for a cancel
     */
    static Set<Integer> itemsIn(ToolContext context, final int dimension, final long[] chunks, boolean followUp) {
        final Set<Integer> ids = new HashSet<>();
        for (int from = 0; from < chunks.length; from += SCAN_SLICE) {
            final int start = from;
            final int end = Math.min(chunks.length, from + SCAN_SLICE);
            java.util.concurrent.Callable<Void> slice = () -> {
                collectItems(ServerWorldTools.requireWorld(dimension), chunks, start, end, ids);
                return null;
            };
            if (followUp) {
                context.onGameThreadFollowUp(slice);
            }
            else {
                context.onGameThread(slice);
            }
        }
        return ids;
    }

    /** How many item entities in {@code chunks} were not there {@code before}. From the tool's thread. */
    static int itemsDropped(ToolContext context, int dimension, long[] chunks, Set<Integer> before) {
        Set<Integer> now = itemsIn(context, dimension, chunks, true);
        now.removeAll(before);
        return now.size();
    }

    /** Game thread: adds the ids of the items in chunks {@code [from, to)} of {@code chunks}. */
    private static void collectItems(World world, long[] chunks, int from, int to, Set<Integer> into) {
        for (int i = from; i < to; i++) {
            if ((i & 255) == 0) {
                com.micatechnologies.minecraft.mcmcp.game.ServerTaskPump.checkpoint();
            }
            Chunk chunk = world.getChunkProvider().getLoadedChunk(ChunkKeys.x(chunks[i]), ChunkKeys.z(chunks[i]));
            if (chunk == null) {
                continue;
            }
            for (ClassInheritanceMultiMap<Entity> slice : chunk.getEntityLists()) {
                for (EntityItem item : slice.getByClass(EntityItem.class)) {
                    into.add(item.getEntityId());
                }
            }
        }
    }
}
