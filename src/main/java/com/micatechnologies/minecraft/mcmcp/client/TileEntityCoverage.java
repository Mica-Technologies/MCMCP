package com.micatechnologies.minecraft.mcmcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Whether every tile entity a frame should draw is in the renderer's lists (#96).
 *
 * <p>Blocks drawn by a tile entity renderer (CSM's signal heads and mast arms) are drawn from lists
 * each render chunk fills when it compiles, with {@code getTileEntity(pos, CHECK)}, which never makes
 * one. A chunk a client has just been sent compiles on a builder thread while the client thread is
 * still creating that chunk's tile entities from the packet; a compile that runs first has a mesh and
 * no tile entities, and they stay undrawn until something rebuilds the chunk. That is how a
 * {@code bringPlayer} capture came back with the junction's terrain and none of its signals,
 * {@code meshesSettled: true}, while the blocks were all there. The same goes for a compiled list
 * still holding a tile entity the client has since replaced.
 *
 * <p>Checked against the client's own tile entities near the camera: each with a renderer and in its
 * draw distance must be in its render chunk's compiled list, or in the global set. Client thread.
 */
@SideOnly(Side.CLIENT)
public final class TileEntityCoverage {

    /** Positions listed in a result. */
    private static final int MAX_LISTED = 16;

    @Nullable
    private static final Field VIEW_FRUSTUM = find("field_175008_n");
    @Nullable
    private static final Field GLOBAL_TILE_ENTITIES = find("field_181024_n");

    private TileEntityCoverage() {
    }

    @Nullable
    private static Field find(String srgName) {
        try {
            return ObfuscationReflectionHelper.findField(RenderGlobal.class, srgName);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** What a check found. */
    public static final class Result {

        public int inRange;
        public int listed;
        public final List<BlockPos> missing = new ArrayList<>();
        public boolean checked;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("inRange", inRange);
            json.addProperty("drawn", listed);
            if (!missing.isEmpty()) {
                json.addProperty("missing", missing.size());
                JsonArray at = new JsonArray();
                for (int i = 0; i < missing.size() && i < MAX_LISTED; i++) {
                    at.add(GameJson.blockPos(missing.get(i)));
                }
                json.add("missingAt", at);
            }
            return json;
        }
    }

    /** Checks the tile entities in draw distance of the render view. Client thread. */
    public static Result check(Minecraft mc) {
        Result result = new Result();
        Entity view = mc.getRenderViewEntity();
        if (mc.world == null || view == null || VIEW_FRUSTUM == null || GLOBAL_TILE_ENTITIES == null) {
            return result;
        }
        ViewFrustum frustum;
        Collection<?> global;
        try {
            frustum = (ViewFrustum) VIEW_FRUSTUM.get(mc.renderGlobal);
            Object set = GLOBAL_TILE_ENTITIES.get(mc.renderGlobal);
            global = set instanceof Collection ? (Collection<?>) set : Collections.emptySet();
        }
        catch (IllegalAccessException | RuntimeException e) {
            return result;
        }
        if (frustum == null || frustum.renderChunks == null) {
            return result;
        }
        Map<BlockPos, RenderChunk> byOrigin = new HashMap<>();
        for (RenderChunk chunk : frustum.renderChunks) {
            byOrigin.put(chunk.getPosition(), chunk);
        }
        result.checked = true;
        synchronized (global) {
            for (TileEntity tile : new ArrayList<>(mc.world.loadedTileEntityList)) {
                if (tile.isInvalid() || TileEntityRendererDispatcher.instance.getRenderer(tile) == null) {
                    continue;
                }
                if (tile.getDistanceSq(view.posX, view.posY, view.posZ) >= tile.getMaxRenderDistanceSquared()) {
                    continue;
                }
                result.inRange++;
                BlockPos pos = tile.getPos();
                RenderChunk chunk = byOrigin.get(new BlockPos(pos.getX() & ~15, pos.getY() & ~15, pos.getZ() & ~15));
                if (global.contains(tile)
                    || chunk != null && chunk.getCompiledChunk().getTileEntities().contains(tile)) {
                    result.listed++;
                }
                else if (chunk != null) {
                    result.missing.add(pos);
                }
                else {
                    // Outside the renderer's grid: not drawn this frame for any reason this can fix.
                    result.inRange--;
                }
            }
        }
        return result;
    }

    /** Marks the render chunks of {@code missing} to be rebuilt. Client thread. */
    public static void rebuild(Minecraft mc, List<BlockPos> missing) {
        for (BlockPos pos : missing) {
            mc.renderGlobal.markBlockRangeForRenderUpdate(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
