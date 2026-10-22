package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * A player moved to run one command somewhere else, and put back afterwards (#127).
 *
 * <p>Game thread only, and the move and the restore belong in the same task: no tick runs between
 * them, so the server never processes the player at the borrowed position, nothing is sent to their
 * client, and their chunk membership never changes. A command that reads {@code sender.posX} sees
 * the borrowed position; everything else sees the player where they were.
 *
 * <p>Restoring puts back position, rotation (head too), motion, fall distance, ground state and
 * flying. When the command moved the player itself (a teleport of its own), the restore also tells
 * the client where they are, since the server has then already told it otherwise. When the command
 * took them to another dimension, nothing is restored, and {@link #restore} says so.
 */
final class PlayerAt {

    private final EntityPlayerMP player;

    private final int dimension;

    private final double x;

    private final double y;

    private final double z;

    private final double prevX;

    private final double prevY;

    private final double prevZ;

    private final double lastTickX;

    private final double lastTickY;

    private final double lastTickZ;

    private final float yaw;

    private final float pitch;

    private final float prevYaw;

    private final float prevPitch;

    private final float headYaw;

    private final float prevHeadYaw;

    private final double motionX;

    private final double motionY;

    private final double motionZ;

    private final float fallDistance;

    private final boolean onGround;

    private final boolean flying;

    /** Where the move put them, to tell whether the command moved them again. */
    private double[] placed;

    private PlayerAt(EntityPlayerMP player) {
        this.player = player;
        this.dimension = player.dimension;
        this.x = player.posX;
        this.y = player.posY;
        this.z = player.posZ;
        this.prevX = player.prevPosX;
        this.prevY = player.prevPosY;
        this.prevZ = player.prevPosZ;
        this.lastTickX = player.lastTickPosX;
        this.lastTickY = player.lastTickPosY;
        this.lastTickZ = player.lastTickPosZ;
        this.yaw = player.rotationYaw;
        this.pitch = player.rotationPitch;
        this.prevYaw = player.prevRotationYaw;
        this.prevPitch = player.prevRotationPitch;
        this.headYaw = player.rotationYawHead;
        this.prevHeadYaw = player.prevRotationYawHead;
        this.motionX = player.motionX;
        this.motionY = player.motionY;
        this.motionZ = player.motionZ;
        this.fallDistance = player.fallDistance;
        this.onGround = player.onGround;
        this.flying = player.capabilities.isFlying;
    }

    /**
     * Moves {@code player} to {@code at}, keeping their rotation where {@code at} gives none.
     *
     * @param at {@code {x, y, z, yaw, pitch}} as {@link CommandBatch#parseAt} reads it
     */
    static PlayerAt move(EntityPlayerMP player, double[] at) {
        PlayerAt saved = new PlayerAt(player);
        float yaw = Double.isNaN(at[3]) ? player.rotationYaw : (float) at[3];
        float pitch = Double.isNaN(at[4]) ? player.rotationPitch : (float) at[4];
        player.setPositionAndRotation(at[0], at[1], at[2], yaw, pitch);
        player.lastTickPosX = player.posX;
        player.lastTickPosY = player.posY;
        player.lastTickPosZ = player.posZ;
        player.rotationYawHead = yaw;
        player.prevRotationYawHead = yaw;
        player.motionX = 0;
        player.motionY = 0;
        player.motionZ = 0;
        player.fallDistance = 0;
        saved.placed = new double[] {player.posX, player.posY, player.posZ};
        return saved;
    }

    /**
     * Puts the player back where {@link #move} found them.
     *
     * @return null when they are back where they were and their client never knew; otherwise what
     *     happened instead, for the command's result
     */
    String restore() {
        if (player.dimension != dimension || player.isDead) {
            return "the command took the player to another dimension or removed them, so they were left there";
        }
        boolean movedByCommand = placed != null
            && (player.posX != placed[0] || player.posY != placed[1] || player.posZ != placed[2]);
        player.setPositionAndRotation(x, y, z, yaw, pitch);
        player.prevPosX = prevX;
        player.prevPosY = prevY;
        player.prevPosZ = prevZ;
        player.lastTickPosX = lastTickX;
        player.lastTickPosY = lastTickY;
        player.lastTickPosZ = lastTickZ;
        player.prevRotationYaw = prevYaw;
        player.prevRotationPitch = prevPitch;
        player.rotationYawHead = headYaw;
        player.prevRotationYawHead = prevHeadYaw;
        player.motionX = motionX;
        player.motionY = motionY;
        player.motionZ = motionZ;
        player.fallDistance = fallDistance;
        player.onGround = onGround;
        if (player.capabilities.isFlying != flying) {
            player.capabilities.isFlying = flying;
            player.sendPlayerAbilities();
        }
        if (movedByCommand && player.connection != null) {
            // The command's own teleport already told the client it was elsewhere.
            player.connection.setPlayerLocation(x, y, z, yaw, pitch);
            return "the command moved the player itself; they were put back and their client told";
        }
        return null;
    }

    /** Where the player was, {@code {x, y, z, yaw, pitch}}, to two decimals. */
    JsonObject movedFrom() {
        JsonObject json = new JsonObject();
        json.addProperty("x", round(x));
        json.addProperty("y", round(y));
        json.addProperty("z", round(z));
        json.addProperty("yaw", round(yaw));
        json.addProperty("pitch", round(pitch));
        return json;
    }

    private static double round(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
