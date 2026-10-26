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

        /** This trip's turn with the player's lease, to give back after the return. */
        public volatile Turn turn = Turn.NONE;

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

    /**
     * A trip's turn with the shared player's lease (#143): taken before the move, so another agent's
     * linking or photo is never moved out from under them, and given back after the return.
     */
    public static final class Turn {
        static final Turn NONE = new Turn(false, 0L, null);

        /** Whether this trip took the lease, and so gives it back; false when the agent already held it. */
        final boolean taken;
        public final long waitedMillis;
        /** Who held the player while this trip waited, or null. */
        public final String waitedFor;

        Turn(boolean taken, long waitedMillis, String waitedFor) {
            this.taken = taken;
            this.waitedMillis = waitedMillis;
            this.waitedFor = waitedFor;
        }
    }

    private static final String LEASE_PURPOSE = "bringPlayer: a photo far away";
    private static final long LEASE_MILLIS = 300_000L;

    /** Attempts at the return teleport before the player is reported stranded (#129). */
    private static final int RETURN_ATTEMPTS = 5;

    /**
     * A return that failed: where the player should have gone, and where they were left. A later
     * {@link #bring} that finds the player still where they were left goes back from here, not from the
     * camera, so one failed return cannot become every later shot's "home" (#129).
     */
    private static volatile Trip stranded;
    private static volatile double[] strandedAt;

    private PlayerShuttle() {
    }

    /**
     * Moves the player to {@code to}, hovering, and waits until this client has them there and holds
     * the chunk under them.
     *
     * @throws IllegalStateException with why it could not, for the caller to report
     */
    public static Trip bring(ToolContext context, double[] to, long waitMillis) throws Exception {
        String player = Minecraft.getMinecraft().getSession().getUsername();
        // The turn first, then where the player is: another agent's trip may still be bringing them back.
        Turn turn = takeTurn(context, player, waitMillis);
        Trip trip;
        try {
            trip = start(context, to);
        }
        catch (Exception e) {
            giveTurnBack(context, player, turn);
            throw e;
        }
        trip.turn = turn;
        return trip;
    }

    private static Trip start(ToolContext context, double[] to) throws Exception {
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
            Trip owed = stranded;
            double[] leftAt = strandedAt;
            if (owed != null && leftAt != null
                && mc.player.getDistanceSq(leftAt[0], leftAt[1], leftAt[2]) < 4.0D) {
                return owed;
            }
            // Moved since: whoever moved them chose the new spot, so it is the one to come back to.
            stranded = null;
            strandedAt = null;
            return new Trip(mc.player.posX, mc.player.posY, mc.player.posZ, mc.player.rotationYaw,
                mc.player.rotationPitch, mc.player.capabilities.isFlying);
        });
        if (checked instanceof String) {
            throw new IllegalStateException((String) checked);
        }
        Trip trip = (Trip) checked;
        teleport(context, to[0], to[1], to[2], trip.yaw, trip.pitch, true);
        try {
            waitUntilAt(context, to[0], to[1], to[2]);
        }
        catch (IllegalStateException e) {
            // The teleport was accepted, so the player may be anywhere between: put them back first.
            back(context, trip, to);
            throw e;
        }
        return trip;
    }

    /**
     * Puts the player back where {@link #bring} found them, facing and flying as they were.
     *
     * <p>Retried with backoff: the return is the move that matters most, and on a remote server it can
     * be refused for a reason that passes in seconds, such as another session's calls filling
     * {@code companion.maxConcurrentCalls} (#129).
     *
     * @param from where the player is being brought back from, to recognise them there next call
     * @throws IllegalStateException when every attempt failed; the player is left at {@code from}
     */
    public static void back(ToolContext context, Trip trip, double[] from) throws Exception {
        try {
            returnPlayer(context, trip, from);
        }
        finally {
            giveTurnBack(context, Minecraft.getMinecraft().getSession().getUsername(), trip.turn);
        }
    }

    private static void returnPlayer(ToolContext context, Trip trip, double[] from) throws Exception {
        Exception last = null;
        long pause = 500L;
        for (int attempt = 0; attempt < RETURN_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                Thread.sleep(pause);
                pause *= 2L;
            }
            try {
                teleport(context, trip.x, trip.y, trip.z, trip.yaw, trip.pitch, trip.flying);
                waitUntilAt(context, trip.x, trip.y, trip.z);
                stranded = null;
                strandedAt = null;
                return;
            }
            catch (InterruptedException e) {
                throw e;
            }
            catch (Exception e) {
                last = e;
            }
        }
        stranded = trip;
        strandedAt = from.clone();
        throw new IllegalStateException("The player was NOT returned and is still at the camera, " + Math.round(from[0])
            + " " + Math.round(from[1]) + " " + Math.round(from[2]) + ": " + last.getMessage() + " They belong at "
            + Math.round(trip.x) + " " + Math.round(trip.y) + " " + Math.round(trip.z) + "; the next bringPlayer "
            + "call returns them there if they have not been moved meanwhile.");
    }

    /**
     * Takes the player's lease for a trip, waiting in line up to {@code waitMillis} while another agent
     * holds it. In singleplayer the ledger is in this JVM; on a remote server it is asked over the
     * companion, once a second, so no companion call is held open through the wait. A server that
     * cannot lease (an older MCMCP, or no 'use' access) is not waited on: its teleport still refuses
     * a player another agent leases.
     *
     * @throws IllegalStateException when the wait ran out
     */
    private static Turn takeTurn(ToolContext context, String player, long waitMillis) throws Exception {
        long start = System.currentTimeMillis();
        String key = context.callerKey();
        boolean local = Minecraft.getMinecraft().getIntegratedServer() != null;
        if (local ? com.micatechnologies.minecraft.mcmcp.mcp.PlayerLeases.get().holds(player, key, start)
            : holdsRemotely(context, player)) {
            return Turn.NONE;
        }
        String waitedFor = null;
        while (true) {
            long now = System.currentTimeMillis();
            String holder;
            if (local) {
                com.micatechnologies.minecraft.mcmcp.mcp.PlayerLeases.Ask ask = com.micatechnologies.minecraft.mcmcp.mcp
                    .PlayerLeases.get().acquire(player, key, context.callerLabel(), LEASE_PURPOSE,
                        java.util.Collections.emptyMap(), LEASE_MILLIS, now);
                if (ask.granted != null) {
                    return new Turn(true, now - start, waitedFor);
                }
                holder = ask.heldBy == null ? "an agent ahead in line" : ask.heldBy.holderLabel;
            }
            else {
                JsonObject args = new JsonObject();
                args.addProperty("player", player);
                args.addProperty("purpose", LEASE_PURPOSE);
                args.addProperty("leaseSeconds", (int) (LEASE_MILLIS / 1000L));
                JsonObject result = ClientCompanion.callServerTool("server_player_lease", args, 15_000L,
                    context.getCaller().stamp());
                if (!isError(result)) {
                    return new Turn(true, now - start, waitedFor);
                }
                JsonObject structured = com.micatechnologies.minecraft.mcmcp.json.Json.getObject(result, "structuredContent");
                JsonObject busy = structured == null ? null : com.micatechnologies.minecraft.mcmcp.json.Json.getObject(structured, "busy");
                if (busy == null) {
                    return Turn.NONE;
                }
                holder = com.micatechnologies.minecraft.mcmcp.json.Json.getString(busy, "holder", "an agent ahead in line");
            }
            waitedFor = holder;
            if (now - start >= waitMillis) {
                if (local) {
                    com.micatechnologies.minecraft.mcmcp.mcp.PlayerLeases.get().stopWaiting(player, key);
                }
                throw new IllegalStateException("The player is leased by " + holder + ", and was not free after "
                    + (waitMillis / 1000L) + " s of waiting (bringWaitSeconds). Try again, or wait longer.");
            }
            context.awaitBudget(1000L, "waiting for " + holder + " to finish with " + player);
        }
    }

    private static boolean holdsRemotely(ToolContext context, String player) {
        JsonObject args = new JsonObject();
        args.addProperty("op", "status");
        args.addProperty("player", player);
        try {
            JsonObject result = ClientCompanion.callServerTool("server_player_lease", args, 15_000L,
                context.getCaller().stamp());
            JsonObject structured = com.micatechnologies.minecraft.mcmcp.json.Json.getObject(result, "structuredContent");
            return !isError(result) && structured != null
                && com.micatechnologies.minecraft.mcmcp.json.Json.getBoolean(structured, "yours", false);
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /** Gives a trip's turn back. Never throws: a turn not given back runs out on its own. */
    private static void giveTurnBack(ToolContext context, String player, Turn turn) {
        if (!turn.taken) {
            return;
        }
        try {
            if (Minecraft.getMinecraft().getIntegratedServer() != null) {
                com.micatechnologies.minecraft.mcmcp.mcp.PlayerLeases.get().release(player, context.callerKey(), false);
                return;
            }
            JsonObject args = new JsonObject();
            args.addProperty("op", "release");
            args.addProperty("player", player);
            ClientCompanion.callServerTool("server_player_lease", args, 15_000L, context.getCaller().stamp());
        }
        catch (RuntimeException e) {
            // Left to run out.
        }
    }

    private static boolean isError(JsonObject result) {
        return result.has("isError") && result.get("isError").getAsBoolean();
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
        JsonObject result = ClientCompanion.callServerTool("server_teleport_player", args, 15_000L,
            context.getCaller().stamp());
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
