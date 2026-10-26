package com.micatechnologies.minecraft.mcmcp.chunkload;

import java.util.Iterator;
import java.util.Set;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * The one place MCMCP generates chunks (#138), and so the one exception to {@link ChunkLoadGovernor}
 * never loading a chunk that was not generated.
 *
 * <p>Generation is what a pregeneration pass is for, and it is paced by its caller in chunks per tick,
 * not by the governor's load budget, which is sized for reads. What it shares with the governor is the
 * discipline on giving back: only chunks the pass itself loaded are unloaded, never one a player can
 * see or something forces, and through {@code queueUnload}, which saves each chunk as the server
 * unloads it, a hundred a tick at most. Nothing here ever saves or unloads the world as a whole.
 * Forge Essentials' pregenerator did that every tick ({@code saveChunks(true)} and
 * {@code queueUnloadAll()}), which took a busy server down to 1-7 TPS and then crashed it.
 *
 * <p>Server thread only.
 */
public final class ChunkPregen {

    /** What one step did. */
    public enum Touched { ALREADY_LOADED, LOADED, GENERATED }

    private ChunkPregen() {
    }

    /** Whether a chunk exists, loaded or on disk. Reads the region file header; loads nothing. */
    public static boolean exists(WorldServer world, int chunkX, int chunkZ) {
        return world.getChunkProvider().isChunkGeneratedAt(chunkX, chunkZ);
    }

    /**
     * Loads or generates one chunk. A chunk already in memory is left as it is and not added to
     * {@code ours}: the pass did not load it, so it is not the pass's to give back.
     */
    public static Touched touch(WorldServer world, PregenPlan.Step step, Set<Long> ours) {
        ChunkProviderServer provider = world.getChunkProvider();
        if (provider.chunkExists(step.x, step.z)) {
            return Touched.ALREADY_LOADED;
        }
        Chunk chunk = step.generate ? provider.provideChunk(step.x, step.z) : provider.loadChunk(step.x, step.z);
        if (chunk == null) {
            // On disk a moment ago, unreadable now: generating over it is not this pass's call.
            return Touched.ALREADY_LOADED;
        }
        ours.add(step.key());
        return step.generate ? Touched.GENERATED : Touched.LOADED;
    }

    /**
     * Gives back the chunks of {@code ours} in rows the pass is done with ({@link PregenPlan#releasable}),
     * or all of them when {@code currentZ} is null.
     *
     * @return how many were queued to unload
     */
    public static int release(WorldServer world, Set<Long> ours, @javax.annotation.Nullable Integer currentZ) {
        ChunkProviderServer provider = world.getChunkProvider();
        int released = 0;
        for (Iterator<Long> it = ours.iterator(); it.hasNext(); ) {
            long key = it.next();
            int x = ChunkKeys.x(key);
            int z = ChunkKeys.z(key);
            if (currentZ != null && !PregenPlan.releasable(z, currentZ)) {
                continue;
            }
            it.remove();
            if (world.getPlayerChunkMap().contains(x, z) || world.getPersistentChunks().containsKey(new ChunkPos(x, z))) {
                continue;
            }
            Chunk chunk = provider.getLoadedChunk(x, z);
            if (chunk != null) {
                provider.queueUnload(chunk);
                released++;
            }
        }
        return released;
    }
}
