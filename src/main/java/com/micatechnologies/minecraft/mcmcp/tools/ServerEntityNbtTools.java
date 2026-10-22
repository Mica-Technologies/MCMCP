package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.NbtProjection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;

/**
 * {@code server_get_entity_nbt} and {@code server_set_entity_nbt}: reading and merging an entity's
 * NBT, server side (#126).
 *
 * <p>A mod entity keeps its state in NBT that nothing else in MCMCP reaches: an Immersive Vehicles
 * vehicle's parts and fuel, for one. {@code /entitydata} merges into it, but it takes a selector
 * that finds nothing with no player near, caps a command at its length, and says only "changed".
 * This is the same merge — {@code writeToNBT}, merge, {@code readFromNBT}, as the command does —
 * with the entity found by UUID or by nearest position, and the result says, key by key, what the
 * entity held before and what it holds after, read back from the entity rather than from the patch.
 *
 * <p>The UUID and dimension keys are refused in a patch rather than applied: the world indexes its
 * entities by UUID, and an entity whose UUID or dimension changes under it is lost to that index, not
 * moved. Vanilla's command restores the UUID before {@code readFromNBT}, which reads it straight back
 * from the patch.
 *
 * <p>Two tools rather than one with a mode, so the read is {@code readOnly()}: it keeps working
 * during an emergency stop, and a companion player with only the read class can use it.
 */
public final class ServerEntityNbtTools {

    /** Characters of NBT returned by default; a larger tag comes back as a summary. */
    private static final int DEFAULT_CHARS = 8192;

    /** The most a caller may raise that to. */
    private static final int MAX_CHARS = 65536;

    /** Default search radius around a position. */
    private static final int DEFAULT_RADIUS = 4;

    /** Other candidates listed when a position matches more than one entity. */
    private static final int MAX_OTHERS = 5;

    /** Longest SNBT a write's {@code restore} carries. */
    private static final int MAX_RESTORE_CHARS = 65536;

