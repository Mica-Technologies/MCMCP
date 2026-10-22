package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.NbtProjection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;

/**
 * {@code server_remove_entities}: clearing what a test left in a box, with nobody near (#86).
 *
 * <p>A signal test summoned villagers into turn bays and could not get rid of them: {@code /kill}
 * and {@code /tp} with selectors found nothing, a villager at 0 health stayed, and the sensors kept
 * counting them until a player flew in. On a server nobody is near, vanilla has stopped ticking
 * entities (see {@link WorldIdle}), and an entity killed or marked dead is only taken out of the world
 * by the entity tick that is not running. So this takes them out at once, with
 * {@code removeEntityDangerously}, which unlinks the entity from its chunk and the world's list and
 * tells the tracker, so clients drop it too. Run from a scheduled task, never during the entity
 * tick, which is what makes it safe here.
 */
public final class ServerEntityTools {

    /** Most chunks one call may cover. */
    private static final int MAX_CHUNKS = 1024;

    /** Most entities one call removes. */
    private static final int MAX_REMOVED = 10_000;

    /** Positions listed in a result. */
    private static final int MAX_LISTED = 20;

    private ServerEntityTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_remove_entities")
            .title("Remove entities in a box")
            .description("Remove every entity of the given types in a block box, with no player near: "
                + "test villagers, dropped items, minecarts. 'types' takes entity ids "
                + "('minecraft:villager', 'item' for dropped items), namespaces ('mymod:') or patterns "
                + "with '*'. Players are never removed, and named entities (a pet, a named villager) are "
                + "kept unless includeNamed is true. Removed outright, with no drops and no death, which "
                + "works where /kill finds nothing because no player is near. dryRun lists what would "
                + "go. load: true loads the box's chunks for the call. Not undoable. Disabled unless "
                + "permissions.allowWorldEdits is enabled in the MCMCP config.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .stringArray("types", "Entity types to remove: ids, 'item', namespaces ending in ':', or "
                    + "patterns with '*'. '*' alone means every type but players.")
                .integer("x", "X of one corner.")
                .integer("y", "Y of one corner.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Defaults to x.")
                .integer("toY", "Y of the opposite corner. Defaults to y.")
                .integer("toZ", "Z of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .bool("includeNamed", "Also remove entities with a custom name. Default false.")
                .bool("dryRun", "List what would be removed, and remove nothing. Default false.")
                .required("types", "x", "y", "z"))
                .build())
            .serverOnly()
            .destructive()
            .handler(context -> {
                String denied = context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
                    "Removing entities is disabled by permissions.allowWorldEdits in the MCMCP config.");
                if (denied != null) {
                    return ToolResult.error(denied);
                }
                final List<String> types = new ArrayList<>();
                for (String type : Json.getStringList(context.getArguments(), "types")) {
                    String t = type.trim().toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) {
                        // A bare name is vanilla's: 'villager', 'item'.
                        types.add(t.indexOf(':') < 0 && t.indexOf('*') < 0 ? "minecraft:" + t : t);
                    }
                }
                if (types.isEmpty()) {
                    return ToolResult.error("Name what to remove in 'types', e.g. ['minecraft:villager', 'item']. "
                        + "'*' removes every type but players.");
                }
                final int x1 = context.requireInt("x");
                final int y1 = context.requireInt("y");
                final int z1 = context.requireInt("z");
                final int x2 = context.getInt("toX", x1);
                final int y2 = context.getInt("toY", y1);
                final int z2 = context.getInt("toZ", z1);
                long chunkCount = ChunkKeys.boxCount(x1, z1, x2, z2);
                if (chunkCount > MAX_CHUNKS) {
                    return ToolResult.error("That box covers " + chunkCount + " chunks; one call covers at most "
                        + MAX_CHUNKS + ". Work in pieces.");
                }
                final int dimension = context.getInt("dimension", 0);
                final boolean includeNamed = context.getBoolean("includeNamed", false);
                final boolean dryRun = context.getBoolean("dryRun", false);
                final Set<Long> chunks = ChunkKeys.box(x1, z1, x2, z2);
                // Whole blocks, both corners included, as every other box here.
                final AxisAlignedBB box = new AxisAlignedBB(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1);

                return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
                    JsonObject json = context.onGameThread(() -> {
                        WorldServer world = ServerWorldTools.requireWorld(dimension);
                        Map<String, Integer> byType = new TreeMap<>();
                        JsonArray at = new JsonArray();
                        int keptNamed = 0;
                        int removed = 0;
                        boolean capped = false;
                        for (Entity entity : world.getEntitiesWithinAABB(Entity.class, box)) {
                            if (entity instanceof EntityPlayer || entity.isDead) {
                                continue;
                            }
                            String type = typeOf(entity);
                            if (!matches(type, types)) {
                                continue;
                            }
                            if (!includeNamed && entity.hasCustomName()) {
                                keptNamed++;
                                continue;
                            }
                            if (removed >= MAX_REMOVED) {
                                capped = true;
                                break;
                            }
                            if (!dryRun) {
                                // Riders first, or they are left floating on nothing.
                                entity.removePassengers();
                                entity.dismountRidingEntity();
                                world.removeEntityDangerously(entity);
                            }
                            removed++;
                            byType.merge(type, 1, Integer::sum);
                            if (at.size() < MAX_LISTED) {
                                JsonObject item = GameJson.blockPos(GameJson.blockPosOf(entity));
                                item.addProperty("type", type);
                                at.add(item);
                            }
                        }
                        JsonObject result = new JsonObject();
                        result.addProperty("dimension", dimension);
                        result.addProperty(dryRun ? "wouldRemove" : "removed", removed);
                        JsonObject counts = new JsonObject();
                        for (Map.Entry<String, Integer> entry : byType.entrySet()) {
                            counts.addProperty(entry.getKey(), entry.getValue());
                        }
                        result.add("byType", counts);
                        if (at.size() > 0) {
                            result.add("at", at);
                        }
                        if (keptNamed > 0) {
                            result.addProperty("keptNamed", keptNamed);
                        }
                        if (capped) {
                            result.addProperty("capped", "Stopped at " + MAX_REMOVED + "; call again for the rest.");
                        }
                        ChunkLoadGovernor.touch(dimension, chunks);
                        result.add("chunks", outcome != null ? outcome.toJson()
                            : ChunkLoadGovernor.describeUnloaded(world, chunks));
                        return result;
                    });
                    return ToolResult.structured(json);
                });
            })
            .build());
    }

    /** The registry id an entity is filtered by: {@code minecraft:item} for a dropped item. */
    static String typeOf(Entity entity) {
        if (entity instanceof EntityItem) {
            return "minecraft:item";
        }
        ResourceLocation key = EntityList.getKey(entity);
        return key == null ? "unknown:" + entity.getClass().getSimpleName().toLowerCase(Locale.ROOT) : key.toString();
    }

    static boolean matches(String type, List<String> patterns) {
        return patterns.contains("*") || NbtProjection.matches(type, patterns);
    }
}
