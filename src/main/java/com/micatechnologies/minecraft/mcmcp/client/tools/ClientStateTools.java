package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.util.List;
import java.util.concurrent.Callable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * World and player inspection from the client's point of view.
 *
 * <h2>What "the client's point of view" costs you</h2>
 *
 * A Minecraft client does not have the world. It has the chunks the server has streamed to it and
 * the entities within its tracking range, and nothing else. Every tool here is therefore honest
 * about a limit the server-side equivalents do not have: a block query outside the loaded view
 * distance reports {@code loaded: false} rather than a block, and an entity scan finds only entities
 * the server has told this client about.
 *
 * <p>That limitation is also the feature. Because these tools see exactly what the player sees, they
 * work on any server the player can join — no server-side install, no operator rights — and a model
 * reasoning from them is reasoning from information the player legitimately has.
 */
@SideOnly(Side.CLIENT)
public final class ClientStateTools {

    private ClientStateTools() {
    }

    public static void register() {
        registerPlayerState();
        registerLookingAt();
        registerGetBlock();
        registerNearbyEntities();
        registerInventory();
        registerConnectionInfo();
    }

    /** Throws with a message a model can act on when there is no world loaded. */
    static Minecraft requireInWorld() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) {
            throw new IllegalStateException("The client is not in a world — it is at the main menu or a "
                + "loading screen. Use client_gui_state to see what is on screen.");
        }
        return mc;
    }

    /**
     * A ray trace computed from the player's rotation <em>right now</em>.
     *
     * <p>Necessary because {@code mc.objectMouseOver} is only recomputed by
     * {@code EntityRenderer.getMouseOver} during rendering. A tool that turns the camera inside a
     * scheduled task and then reads {@code objectMouseOver} in the same task gets the target from
     * before the turn — so {@code client_look} would aim correctly at a block and report "miss",
     * which reads as a failed aim and invites a model to correct a rotation that was already right.
     * Observed exactly that against a live client.
     *
     * <p>Blocks only: {@link net.minecraft.entity.Entity#rayTrace} does not consider entities. That is
     * the right trade for the post-action reports this feeds, where the question is "did the camera
     * end up where I asked". {@code client_looking_at} deliberately still reports
     * {@code objectMouseOver}, because that is the target the game will actually act on and it is
     * never stale when read from a standalone call.
     */
    static JsonObject freshLookTarget(Minecraft mc) {
        double reach = mc.playerController == null ? 4.5D : mc.playerController.getBlockReachDistance();
        return GameJson.rayTrace(mc.world, mc.player.rayTrace(reach, 1.0F));
    }

    private static void registerPlayerState() {
        McpRegistry.registerTool(McpTool.named("client_player_state")
            .title("Player state")
            .description("Read the controlled player's full state: position, orientation, health, "
                + "hunger, held item, the block underfoot, the biome, and the world's time and weather. "
                + "This is the usual first call for orienting yourself.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = requireInWorld();
                        WorldClient world = mc.world;

                        JsonObject json = GameJson.player(mc.player);
                        json.add("world", GameJson.world(world));
                        String biome = GameJson.biomeName(world, GameJson.blockPosOf(mc.player));
                        if (biome != null) {
                            json.addProperty("biome", biome);
                        }
                        json.add("standingOn", GameJson.block(world, GameJson.blockPosOf(mc.player).down()));
                        json.add("lookingAt", GameJson.rayTrace(world, mc.objectMouseOver));
                        json.addProperty("renderDistanceChunks", mc.gameSettings.renderDistanceChunks);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    /**
     * What the crosshair is on.
     *
     * <p>Separate from {@code client_player_state} because it is the one query that gets called
     * repeatedly in a loop — look, check, adjust, check again — and paying for a full player-state
     * response each time is wasteful.
     */
    private static void registerLookingAt() {
        McpRegistry.registerTool(McpTool.named("client_looking_at")
            .title("Looking at")
            .description("Report what the player's crosshair is currently pointing at: a block (with "
                + "the face being looked at), an entity, or nothing. This is the same target that "
                + "client_use and client_attack would act on, so check it before acting.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = requireInWorld();
                        JsonObject json = GameJson.rayTrace(mc.world, mc.objectMouseOver);
                        json.addProperty("yaw", Math.round(mc.player.rotationYaw * 100.0D) / 100.0D);
                        json.addProperty("pitch", Math.round(mc.player.rotationPitch * 100.0D) / 100.0D);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerGetBlock() {
        McpRegistry.registerTool(McpTool.named("client_get_block")
            .title("Get block")
            .description("Read the block at a world position as this client sees it. Positions outside "
                + "the loaded view distance report loaded=false — the client genuinely does not know "
                + "what is there, and will not be told until it gets closer.")
            .schema(JsonSchema.object()
                .integer("x", "Block X coordinate.")
                .integer("y", "Block Y coordinate, 0-255.")
                .integer("z", "Block Z coordinate.")
                .bool("relative", "Treat x, y and z as offsets from the player's block position "
                    + "instead of absolute coordinates.")
                .required("x", "y", "z")
                .build())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                final int x = context.requireInt("x");
                final int y = context.requireInt("y");
                final int z = context.requireInt("z");
                final boolean relative = context.getBoolean("relative", false);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = requireInWorld();
                        BlockPos origin = relative ? GameJson.blockPosOf(mc.player) : BlockPos.ORIGIN;
                        BlockPos pos = new BlockPos(origin.getX() + x, origin.getY() + y,
                            origin.getZ() + z);
                        JsonObject json = GameJson.block(mc.world, pos);
                        String biome = GameJson.biomeName(mc.world, pos);
                        if (biome != null) {
                            json.addProperty("biome", biome);
                        }
                        json.addProperty("distanceFromPlayer",
                            Math.round(Math.sqrt(mc.player.getDistanceSq(pos)) * 100.0D) / 100.0D);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerNearbyEntities() {
        McpRegistry.registerTool(McpTool.named("client_nearby_entities")
            .title("Nearby entities")
            .description("List entities the client currently knows about within a radius of the player: "
                + "mobs, other players, dropped items, vehicles. Limited to the client's entity "
                + "tracking range, which is smaller than the server's view.")
            .schema(JsonSchema.object()
                .integer("radius", "Search radius in blocks around the player.", 1, 128)
                .string("type", "Namespaced entity type to filter by, e.g. 'minecraft:zombie'. Omit "
                    + "to return every entity.")
                .build())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                final int radius = context.getBoundedInt("radius", 16, 1, McmcpConfig.getMaxScanRadius());
                final String typeFilter = context.getString("type", null);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = requireInWorld();
                        AxisAlignedBB box = mc.player.getEntityBoundingBox().grow(radius);
                        List<Entity> entities = mc.world.getEntitiesWithinAABB(Entity.class, box);

                        JsonArray array = new JsonArray();
                        for (Entity entity : entities) {
                            if (entity == mc.player) {
                                continue;
                            }
                            JsonObject json = GameJson.entity(entity);
                            if (typeFilter != null && !typeFilter.equals(json.get("type").getAsString())) {
                                continue;
                            }
                            json.addProperty("distance",
                                Math.round(mc.player.getDistance(entity) * 100.0D) / 100.0D);
                            array.add(json);
                        }

                        JsonObject json = new JsonObject();
                        json.addProperty("radius", radius);
                        json.addProperty("count", array.size());
                        json.add("entities", array);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerInventory() {
        McpRegistry.registerTool(McpTool.named("client_inventory")
            .title("Player inventory")
            .description("Read the player's inventory: every occupied slot, the selected hotbar slot, "
                + "and both hands. Slot indices 0-8 are the hotbar; 9-35 are the main inventory.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = requireInWorld();
                        JsonObject json = new JsonObject();
                        json.addProperty("selectedSlot", mc.player.inventory.currentItem);
                        json.add("inventory", GameJson.inventory(mc.player.inventory));
                        json.add("mainHand", GameJson.itemStack(mc.player.getHeldItemMainhand()));
                        json.add("offHand", GameJson.itemStack(mc.player.getHeldItemOffhand()));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    /**
     * Which server the client is attached to, and who else is on it.
     *
     * <p>Worth its own tool because it is the answer to "what am I even connected to" — singleplayer
     * versus a remote server changes what a model should assume about permissions, other players
     * and how reversible its actions are.
     */
    private static void registerConnectionInfo() {
        McpRegistry.registerTool(McpTool.named("client_connection_info")
            .title("Connection info")
            .description("Report what the client is connected to: singleplayer or a remote server, the "
                + "server address, latency, and the player list. Check this before acting — actions on "
                + "a shared server affect other people and are rarely reversible.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = Minecraft.getMinecraft();
                        JsonObject json = new JsonObject();
                        json.addProperty("inWorld", mc.world != null && mc.player != null);
                        json.addProperty("singleplayer", mc.isSingleplayer());

                        if (mc.getCurrentServerData() != null) {
                            json.addProperty("serverAddress", mc.getCurrentServerData().serverIP);
                            json.addProperty("serverName", mc.getCurrentServerData().serverName);
                        }

                        if (mc.getConnection() != null) {
                            JsonArray players = new JsonArray();
                            for (NetworkPlayerInfo info : mc.getConnection().getPlayerInfoMap()) {
                                JsonObject entry = new JsonObject();
                                entry.addProperty("name", info.getGameProfile().getName());
                                entry.addProperty("uuid", info.getGameProfile().getId().toString());
                                entry.addProperty("ping", info.getResponseTime());
                                entry.addProperty("gameMode", String.valueOf(info.getGameType()));
                                players.add(entry);
                            }
                            json.addProperty("onlinePlayers", players.size());
                            json.add("players", players);
                        }
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }
}
