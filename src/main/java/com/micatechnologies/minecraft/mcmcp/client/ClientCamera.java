package com.micatechnologies.minecraft.mcmcp.client;

import java.lang.reflect.Field;
import java.util.Collections;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Renders the game from somewhere other than the player's eyes, or brighter than the world is,
 * for one capture, then puts everything back.
 *
 * <h2>Why</h2>
 *
 * Every survey view used to mean teleporting the player, which other players on a shared server
 * see, then waiting for chunks and aiming. At night every frame was near-black, and changing the
 * world's time on a shared server is not an option (#40). Both are client-side here: the camera is
 * a stand-in entity set as the render view, and full-bright is the gamma the lightmap reads. Nothing
 * reaches the server.
 *
 * <h2>Putting it back</h2>
 *
 * Everything changed is saved first and restored in the same client-thread task that reads the
 * frame, so no frame is rendered between. If the worker driving the capture dies first, the tick
 * handler restores it anyway after {@link #MAX_HOLD_MILLIS}: a camera left detached would leave the
 * player looking at a fixed point with no idea why, and a gamma left at full bright would be saved
 * into their options the next time anything writes them.
 *
 * <p>The render view is written to the field directly. {@code Minecraft.setRenderViewEntity} also
 * reloads the entity renderer's shader, which tears down any shader another mod has active.
 */
@SideOnly(Side.CLIENT)
public final class ClientCamera {

    /** Longest an override may stand before the tick handler restores it on its own. */
    private static final long MAX_HOLD_MILLIS = 60_000L;

    /** Gamma the lightmap clamps to full brightness at every light level. */
    private static final float FULLBRIGHT_GAMMA = 16.0F;

    @Nullable
    private static final Field RENDER_VIEW_ENTITY = findRenderViewField();

    private static boolean registered;

    /** The settings in force before the override, or null when none is active. Client thread only. */
    @Nullable
    private static Saved saved;

    private ClientCamera() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ClientCamera.Events());
        registered = true;
    }

    /** Whether a detached camera can be used at all in this game. */
    public static boolean canDetach() {
        return RENDER_VIEW_ENTITY != null;
    }

    /**
     * Applies an override. Client thread only. Restores any earlier one first.
     *
     * @param position   eye position for a detached camera, or null to keep the player's view
     * @param fov        field of view in degrees, or NaN to keep the player's
     * @param fullbright whether to light everything fully
     */
    public static void apply(@Nullable double[] position, float yaw, float pitch, float fov,
                             boolean fullbright) {
        restore();
        Minecraft mc = Minecraft.getMinecraft();
        Saved previous = new Saved();
        previous.viewEntity = mc.getRenderViewEntity();
        previous.gamma = mc.gameSettings.gammaSetting;
        previous.fov = mc.gameSettings.fovSetting;
        previous.thirdPersonView = mc.gameSettings.thirdPersonView;
        previous.hideGui = mc.gameSettings.hideGUI;
        previous.appliedAt = System.currentTimeMillis();
        saved = previous;

        if (position != null) {
            Camera camera = new Camera(mc.world);
            camera.setLocationAndAngles(position[0], position[1], position[2], yaw, pitch);
            camera.prevPosX = camera.lastTickPosX = position[0];
            camera.prevPosY = camera.lastTickPosY = position[1];
            camera.prevPosZ = camera.lastTickPosZ = position[2];
            camera.prevRotationYaw = camera.rotationYawHead = camera.prevRotationYawHead = yaw;
            camera.prevRotationPitch = pitch;
            setRenderView(camera);
            // A view from somewhere else is a picture of the world, not of the player's screen:
            // their hand and HUD do not belong in it, and third person would put the camera
            // behind the stand-in rather than at the point asked for.
            mc.gameSettings.thirdPersonView = 0;
            mc.gameSettings.hideGUI = true;
        }
        if (!Float.isNaN(fov)) {
            mc.gameSettings.fovSetting = fov;
        }
        if (fullbright) {
            mc.gameSettings.gammaSetting = FULLBRIGHT_GAMMA;
        }
    }

    /** Puts back whatever {@link #apply} changed. Client thread only; harmless when nothing is applied. */
    public static void restore() {
        Saved previous = saved;
        if (previous == null) {
            return;
        }
        saved = null;
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.gammaSetting = previous.gamma;
        mc.gameSettings.fovSetting = previous.fov;
        mc.gameSettings.thirdPersonView = previous.thirdPersonView;
        mc.gameSettings.hideGUI = previous.hideGui;
        Entity view = previous.viewEntity;
        // The player may have died, respawned or changed dimension since: a stale player entity as
        // the render view would freeze the camera on a corpse.
        if (view == null || view.isDead || view.world != mc.world) {
            view = mc.player;
        }
        setRenderView(view);
    }

    public static boolean isApplied() {
        return saved != null;
    }

    private static void setRenderView(@Nullable Entity entity) {
        if (RENDER_VIEW_ENTITY == null || entity == null) {
            return;
        }
        try {
            RENDER_VIEW_ENTITY.set(Minecraft.getMinecraft(), entity);
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set the render view entity", e);
        }
    }

    @Nullable
    private static Field findRenderViewField() {
        try {
            return ObfuscationReflectionHelper.findField(Minecraft.class, "field_175622_Z");
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Saved {
        Entity viewEntity;
        float gamma;
        float fov;
        int thirdPersonView;
        boolean hideGui;
        long appliedAt;
    }

    /**
     * The stand-in the world is rendered from. Never spawned: it is not in the world's entity list,
     * so nothing ticks it, collides with it or sends it to the server.
     */
    private static final class Camera extends EntityLivingBase {

        Camera(World world) {
            super(world);
            setInvisible(true);
        }

        /** Zero, so the camera sits exactly at the position asked for rather than above it. */
        @Override
        public float getEyeHeight() {
            return 0.0F;
        }

        @Override
        public Iterable<ItemStack> getArmorInventoryList() {
            return Collections.emptyList();
        }

        @Override
        public ItemStack getItemStackFromSlot(EntityEquipmentSlot slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItemStackToSlot(EntityEquipmentSlot slot, ItemStack stack) {
        }

        @Override
        public EnumHandSide getPrimaryHand() {
            return EnumHandSide.RIGHT;
        }
    }

    /** Nested so a second register() cannot put the statics on the bus twice. */
    public static class Events {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || saved == null) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world == null || System.currentTimeMillis() - saved.appliedAt > MAX_HOLD_MILLIS) {
                restore();
            }
        }
    }
}
