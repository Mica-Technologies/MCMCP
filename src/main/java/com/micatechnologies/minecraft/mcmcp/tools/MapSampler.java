package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Reads a map's columns from a world: the top block of each, its map colour, its height and water
 * depth. Used by {@code client_render_map} on the client and by undo points on the server, so it
 * names no client class; its methods run on whichever game thread owns the world, and its results are
 * plain values.
 */
public final class MapSampler {

    /** Most top-block kinds listed in {@link #topBlocks()}. */
    private static final int TOP_BLOCKS_LISTED = 12;

    /** Most unloaded chunks named; the count beyond this stays exact. */
    private static final int MAX_LISTED_CHUNKS = 64;

    private final int minX;
    private final int minZ;
    private final int scale;
    private final int yMax;
    private final BlockPatterns highlight;

    /** Building an id string per block is most of the cost; there are few states. */
    private final Map<IBlockState, String> ids = new IdentityHashMap<>();
    private final Map<String, int[]> topCounts = new HashMap<>();
    private final Map<Long, Boolean> unloadedSeen = new HashMap<>();
    public final JsonArray unloadedList = new JsonArray();
    public int unloadedCount;
    public int highlighted;

    public MapSampler(int minX, int minZ, int scale, int yMax, BlockPatterns highlight) {
        this.minX = minX;
        this.minZ = minZ;
        this.scale = scale;
        this.yMax = yMax;
        this.highlight = highlight;
    }

    public void sampleRows(World world, MapImage.Columns columns, int fromRow, int toRow) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int pz = fromRow; pz < toRow; pz++) {
            // The middle of the pixel's block square, so a scaled map is not biased north-west.
            int z = minZ + pz * scale + scale / 2;
            for (int px = 0; px < columns.width(); px++) {
                int x = minX + px * scale + scale / 2;
                Chunk chunk = world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
                if (chunk == null || chunk.isEmpty()) {
                    columns.setUnloaded(px, pz);
                    noteUnloaded(x >> 4, z >> 4);
                    continue;
                }
                sampleColumn(world, chunk, columns, px, pz, x, z, pos);
            }
        }
    }

    private void sampleColumn(World world, Chunk chunk, MapImage.Columns columns, int px, int pz,
                              int x, int z, BlockPos.MutableBlockPos pos) {
        int top = Math.min(yMax, chunk.getTopFilledSegment() + 15);
        for (int y = top; y >= 0; y--) {
            IBlockState state = chunk.getBlockState(x, y, z);
            if (state.getMaterial() == Material.AIR) {
                continue;
            }
            String id = idOf(state);
            if (!highlight.isEmpty() && highlight.matches(id)) {
                columns.set(px, pz, MapImage.HIGHLIGHT_RGB, y, 0, true);
                count(id);
                highlighted++;
                return;
            }
            pos.setPos(x, y, z);
            MapColor colour = state.getMapColor(world, pos);
            if (colour == MapColor.AIR) {
                // Glass, torches, flowers: vanilla maps look through them, and so does this.
                continue;
            }
            int depth = 0;
            if (state.getMaterial().isLiquid()) {
                while (depth < 32 && y - depth >= 0
                    && chunk.getBlockState(x, y - depth, z).getMaterial().isLiquid()) {
                    depth++;
                }
            }
            columns.set(px, pz, colour.colorValue, y, depth, false);
            count(id);
            return;
        }
        columns.setEmpty(px, pz);
    }

    private String idOf(IBlockState state) {
        String id = ids.get(state);
        if (id == null) {
            ResourceLocation name = state.getBlock().getRegistryName();
            id = name == null ? "unknown" : name.toString();
            int metadata = state.getBlock().getMetaFromState(state);
            if (metadata != 0) {
                id = id + ":" + metadata;
            }
            ids.put(state, id);
        }
        return id;
    }

    private void count(String id) {
        int[] count = topCounts.get(id);
        if (count == null) {
            topCounts.put(id, new int[]{1});
        }
        else {
            count[0]++;
        }
    }

    private void noteUnloaded(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        if (unloadedSeen.put(key, Boolean.TRUE) != null) {
            return;
        }
        unloadedCount++;
        if (unloadedList.size() < MAX_LISTED_CHUNKS) {
            JsonArray pair = new JsonArray();
            pair.add(chunkX);
            pair.add(chunkZ);
            unloadedList.add(pair);
        }
    }

    /** The commonest top blocks, most common first, as sampled columns. */
    public JsonObject topBlocks() {
        List<Map.Entry<String, int[]>> entries = new ArrayList<>(topCounts.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        JsonObject json = new JsonObject();
        for (int i = 0; i < Math.min(TOP_BLOCKS_LISTED, entries.size()); i++) {
            json.addProperty(entries.get(i).getKey(), entries.get(i).getValue()[0]);
        }
        return json;
    }
}
