package com.micatechnologies.minecraft.mcmcp.client;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.client.companion.ClientCompanion;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import org.lwjgl.opengl.Display;

/**
 * Takes the player to a far camera and back, for {@code client_screenshot bringPlayer} (#78).
 *
 * <p>A detached camera draws only the chunks this client holds, within its render distance of the
 * player, so a photo of a build far away used to need a teleport, a wait for chunks, the photo and a
 * teleport back: four calls, dozens of times a build. This does the moves around one capture.
 *
 * <p>The move is the server's own teleport: the integrated server's in singleplayer, and
 * {@code server_teleport_player} over the companion on a remote server, which needs the player's
 * companion write access there. The player hovers at the camera, so this needs a game mode that can
 * fly; a survival player put in the air is kicked for flying. It is refused while the game window has
 * focus and the agent has not locked input, since then a person may be the one playing.
 */
public final class PlayerShuttle {

    /** Where the player was, to put them back exactly. */
    public static final class Trip {
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final boolean flying;

        Trip(double x, double y, double z, float yaw, float pitch, boolean flying) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.flying = flying;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("x", Math.round(x * 100.0D) / 100.0D);
            json.addProperty("y", Math.round(y * 100.0D) / 100.0D);
            json.addProperty("z", Math.round(z * 100.0D) / 100.0D);
            return json;
        }
    }

    private PlayerShuttle() {
    }

    /**
     * Moves the player to {@code to}, hovering, and waits until this client has them there and holds
     * the chunk under them.
     *
     * @throws IllegalStateException with why it could not, for the caller to report
     */
    public static Trip bring(ToolContext context, double[] to) throws Exception {
        // Decided on the game thread, thrown here: an exception from inside onGameThread comes back as
        // a protocol failure, not as the sentence the agent needs.
        Object checked = context.onGameThread(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || mc.world == null) {
                return "No player is in a world.";
            }
            if (!mc.player.capabilities.allowFlying) {
                return "bringPlayer needs creative or spectator mode, so the player can hover at the camera: a "
                    + "survival player put in the air is kicked for flying.";
            }
            if (Display.isActive() && !ClientInputLock.isLocked()) {
                return "The game window has focus, so a person may be playing: bringPlayer moves the player only "
                    + "while the agent holds client_input_lock, or the window is in the background.";
            }
            return new Trip(mc.player.posX, mc.player.posY, mc.player.posZ, mc.player.rotationYaw,
                mc.player.rotationPitch, mc.player.capabilities.isFlying);
        });
        if (checked instanceof String) {
            throw new IllegalStateException((String) checked);
        }
        Trip trip = (Trip) checked;
        teleport(context, to[0], to[1], to[2], trip.yaw, trip.pitch, true);
        waitUntilAt(context, to[0], to[1], to[2]);
        return trip;
    }

    /** Puts the player back where {@link #bring} found them, facing and flying as they were. */
    public static void back(ToolContext context, Trip trip) throws Exception {
        teleport(context, trip.x, trip.y, trip.z, trip.yaw, trip.pitch, trip.flying);
        waitUntilAt(context, trip.x, trip.y, trip.z);
    }

    private static void teleport(ToolContext context, double x, double y, double z, float yaw, float pitch,
                                 boolean fly) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        IntegratedServer server = mc.getIntegratedServer();
        if (server != null) {
            final java.util.UUID id = mc.player.getUniqueID();
            CompletableFuture<Void> done = new CompletableFuture<>();
            server.addScheduledTask(() -> {
                try {
                    EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(id);
                    if (player == null) {
                        throw new IllegalStateException("The player is not on the integrated server.");
                    }
                    player.capabilities.isFlying = fly && player.capabilities.allowFlying;
                    player.sendPlayerAbilities();
                    player.connection.setPlayerLocation(x, y, z, yaw, pitch);
                    done.complete(null);
                }
                catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
            done.get(5L, TimeUnit.SECONDS);
            return;
        }
        JsonObject args = new JsonObject();
        args.addProperty("player", mc.getSession().getUsername());
        args.addProperty("x", x);
        args.addProperty("y", y);
        args.addProperty("z", z);
        args.addProperty("yaw", yaw);
        args.addProperty("pitch", pitch);
        args.addProperty("fly", fly);
        JsonObject result = ClientCompanion.callServerTool("server_teleport_player", args, 15_000L);
        if (result.has("isError") && result.get("isError").getAsBoolean()) {
            throw new IllegalStateException("The server refused the teleport: " + ClientCompanion.textOf(result));
        }
    }

    /** Waits up to ten seconds for this client to have the player at a spot and its chunk loaded. */
    private static void waitUntilAt(ToolContext context, double x, double y, double z) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            boolean there = context.<Boolean>onGameThread(() -> {
                Minecraft mc = Minecraft.getMinecraft();
                return mc.player != null && mc.world != null
                    && mc.player.getDistanceSq(x, y, z) < 4.0D
                    && !mc.world.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4).isEmpty();
            });
            if (there) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("The player did not arrive at " + Math.round(x) + " " + Math.round(y) + " "
            + Math.round(z) + " within ten seconds.");
    }
}
