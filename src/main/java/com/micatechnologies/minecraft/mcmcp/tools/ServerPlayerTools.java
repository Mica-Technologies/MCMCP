package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * Player inspection and movement on the server endpoint.
 *
 * <p>Every tool that names a player resolves it by username through the player list, so an offline
 * or misspelled name produces a tool error naming who <em>is</em> online rather than a null
 * dereference. That listing is not padding: a model working from a stale plan needs to see that the
 * player it wanted left, and the fastest way to tell it is in the same response.
 */
public final class ServerPlayerTools {

    private ServerPlayerTools() {
    }

    public static void register() {
        registerListPlayers();
        registerPlayerState();
        registerPlayerInventory();
        registerTeleportPlayer();
    }

    /**
     * Resolves a username to a connected player.
     *
     * @throws IllegalStateException with the online roster in the message when no such player is
     *                               connected
     */
    private static EntityPlayerMP requirePlayer(String username) {
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null) {
            throw new IllegalStateException("No Minecraft server is running");
        }
        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(username);
        if (player != null) {
            return player;
        }

        StringBuilder online = new StringBuilder();
        for (EntityPlayerMP candidate : server.getPlayerList().getPlayers()) {
            if (online.length() > 0) {
                online.append(", ");
            }
            online.append(candidate.getName());
        }
        throw new IllegalStateException("No player named '" + username + "' is connected. Online: "
            + (online.length() == 0 ? "(nobody)" : online.toString()));
    }

    private static void registerListPlayers() {
        McpRegistry.registerTool(McpTool.named("server_list_players")
            .title("List players")
            .description("List every connected player with position, dimension, health and ping. Start "
                + "here when you need to act on a player — every other player tool takes a username "
                + "from this list.")
            .schema(JsonSchema.noArguments())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        if (server == null) {
                            throw new IllegalStateException("No Minecraft server is running");
                        }
                        JsonArray players = new JsonArray();
                        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                            JsonObject json = GameJson.player(player);
                            json.addProperty("ping", player.ping);
                            json.addProperty("operator",
                                server.getPlayerList().canSendCommands(player.getGameProfile()));
                            players.add(json);
                        }
                        JsonObject json = new JsonObject();
                        json.addProperty("count", players.size());
                        json.add("players", players);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerPlayerState() {
        McpRegistry.registerTool(McpTool.named("server_player_state")
            .title("Player state")
            .description("Read one player's full state: position, orientation, health, hunger, "
                + "experience, held item, and the block and biome they are standing in.")
            .schema(JsonSchema.object()
                .string("player", "Username of a connected player, as returned by server_list_players.")
                .required("player")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                final String username = context.requireString("player");
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        EntityPlayerMP player = requirePlayer(username);
                        JsonObject json = GameJson.player(player);
                        WorldServer world = (WorldServer) player.world;
                        json.add("world", GameJson.world(world));
                        String biome = GameJson.biomeName(world, GameJson.blockPosOf(player));
                        if (biome != null) {
                            json.addProperty("biome", biome);
                        }
                        json.add("standingOn",
                            GameJson.block(world, GameJson.blockPosOf(player).down()));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerPlayerInventory() {
        McpRegistry.registerTool(McpTool.named("server_player_inventory")
            .title("Player inventory")
            .description("Read a player's inventory: main inventory, hotbar, armour and offhand. Empty "
                + "slots are omitted from the item lists and reported as a count instead.")
            .schema(JsonSchema.object()
                .string("player", "Username of a connected player.")
                .required("player")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                final String username = context.requireString("player");
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        EntityPlayerMP player = requirePlayer(username);
                        JsonObject json = new JsonObject();
                        json.addProperty("player", player.getName());
                        json.addProperty("selectedSlot", player.inventory.currentItem);
                        json.add("inventory", GameJson.inventory(player.inventory));
                        json.add("mainHand", GameJson.itemStack(player.getHeldItemMainhand()));
                        json.add("offHand", GameJson.itemStack(player.getHeldItemOffhand()));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    /** How long server_teleport_player waits for the client to accept a teleport. */
    private static final long TELEPORT_CONFIRM_TIMEOUT_MILLIS = 2000L;

    /** NetHandlerPlayServer's pending-teleport target, found by type; see {@link #isTeleportPending}. */
    private static volatile Field teleportTargetField;
    private static volatile boolean teleportTargetFieldSearched;

    /**
     * Whether the server is still waiting for {@code player}'s client to accept a teleport, or null
     * if that cannot be told.
     *
     * <p>The server holds the destination in a private {@code Vec3d} until the client confirms, and
     * ignores the player's movement packets meanwhile. It is found by type rather than name, for the
     * reason the tile entity registry is in RegistryDumpTools: a name literal is rewritten by the
     * reobfuscator and then fails in a development client. It is the handler's only {@code Vec3d}
     * field in 1.12.2; if that stops being true this reports null and the tool stops waiting.
     */
    @Nullable
    static Boolean isTeleportPending(EntityPlayerMP player) {
        if (!teleportTargetFieldSearched) {
            Field found = null;
            for (Field field : NetHandlerPlayServer.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == Vec3d.class) {
                    if (found != null) {
                        found = null;
                        break;
                    }
                    found = field;
                }
            }
            if (found != null) {
                found.setAccessible(true);
            }
            teleportTargetField = found;
            teleportTargetFieldSearched = true;
        }
        Field field = teleportTargetField;
        if (field == null || player.connection == null) {
            return null;
        }
        try {
            return field.get(player.connection) != null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Teleports a player.
     *
     * <p>Gated behind {@code permissions.allowWorldEdits} rather than {@code allowPlayerControl}: the
     * latter governs driving the local player's own input on the client endpoint, which is bounded by
     * what that player could do themselves. Moving <em>another</em> player from the server is a
     * different thing entirely, and belongs with the other authoritative-write permissions.
     */
    private static void registerTeleportPlayer() {
        McpRegistry.registerTool(McpTool.named("server_teleport_player")
            .title("Teleport player")
            .description("Move a player to a position, optionally facing a given direction. Returns "
                + "once the player's client has accepted the teleport, so a client_look or "
                + "screenshot made next sees the new position and facing; 'confirmed' is false if it "
                + "did not within two seconds. Prefer this to /tp through server_run_command for "
                + "setting a camera.\n\n"
                + "A player in the air falls unless flying: set fly=true to hold a pose (creative or "
                + "spectator only). Disabled unless permissions.allowWorldEdits is enabled in the "
                + "MCMCP config.")
            .schema(JsonSchema.object()
                .string("player", "Username of a connected player.")
                .number("x", "Destination X coordinate.")
                .number("y", "Destination Y coordinate.")
                .number("z", "Destination Z coordinate.")
                .number("yaw", "Facing, in degrees clockwise from south. Defaults to unchanged.",
                    -360.0D, 360.0D)
                .number("pitch", "Facing, in degrees down from horizontal. Defaults to unchanged.",
                    -90.0D, 90.0D)
                .bool("fly", "Start flying, so the player stays at y instead of falling. true "
                    + "needs a game mode that allows flight; false lands them. Defaults to "
                    + "unchanged.")
                .required("player", "x", "y", "z")
                .build())
            .serverOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowWorldEdits()) {
                    return ToolResult.error("Teleporting players is disabled by "
                        + "permissions.allowWorldEdits in the MCMCP config.");
                }

                final String username = context.requireString("player");
                final double x = context.requireDouble("x");
                final double y = context.requireDouble("y");
                final double z = context.requireDouble("z");
                final boolean changeFlying = context.has("fly");
                final boolean fly = context.getBoolean("fly", false);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        EntityPlayerMP player = requirePlayer(username);
                        float yaw = (float) context.getDouble("yaw", player.rotationYaw);
                        float pitch = (float) context.getDouble("pitch", player.rotationPitch);
                        JsonObject before = GameJson.vec(player.posX, player.posY, player.posZ);

                        JsonObject json = new JsonObject();
                        if (changeFlying) {
                            // Before the move, so the player is already flying when they arrive
                            // and does not start to fall in the tick between.
                            if (fly && !player.capabilities.allowFlying) {
                                json.addProperty("flying", false);
                                json.addProperty("flyingRefused", "This player's game mode does not "
                                    + "allow flight, so they will fall. Use creative or spectator.");
                            } else {
                                player.capabilities.isFlying = fly;
                                player.sendPlayerAbilities();
                                json.addProperty("flying", fly);
                            }
                        }

                        // Through the connection rather than setPosition: this sends the position
                        // packet the client needs to actually move. A bare setPosition desyncs the
                        // player, who then gets rubber-banded back by the movement check.
                        player.connection.setPlayerLocation(x, y, z, yaw, pitch);

                        json.addProperty("player", player.getName());
                        json.add("from", before);
                        json.add("to", GameJson.vec(x, y, z));
                        json.addProperty("yaw", yaw);
                        json.addProperty("pitch", pitch);
                        return json;
                    }
                });

                // Wait for the client to accept it. The teleport is a packet; until the client has
                // applied it, the client still has the old position and rotation, and a client_look
                // made in that window was overwritten by the teleport landing a moment later — the
                // guard then reported that the camera had not stayed put. On the worker, a tick at
                // a time: waiting on the game thread would stop the tick that processes the reply.
                boolean confirmed = false;
                long deadline = System.currentTimeMillis() + TELEPORT_CONFIRM_TIMEOUT_MILLIS;
                while (System.currentTimeMillis() < deadline) {
                    Boolean pending = context.onGameThread(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            return isTeleportPending(requirePlayer(username));
                        }
                    });
                    if (pending == null) {
                        break;
                    }
                    if (!pending) {
                        confirmed = true;
                        break;
                    }
                    PerformanceTools.sleepCancellable(context, 25L);
                }
                result.addProperty("confirmed", confirmed);
                return ToolResult.structured(result);
            })
            .build());
    }
}