    /** Top-level keys a patch may not set. */
    private static final Set<String> REFUSED_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(
        java.util.Arrays.asList("UUIDMost", "UUIDLeast", "UUID", "Dimension")));

    private ServerEntityNbtTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_get_entity_nbt")
            .title("Read an entity's NBT")
            .description("Read an entity's server-side NBT, with no player near. Mod entities keep their "
                + "state here: an Immersive Vehicles (MTS) vehicle's parts and fuel are in its NBT, so read "
                + "it to see the layout before writing with server_set_entity_nbt. Pick the entity by "
                + "'uuid', or by x, y, z (nearest within 'radius', optionally of 'type'); players are never "
                + "picked. Returns 'nbt', narrowed by 'keys' (dot paths), or a summary of keys and sizes "
                + "when over maxChars or with summary: true.")
            .schema(selection(JsonSchema.object())
                .array("keys", "Only these NBT fields, as dot paths; '[n]' or '.n' picks one list element. "
                    + "Omit for the whole tag.", Json.obj("type", "string"))
                .integer("maxChars", "Largest tag returned in full; a larger one comes back as a summary. "
                    + "Default " + DEFAULT_CHARS + ".", 256, MAX_CHARS)
                .bool("summary", "Return the tag's keys and their sizes in characters instead of the "
                    + "values, to see what to ask for with 'keys'.")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                if (context.getArguments().has("nbt")) {
                    return ToolResult.error("This tool only reads; server_set_entity_nbt merges 'nbt' into "
                        + "an entity.");
                }
                Action read = new Action(null, false, Json.getStringList(context.getArguments(), "keys"),
                    context.getBoundedInt("maxChars", DEFAULT_CHARS, 256, MAX_CHARS),
                    context.getBoolean("summary", false));
                return pickAndAct(context, read);
            })
            .build());

        McpRegistry.registerTool(McpTool.named("server_set_entity_nbt")
            .title("Merge NBT into an entity")
            .description("Merge NBT into an entity's server-side NBT, as /entitydata does but with no "
                + "player near and no command-length limit. Read the entity with server_get_entity_nbt "
                + "first to see the layout. Pick the entity by 'uuid', or by x, y, z (nearest within "
                + "'radius', optionally of 'type'); players are never picked. Nested compounds merge, "
                + "lists and other values are replaced whole. UUID and position stay unless the patch "
                + "names them (Pos, Motion, Rotation); UUIDMost, UUIDLeast and Dimension are refused. The "
                + "result lists each changed top-level key before and after, as read back from the "
                + "entity, 'notKept' for patch keys the entity did not keep, and 'restore', SNBT that puts "
                + "the changed keys back (keys the write added cannot be removed by a merge). Not "
                + "undoable. Needs permissions.allowWorldEdits in the MCMCP config.")
            .schema(selection(JsonSchema.object())
                .property("nbt", ServerBuildTools.nbtSchema("The compound to merge into the entity."))
                .bool("resync", "Re-send the entity to players who can see it, so clients show state they "
                    + "are only told when an entity appears. Default true.")
                .integer("maxChars", "Largest key value returned in full in 'changes'; larger ones come "
                    + "back as a summary. Default " + DEFAULT_CHARS + ".", 256, MAX_CHARS)
                .required("nbt")
                .build())
            .serverOnly()
            .destructive()
            .handler(context -> {
                String denied = context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
                    "Writing entity NBT is disabled by permissions.allowWorldEdits in the MCMCP config.");
                if (denied != null) {
                    return ToolResult.error(denied);
                }
                JsonElement given = context.getArguments().get("nbt");
                if (given == null || given.isJsonNull()) {
                    return ToolResult.error("'nbt' is required: the compound to merge.");
                }
                NBTTagCompound patch;
                try {
                    patch = TileEntityNbt.parse(given);
                }
                catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage() + " Nothing was changed.");
                }
                if (patch.isEmpty()) {
                    return ToolResult.error("'nbt' is an empty compound; there is nothing to merge.");
                }
                List<String> refused = new ArrayList<>();
                for (String key : patch.getKeySet()) {
                    if (REFUSED_KEYS.contains(key)) {
                        refused.add(key);
                    }
                }
                if (!refused.isEmpty()) {
                    return ToolResult.error("The patch sets " + refused + ", which this tool refuses: the "
                        + "world finds entities by UUID and dimension, and changing either under a live "
                        + "entity loses it rather than moving it. Nothing was changed.");
                }
                Action write = new Action(patch, context.getBoolean("resync", true), Collections.<String>emptyList(),
                    context.getBoundedInt("maxChars", DEFAULT_CHARS, 256, MAX_CHARS), false);
                return pickAndAct(context, write);
            })
            .build());
    }

    /** The entity-selection arguments both tools take, and {@code load}. */
    private static JsonSchema selection(JsonSchema schema) {
        return ServerChunkTools.loadArgument(schema
            .string("uuid", "The entity's UUID, as server_nearby_entities lists it. Searched in "
                + "'dimension' when given, otherwise in every loaded dimension.")
            .number("x", "X to search around, instead of uuid.")
            .number("y", "Y to search around.")
            .number("z", "Z to search around.")
            .integer("radius", "Search radius in blocks around x, y, z. Default " + DEFAULT_RADIUS + ".",
                1, GameJson.MAX_ENTITY_RADIUS)
            .string("type", "Only this entity type around x, y, z: an id ('mts:mts_entity'; a bare "
                + "name is vanilla's), a namespace ending in ':', or a pattern with '*'.")
            .integer("dimension", "Dimension id. Defaults to 0 around a position; with uuid, every "
                + "dimension."));
    }

    /** What to do to the picked entity: read it, or merge {@code patch} into it. */
    private static final class Action {
        @Nullable
        final NBTTagCompound patch;
        final boolean resync;
        final List<String> keys;
        final int maxChars;
        final boolean summary;

        Action(@Nullable NBTTagCompound patch, boolean resync, List<String> keys, int maxChars, boolean summary) {
            this.patch = patch;
            this.resync = resync;
            this.keys = keys;
            this.maxChars = maxChars;
            this.summary = summary;
        }
    }

    /** Finds the entity the arguments name, by UUID or nearest position, and applies {@code action}. */
    private static ToolResult pickAndAct(ToolContext context, Action action) throws Exception {
        JsonObject args = context.getArguments();
        final UUID uuid;
        String uuidText = context.getString("uuid", null);
        if (uuidText != null && !uuidText.trim().isEmpty()) {
            try {
                uuid = UUID.fromString(uuidText.trim());
            }
            catch (IllegalArgumentException e) {
                return ToolResult.error("'" + uuidText + "' is not a UUID; server_nearby_entities lists "
                    + "each entity's.");
            }
            if (args.has("x") || args.has("y") || args.has("z")) {
                return ToolResult.error("Pick the entity by 'uuid' or by x, y, z, not both.");
            }
            final Integer dimension = args.has("dimension") ? context.getInt("dimension", 0) : null;
            JsonObject json = context.onGameThread(() -> {
                Entity entity = byUuid(uuid, dimension);
                if (entity == null) {
                    return failure("No loaded entity has UUID " + uuid
                        + (dimension == null ? "" : " in dimension " + dimension) + ". An entity in an "
                        + "unloaded chunk is not found by UUID; search around its position with "
                        + "load: true.");
                }
                if (entity instanceof EntityPlayer) {
                    return failure("That UUID is a player's. Player data is not read or written here; "
                        + "use server_player_state and server_player_inventory.");
                }
                JsonObject result = new JsonObject();
                result.add("entity", GameJson.entity(entity));
                act(entity, action, result);
                return result;
            });
            return finish(json);
        }

        if (!args.has("x") || !args.has("y") || !args.has("z")) {
            return ToolResult.error("Pick the entity by 'uuid', or by x, y and z (with radius and type).");
        }
        final double cx = context.requireDouble("x");
        final double cy = context.requireDouble("y");
        final double cz = context.requireDouble("z");
        final int radius = context.getBoundedInt("radius", DEFAULT_RADIUS, 1, GameJson.MAX_ENTITY_RADIUS);
        final int dimension = context.getInt("dimension", 0);
        String typeText = context.getString("type", null);
        final List<String> types = new ArrayList<>();
        if (typeText != null && !typeText.trim().isEmpty()) {
            String t = typeText.trim().toLowerCase(Locale.ROOT);
            types.add(t.indexOf(':') < 0 && t.indexOf('*') < 0 ? "minecraft:" + t : t);
        }
        final Set<Long> chunks = ChunkKeys.box((int) Math.floor(cx - radius), (int) Math.floor(cz - radius),
            (int) Math.floor(cx + radius), (int) Math.floor(cz + radius));
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            JsonObject json = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                AxisAlignedBB box = new AxisAlignedBB(cx - radius, cy - radius, cz - radius,
                    cx + radius, cy + radius, cz + radius);
                List<Entity> found = new ArrayList<>();
                for (Entity entity : world.getEntitiesWithinAABB(Entity.class, box)) {
                    if (entity instanceof EntityPlayer || entity.isDead) {
                        continue;
                    }
                    if (!types.isEmpty() && !ServerEntityTools.matches(ServerEntityTools.typeOf(entity), types)) {
                        continue;
                    }
                    if (entity.getDistanceSq(cx, cy, cz) <= (double) radius * radius) {
                        found.add(entity);
                    }
                }
                ChunkLoadGovernor.touch(dimension, chunks);
                if (found.isEmpty()) {
                    JsonObject none = failure("No entity" + (types.isEmpty() ? "" : " of type " + types.get(0))
                        + " within " + radius + " blocks of " + cx + ", " + cy + ", " + cz
                        + " in dimension " + dimension + ". Players are never picked.");
                    none.add("chunks", outcome != null ? outcome.toJson()
                        : ChunkLoadGovernor.describeUnloaded(world, chunks));
                    return none;
                }
                found.sort(Comparator.comparingDouble(e -> e.getDistanceSq(cx, cy, cz)));
                Entity entity = found.get(0);
                JsonObject result = new JsonObject();
                JsonObject described = GameJson.entity(entity);
                described.addProperty("distance", Math.round(Math.sqrt(entity.getDistanceSq(cx, cy, cz))
                    * 100.0D) / 100.0D);
                result.add("entity", described);
                result.addProperty("matched", found.size());
                if (found.size() > 1) {
                    // Nearest is a guess when there are several; say what else was there.
                    JsonArray others = new JsonArray();
                    for (int i = 1; i < found.size() && others.size() < MAX_OTHERS; i++) {
                        Entity other = found.get(i);
                        JsonObject brief = new JsonObject();
                        brief.addProperty("uuid", other.getUniqueID().toString());
                        brief.addProperty("type", ServerEntityTools.typeOf(other));
                        brief.addProperty("distance", Math.round(Math.sqrt(other.getDistanceSq(cx, cy, cz))
                            * 100.0D) / 100.0D);
                        others.add(brief);
                    }
                    result.add("others", com.micatechnologies.minecraft.mcmcp.json.JsonTables.table(others));
                }
                act(entity, action, result);
                result.add("chunks", outcome != null ? outcome.toJson()
                    : ChunkLoadGovernor.describeUnloaded(world, chunks));
                return result;
            });
            return finish(json);
        });
    }

    /** Game thread. */
    @Nullable
    private static Entity byUuid(UUID uuid, @Nullable Integer dimension) {
        if (dimension != null) {
            return ServerWorldTools.requireWorld(dimension).getEntityFromUuid(uuid);
        }
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null) {
            throw new IllegalStateException("No Minecraft server is running");
        }
        return server.getEntityFromUuid(uuid);
    }

    /** Reads, or merges and reports, into {@code result}. Game thread. */
    private static void act(Entity entity, Action action, JsonObject result) {
        final NBTTagCompound patch = action.patch;
        final boolean resync = action.resync;
        final List<String> keys = action.keys;
        final int maxChars = action.maxChars;
        final boolean summary = action.summary;
        NBTTagCompound before;
        try {
            before = entity.writeToNBT(new NBTTagCompound());
        }
        catch (RuntimeException e) {
            result.addProperty("error", "The entity could not write its NBT: " + e);
            return;
        }
        if (patch == null) {
            JsonElement tag = NbtJson.toJson(before);
            JsonObject nbt = tag.isJsonObject() ? tag.getAsJsonObject() : new JsonObject();
            JsonObject shown = keys.isEmpty() ? nbt : NbtProjection.project(nbt, keys);
            int size = Json.write(shown).length();
            if (summary || size > maxChars) {
                if (!summary) {
                    result.addProperty("truncated", true);
                }
                result.addProperty("chars", size);
                result.add("summary", NbtProjection.summary(shown, 3));
            }
            else {
                result.add("nbt", shown);
            }
            return;
        }

        NBTTagCompound merged = before.copy();
        merged.merge(patch);
        if (merged.equals(before)) {
            result.addProperty("changed", 0);
            result.addProperty("note", "The entity already held every value in the patch; nothing was written.");
            return;
        }
        UUID uuid = entity.getUniqueID();
        int dimension = entity.dimension;
        try {
            entity.readFromNBT(merged);
        }
        catch (RuntimeException e) {
            // Put back what it had, so a patch the entity chokes on halfway does not leave it in between.
            String restored;
            try {
                entity.readFromNBT(before);
                restored = "Its previous NBT was read back into it.";
            }
            catch (RuntimeException again) {
                restored = "Reading its previous NBT back failed too (" + again + "); check the entity.";
            }
            entity.setUniqueId(uuid);
            entity.dimension = dimension;
            result.addProperty("error", "The entity refused the merged NBT: " + e + ". " + restored);
            return;
        }
        entity.setUniqueId(uuid);
        entity.dimension = dimension;

        NBTTagCompound after;
        try {
            after = entity.writeToNBT(new NBTTagCompound());
        }
        catch (RuntimeException e) {
            result.addProperty("error", "The merge was applied, but the entity could not write its NBT back to "
                + "show the result: " + e);
            return;
        }

        JsonObject changed = new JsonObject();
        NBTTagCompound restore = new NBTTagCompound();
        JsonArray added = new JsonArray();
        Set<String> all = new java.util.TreeSet<>(before.getKeySet());
        all.addAll(after.getKeySet());
        for (String key : all) {
            NBTBase was = before.getTag(key);
            NBTBase now = after.getTag(key);
            if (was != null && was.equals(now)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.add("before", bounded(was, maxChars));
            entry.add("after", bounded(now, maxChars));
            changed.add(key, entry);
            if (was != null) {
                restore.setTag(key, was.copy());
            }
            else {
                added.add(key);
            }
        }
        JsonArray notKept = new JsonArray();
        for (String key : patch.getKeySet()) {
            NBTBase wanted = merged.getTag(key);
            NBTBase now = after.getTag(key);
            if (wanted != null && !wanted.equals(now)) {
                notKept.add(key);
            }
        }

        result.addProperty("changed", changed.size());
        result.add("changes", changed);
        if (notKept.size() > 0) {
            result.add("notKept", notKept);
            result.addProperty("notKeptNote", "The entity read these back differently from the patch: it "
                + "normalised, clamped or ignored them. 'changes' shows what it holds now.");
        }
        if (added.size() > 0) {
            result.add("added", added);
        }
        if (!restore.isEmpty()) {
            String snbt = restore.toString();
            if (snbt.length() <= MAX_RESTORE_CHARS) {
                result.addProperty("restore", snbt);
            }
            else {
                result.addProperty("restoreOmitted", "The previous values are " + snbt.length()
                    + " characters of SNBT, over " + MAX_RESTORE_CHARS + "; read the keys you need first to keep "
                    + "a copy.");
            }
        }
        // Saved with its chunk, which only saves entities it is told have changed.
        net.minecraft.world.World world = entity.world;
        net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(entity);
        if (world != null && world.isBlockLoaded(at)) {
            world.getChunk(at).markDirty();
        }
        if (resync && world instanceof WorldServer) {
            // A fresh spawn to everyone tracking it: some state (a mod entity's spawn data) is only sent
            // when an entity appears. Riders are re-sent with it by the tracker entry.
            WorldServer server = (WorldServer) world;
            server.getEntityTracker().untrack(entity);
            server.getEntityTracker().track(entity);
            result.addProperty("resynced", true);
        }
    }

    /** A tag value as JSON, or its summary when it is over {@code maxChars}. */
    private static JsonElement bounded(@Nullable NBTBase tag, int maxChars) {
        if (tag == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonElement json = NbtJson.toJson(tag);
        int size = Json.write(json).length();
        if (size <= maxChars) {
            return json;
        }
        JsonObject cut = new JsonObject();
        cut.addProperty("truncated", true);
        cut.add("summary", NbtProjection.summary(json, 2));
        return cut;
    }

    private static JsonObject failure(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("failure", message);
        return json;
    }

    private static ToolResult finish(JsonObject json) {
        if (json.has("failure")) {
            String message = json.get("failure").getAsString();
            json.remove("failure");
            return json.entrySet().isEmpty() ? ToolResult.error(message)
                : ToolResult.error(message).withStructured(json);
        }
        if (json.has("error")) {
            return ToolResult.error(json.get("error").getAsString()).withStructured(json);
        }
        return ToolResult.structured(json);
    }
}
