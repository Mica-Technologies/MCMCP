package com.micatechnologies.minecraft.mcmcp.tools;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Sky light that vanilla gets wrong after bulk writes (#82).
 *
 * <p>When a write puts the first block into an empty 16-high section, {@code Chunk.setBlockState}
 * creates the section with sky light 0 in every cell, and fills it in from the sky only if that
 * block is at or above its own column's height. Written under or inside anything, it is not: the
 * whole 16 x 16 x 16 section, open-sky columns included, reads as dark on the server. The client
 * works out its own light and looks right, until the chunk is sent whole, by a relight or a rejoin,
 * and the server's zeros arrive as sharp chunk-aligned black patches.
 *
 * <p>A cell at or above its column's height sees the sky, so its sky light is exactly 15, with no
 * propagation needed: that is what {@link #openSky} writes. It only ever raises light.
 *
 * <p>Game thread only.
 */
final class SkyLight {

    private SkyLight() {
    }

    /**
     * {@code world.setBlockState}, repairing the section's open-sky cells if the write created it.
     */
    static boolean setBlockState(World world, BlockPos pos, IBlockState state, int flags) {
        if (!world.provider.hasSkyLight() || pos.getY() < 0 || pos.getY() > 255 || !world.isBlockLoaded(pos)) {
            return world.setBlockState(pos, state, flags);
        }
        Chunk chunk = world.getChunk(pos);
        int section = pos.getY() >> 4;
        boolean wasEmpty = chunk.getBlockStorageArray()[section] == Chunk.NULL_BLOCK_STORAGE;
        try {
            return world.setBlockState(pos, state, flags);
        }
        finally {
            if (wasEmpty && chunk.getBlockStorageArray()[section] != Chunk.NULL_BLOCK_STORAGE) {
                openSky(chunk, 0, 15, 0, 15, section << 4, (section << 4) + 15);
            }
        }
    }

    /**
     * Sets sky light 15 in every cell of the given chunk-local columns, between {@code fromY} and
     * {@code toY}, that is at or above its column's height. Sections that do not exist are skipped:
     * an absent section already reads as sky-lit where it sees the sky.
     *
     * @return how many cells were raised
     */
    static int openSky(Chunk chunk, int fromX, int toX, int fromZ, int toZ, int fromY, int toY) {
        if (!chunk.getWorld().provider.hasSkyLight()) {
            return 0;
        }
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        int[] heights = chunk.getHeightMap();
        int raised = 0;
        for (int z = fromZ; z <= toZ; z++) {
            for (int x = fromX; x <= toX; x++) {
                int y = Math.max(fromY, Math.max(0, heights[z << 4 | x]));
                while (y <= toY && y <= 255) {
                    ExtendedBlockStorage storage = sections[y >> 4];
                    if (storage == Chunk.NULL_BLOCK_STORAGE) {
                        y = (y | 15) + 1;
                        continue;
                    }
                    if (storage.getSkyLight(x, y & 15, z) != 15) {
                        storage.setSkyLight(x, y & 15, z, 15);
                        raised++;
                    }
                    y++;
                }
            }
        }
        if (raised > 0) {
            chunk.markDirty();
        }
        return raised;
    }

    /**
     * Recomputes the height of the given chunk-local columns from the blocks in them: the lowest y
     * with nothing that blocks light above it. A height left too high makes vanilla treat open
     * ground as covered, and a relight then spreads darkness from it.
     *
     * @return how many columns' heights were wrong
     */
    static int heights(Chunk chunk, int fromX, int toX, int fromZ, int toZ) {
        int[] heights = chunk.getHeightMap().clone();
        int top = chunk.getTopFilledSegment() + 16;
        int baseX = chunk.x << 4;
        int baseZ = chunk.z << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int changed = 0;
        for (int z = fromZ; z <= toZ; z++) {
            for (int x = fromX; x <= toX; x++) {
                int y = top;
                while (y > 0 && chunk.getBlockLightOpacity(pos.setPos(baseX + x, y - 1, baseZ + z)) == 0) {
                    y--;
                }
                if (heights[z << 4 | x] != y) {
                    heights[z << 4 | x] = y;
                    changed++;
                }
            }
        }
        if (changed > 0) {
            // Through the setter, which also keeps the chunk's lowest-height record in step.
            chunk.setHeightMap(heights);
            chunk.markDirty();
        }
        return changed;
    }
}
