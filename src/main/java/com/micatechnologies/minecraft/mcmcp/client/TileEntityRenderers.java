package com.micatechnologies.minecraft.mcmcp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Which special renderer, if any, draws a tile entity — for the registry dump.
 *
 * <p>Answers "which of this mod's blocks are drawn by a TESR, and by what" from the registries
 * alone. Without it the only way to learn that was to place every tile-entity block and profile it,
 * which for one audit meant 510 blocks placed in batches to discover that 146 of them had no
 * renderer at all.
 *
 * <p>Asked of a tile entity made fresh for the purpose, not one in a world, so anything a renderer
 * or tile entity decides from its world or neighbours is not reflected. Each question is asked
 * separately and a failure drops only that field: mod code written for a placed tile entity may
 * throw on one that has no world.
 */
@SideOnly(Side.CLIENT)
public final class TileEntityRenderers {

    private TileEntityRenderers() {
    }

    /** Client thread only: the dispatcher's lookup caches by writing to its map. */
    public static JsonObject describe(TileEntity tileEntity) {
        JsonObject json = new JsonObject();
        TileEntitySpecialRenderer<TileEntity> renderer;
        try {
            renderer = TileEntityRendererDispatcher.instance.getRenderer(tileEntity);
        } catch (RuntimeException e) {
            json.addProperty("renderer", "error: " + e.getClass().getSimpleName());
            return json;
        }
        // A string rather than null: the dump is written with a Gson that drops null members, and a
        // missing key reads as "not checked" where this means "checked, and there is none".
        json.addProperty("renderer", renderer == null ? "none" : renderer.getClass().getName());
        if (renderer == null) {
            return json;
        }
        try {
            json.addProperty("globalRenderer", renderer.isGlobalRenderer(tileEntity));
        } catch (RuntimeException ignored) {
            // Depends on world state the fresh tile entity does not have.
        }
        try {
            json.addProperty("maxRenderDistance",
                Math.round(Math.sqrt(tileEntity.getMaxRenderDistanceSquared())));
        } catch (RuntimeException ignored) {
            // As above.
        }
        return json;
    }
}
