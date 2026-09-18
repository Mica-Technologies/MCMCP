package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.properties.IProperty;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

/**
 * Turns live game objects into the JSON that MCP tools and resources return.
 *
 * <p>Every method here <strong>must be called on the game thread</strong>. They read entity
 * positions, block states and inventories directly, none of which is safe to touch from an HTTP
 * worker. The pattern throughout MCMCP is: hop to the game thread, build the JSON there, hand the
 * finished {@link JsonObject} back across the boundary. The JSON is an immutable snapshot; the game
 * objects it came from are not, and must never escape.
 *
 * <p>Two conventions run through all of it, because models get both wrong otherwise and the fix is
 * to be explicit in the data rather than in prose:
 *
 * <ul>
 *   <li>Block positions are integers and appear as {@code x}/{@code y}/{@code z}. Entity positions
 *       are doubles and appear as {@code position}, alongside a separate {@code blockPosition} for
 *       the block the entity occupies. They are not interchangeable — an entity at y=64.0 is
 *       standing <em>on</em> the block at y=63.</li>
 *   <li>Anything with a registry name gets its full namespaced id ({@code minecraft:stone},
 *       {@code csm:traffic_light}), never a display name. Display names are localised and change
 *       between languages; ids are what commands and other tools accept.</li>
 * </ul>
 */
public final class GameJson {

    private GameJson() {
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    public static JsonObject blockPos(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    public static JsonObject vec(double x, double y, double z) {
        JsonObject json = new JsonObject();
        json.addProperty("x", round(x));
        json.addProperty("y", round(y));
        json.addProperty("z", round(z));
        return json;
    }

    public static JsonObject vec(Vec3d vector) {
        return vec(vector.x, vector.y, vector.z);
    }

    /**
     * The block an entity actually occupies.
     *
     * <p>Use this in preference to {@link Entity#getPosition()}, which cannot be trusted for the
     * local player. {@code EntityPlayerSP} overrides it as
     * {@code new BlockPos(posX + 0.5, posY + 0.5, posZ + 0.5)} — a <em>round</em>, not a floor — so
     * at a block centre, which is where a player stands after almost any teleport or spawn, it names
     * the block one over on both horizontal axes. {@code EntityPlayerMP} has no such override, so
     * using {@code getPosition()} also made the client and server endpoints disagree about where the
     * same player was standing.
     *
     * <p>Found by driving a live client: {@code client_player_state} reported the player at block
     * (35, 64, 13) while its own {@code standingOn} field described (36, 63, 14).
     */
    public static BlockPos blockPosOf(Entity entity) {
        return new BlockPos(
            MathHelper.floor(entity.posX),
            MathHelper.floor(entity.posY),
            MathHelper.floor(entity.posZ));
    }

    /**
     * Rounds to three decimals.
     *
     * <p>Raw doubles from the game serialise as {@code 64.00000000000001} and similar. That noise is
     * pure token cost in every response and invites a model to treat two identical positions as
     * different. Millimetre precision is far finer than anything a tool here acts on.
     */
    private static double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    /**
     * Whether this side actually holds the chunk {@code pos} is in.
     *
     * <p>Not {@link World#isBlockLoaded(BlockPos)}, which is the obvious call and is always true on a
     * client. It passes {@code allowEmpty = true}, and {@code WorldClient.isChunkLoaded} is
     * {@code allowEmpty || !provideChunk(x, z).isEmpty()} — so the question is never asked. The read
     * that follows lands on {@code ChunkProviderClient}'s shared {@code EmptyChunk}, which answers
     * air, block light 0, sky light 15 and, having no biome array, Plains: a complete, plausible
     * reading of open sky for a chunk the client was never sent. That is how a conduit run traced
     * across a few hundred blocks came back "broken" at the edge of the view distance.
     *
     * <p>{@code WorldServer.isChunkLoaded} ignores the flag, so the server side is unchanged.
     */
    public static boolean isLoaded(World world, BlockPos pos) {
        return world.isBlockLoaded(pos, false);
    }

    /**
     * Describes the block at {@code pos}.
     *
     * <p>Reports {@code loaded: false} and nothing else for an unloaded chunk rather than reading
     * through it. {@link World#getBlockState} on an unloaded position silently returns air, so a
     * naive read would confidently report empty space where there is a mountain — and, on a server,
     * asking for it can force a chunk load, turning a read-only query into a world mutation with a
     * disk hit.
     */
    public static JsonObject block(World world, BlockPos pos) {
        JsonObject json = new JsonObject();
        json.add("position", blockPos(pos));

        if (!isLoaded(world, pos)) {
            json.addProperty("loaded", false);
            return json;
        }
        json.addProperty("loaded", true);

        IBlockState state = world.getBlockState(pos);
        ResourceLocation registryName = state.getBlock().getRegistryName();
        json.addProperty("block", registryName == null ? "unknown" : registryName.toString());
        json.addProperty("metadata", state.getBlock().getMetaFromState(state));
        json.addProperty("displayName", state.getBlock().getLocalizedName());
        json.addProperty("air", world.isAirBlock(pos));

        JsonObject properties = new JsonObject();
        for (Map.Entry<IProperty<?>, Comparable<?>> entry : state.getProperties().entrySet()) {
            properties.addProperty(entry.getKey().getName(), String.valueOf(entry.getValue()));
        }
        json.add("state", properties);

        JsonObject actual = actualStateProperties(world, pos, state, properties);
        if (actual != null) {
            json.add("actualState", actual);
        }
        JsonObject bounds = boundingBox(world, pos, state);
        if (bounds != null) {
            json.add("boundingBox", bounds);
        }

        json.addProperty("blockLight", world.getLightFor(EnumSkyBlock.BLOCK, pos));
        json.addProperty("skyLight", world.getLightFor(EnumSkyBlock.SKY, pos));
        json.addProperty("hardness", state.getBlockHardness(world, pos));
        return json;
    }

    /**
     * The block's state as it is actually drawn and interacted with, when that differs from the
     * state stored in the chunk.
     *
     * <p>Blocks whose appearance depends on their surroundings — fences, walls, redstone wire, and
     * any modded block that connects or mounts to its neighbours — keep placeholder values in the
     * chunk and compute the real ones in {@code Block.getActualState} every time they are drawn.
     * Reading only the stored state therefore describes every such block identically no matter what
     * is around it, and worse, the placeholder is commonly "every connection present": a fence
     * standing alone in a field reads as connected on all four sides. That silently defeats any
     * attempt to verify connection or mounting behaviour, because the answer looks confidently
     * correct and is the same for every case being compared.
     *
     * <p>Reported only when it differs from {@code state}, so the key's presence is itself the
     * signal that this block computes its appearance at render time. When present it carries the
     * block's full property set, not only the properties that changed.
     *
     * @return the actual-state properties, or {@code null} when they match the stored state
     */
    @Nullable
    private static JsonObject actualStateProperties(World world, BlockPos pos, IBlockState state,
                                                    JsonObject stored) {
        IBlockState actual;
        try {
            actual = state.getActualState(world, pos);
        } catch (RuntimeException e) {
            // getActualState reads neighbours and, on a modded block, is third-party code. One
            // block that throws must not take the whole read down with it.
            return null;
        }
        if (actual == state) {
            return null;
        }

        JsonObject properties = new JsonObject();
        boolean differs = false;
        for (Map.Entry<IProperty<?>, Comparable<?>> entry : actual.getProperties().entrySet()) {
            String name = entry.getKey().getName();
            String value = String.valueOf(entry.getValue());
            properties.addProperty(name, value);
            JsonElement before = stored.get(name);
            if (before == null || !value.equals(before.getAsString())) {
                differs = true;
            }
        }
        return differs ? properties : null;
    }

    /**
     * The block's selection box, in block-relative coordinates, when it is not a full cube.
     *
     * <p>This is the shape a player's crosshair actually catches, and for a mod that gives its
     * blocks hand-written bounding boxes it is the only way to check one without standing in front
     * of it and squinting: a box on the wrong face, or one that stops short of the geometry it is
     * supposed to cover, is invisible in a screenshot but makes the block unclickable from the side
     * it should be clickable from.
     *
     * <p>Block-relative rather than world coordinates because that is the frame the box is written
     * in — a value here compares directly against the source. Absent when the block fills its cube,
     * which is the default and would otherwise repeat on almost every read.
     *
     * @return {@code minX/minY/minZ/maxX/maxY/maxZ}, or {@code null} for a full cube or on failure
     */
    @Nullable
    private static JsonObject boundingBox(World world, BlockPos pos, IBlockState state) {
        AxisAlignedBB box;
        try {
            box = state.getBoundingBox(world, pos);
        } catch (RuntimeException e) {
            // Same reasoning as actualState: this is third-party code on a modded block.
            return null;
        }
        if (box == null) {
            return null;
        }
        boolean fullCube = box.minX == 0.0 && box.minY == 0.0 && box.minZ == 0.0
            && box.maxX == 1.0 && box.maxY == 1.0 && box.maxZ == 1.0;
        if (fullCube) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("minX", round(box.minX));
        json.addProperty("minY", round(box.minY));
        json.addProperty("minZ", round(box.minZ));
        json.addProperty("maxX", round(box.maxX));
        json.addProperty("maxY", round(box.maxY));
        json.addProperty("maxZ", round(box.maxZ));
        return json;
    }

    /** Largest tile entity tag returned whole, measured as the JSON it becomes. */
    private static final int MAX_BLOCK_ENTITY_CHARS = 32 * 1024;

    /**
     * The tile entity at {@code pos} and its NBT, as this side holds it.
     *
     * <p>{@code source} is the point. On a server the tag is the block's whole saved state. On a
     * client it is only what the server chose to send — {@code getUpdateTag} and update packets,
     * which a mod writes for what it has to <em>draw</em> — so a missing key means "not synced",
     * not "not set", and the two read identically unless the result says which side answered.
     *
     * <p>Looked up with {@code CHECK}, which never creates one: {@code World.getTileEntity} will
     * construct a missing tile entity for a block that should have one, and a read must not.
     *
     * @return {@code {"present": false}} when there is none, which is an answer and not an omission
     */
    public static JsonObject blockEntity(World world, BlockPos pos) {
        JsonObject json = new JsonObject();
        TileEntity tile = !isLoaded(world, pos) ? null
            : world.getChunk(pos).getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
        if (tile == null) {
            json.addProperty("present", false);
            return json;
        }
        json.addProperty("source", world.isRemote ? "client-synced" : "server");

        JsonElement nbt;
        try {
            nbt = NbtJson.toJson(tile.writeToNBT(new NBTTagCompound()));
        } catch (RuntimeException e) {
            // writeToNBT is third-party code on a modded block, and on a client it may be reading
            // fields the mod only ever populates on the server.
            json.addProperty("error", "The tile entity could not write its tag: " + e);
            return json;
        }

        int size = Json.write(nbt).length();
        if (size > MAX_BLOCK_ENTITY_CHARS) {
            JsonArray keys = new JsonArray();
            for (Map.Entry<String, JsonElement> entry : nbt.getAsJsonObject().entrySet()) {
                keys.add(entry.getKey());
            }
            json.addProperty("truncated", true);
            json.addProperty("chars", size);
            json.add("keys", keys);
            return json;
        }
        json.add("nbt", nbt);
        return json;
    }

    // ------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------

    /**
     * Describes an entity.
     *
     * <p>{@code type} is the registry id where one exists. Players have no entity-registry entry in
     * 1.12.2, so they are reported as {@code minecraft:player} explicitly rather than as null —
     * a model filtering on type should not have to special-case the most important entity in the
     * world.
     */
    public static JsonObject entity(Entity entity) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entity.getEntityId());
        json.addProperty("uuid", entity.getUniqueID().toString());
        json.addProperty("name", entity.getName());

        if (entity instanceof EntityPlayer) {
            json.addProperty("type", "minecraft:player");
        }
        else {
            ResourceLocation key = EntityList.getKey(entity);
            json.addProperty("type", key == null ? "unknown" : key.toString());
        }

        json.add("position", vec(entity.posX, entity.posY, entity.posZ));
        json.add("blockPosition", blockPos(blockPosOf(entity)));
        // Wrapped, because the game never does: mouse look only ever adds to rotationYaw, so a player
        // who has been turning for a while reads 24507.33, which says nothing about which way they
        // face and cannot be compared with the -180..180 that client_look reports.
        json.addProperty("yaw", round(MathHelper.wrapDegrees(entity.rotationYaw)));
        json.addProperty("pitch", round(entity.rotationPitch));
        json.add("velocity", vec(entity.motionX, entity.motionY, entity.motionZ));
        json.addProperty("onGround", entity.onGround);
        json.addProperty("dimension", entity.dimension);

        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            json.addProperty("health", round(living.getHealth()));
            json.addProperty("maxHealth", round(living.getMaxHealth()));
            ItemStack held = living.getHeldItemMainhand();
            if (!held.isEmpty()) {
                json.add("heldItem", itemStack(held));
            }
        }
        return json;
    }

    /** A player, with the survival state a model needs before deciding what is safe to do. */
    public static JsonObject player(EntityPlayer player) {
        JsonObject json = entity(player);
        json.addProperty("health", round(player.getHealth()));
        json.addProperty("food", player.getFoodStats().getFoodLevel());
        json.addProperty("saturation", round(player.getFoodStats().getSaturationLevel()));
        json.addProperty("experienceLevel", player.experienceLevel);
        json.addProperty("air", player.getAir());
        json.addProperty("creative", player.capabilities.isCreativeMode);
        json.addProperty("flying", player.capabilities.isFlying);
        json.addProperty("sneaking", player.isSneaking());
        json.addProperty("sprinting", player.isSprinting());
        json.addProperty("inWater", player.isInWater());
        json.addProperty("selectedSlot", player.inventory.currentItem);
        return json;
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    /**
     * Describes one stack.
     *
     * <p>NBT is included as its {@code toString} form only when present. It is the difference
     * between "a diamond sword" and "a diamond sword with Sharpness V", which matters for tool
     * decisions — but a full structured NBT tree would dominate the response for every enchanted
     * item in an inventory listing.
     */
    public static JsonObject itemStack(ItemStack stack) {
        JsonObject json = new JsonObject();
        if (stack.isEmpty()) {
            json.addProperty("empty", true);
            return json;
        }
        ResourceLocation registryName = stack.getItem().getRegistryName();
        json.addProperty("item", registryName == null ? "unknown" : registryName.toString());
        json.addProperty("displayName", stack.getDisplayName());
        json.addProperty("count", stack.getCount());
        json.addProperty("damage", stack.getItemDamage());
        json.addProperty("maxDamage", stack.getMaxDamage());
        if (stack.hasTagCompound()) {
            json.addProperty("nbt", String.valueOf(stack.getTagCompound()));
        }
        return json;
    }

    /**
     * Lists an inventory's occupied slots.
     *
     * <p>Empty slots are omitted from the array but counted in {@code emptySlots}. Emitting 36
     * {@code {"empty": true}} entries for a mostly-bare inventory is the single largest avoidable
     * cost in a typical response, and "which slot indices are free" is answerable from the slot
     * numbers that are present.
     */
    public static JsonObject inventory(IInventory inventory) {
        JsonObject json = new JsonObject();
        json.addProperty("size", inventory.getSizeInventory());

        JsonArray slots = new JsonArray();
        int empty = 0;
        for (int index = 0; index < inventory.getSizeInventory(); index++) {
            ItemStack stack = inventory.getStackInSlot(index);
            if (stack.isEmpty()) {
                empty++;
                continue;
            }
            JsonObject slot = itemStack(stack);
            slot.addProperty("slot", index);
            slots.add(slot);
        }
        json.addProperty("emptySlots", empty);
        json.add("items", slots);
        return json;
    }

    // ------------------------------------------------------------------
    // World
    // ------------------------------------------------------------------

    public static JsonObject world(World world) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", world.provider.getDimension());
        json.addProperty("dimensionType", world.provider.getDimensionType().getName());
        json.addProperty("timeOfDay", world.getWorldTime() % 24000L);
        json.addProperty("totalTime", world.getTotalWorldTime());
        json.addProperty("daytime", world.isDaytime());
        json.addProperty("raining", world.isRaining());
        json.addProperty("thundering", world.isThundering());
        json.addProperty("difficulty", world.getDifficulty().name());
        json.addProperty("remote", world.isRemote);
        return json;
    }

    @Nullable
    public static String biomeName(World world, BlockPos pos) {
        if (!isLoaded(world, pos)) {
            return null;
        }
        Biome biome = world.getBiome(pos);
        return biome == null ? null : biome.getBiomeName();
    }

    // ------------------------------------------------------------------
    // Ray tracing
    // ------------------------------------------------------------------

    /**
     * Describes what a look-vector ray trace hit.
     *
     * <p>{@code null} in means "nothing in range", which is reported as {@code {"type":"miss"}}
     * rather than as an absent field: a model reading "what am I looking at" needs a definite answer
     * either way, and an omitted key reads as a tool failure.
     */
    public static JsonObject rayTrace(World world, @Nullable RayTraceResult result) {
        JsonObject json = new JsonObject();
        if (result == null || result.typeOfHit == RayTraceResult.Type.MISS) {
            json.addProperty("type", "miss");
            return json;
        }
        if (result.typeOfHit == RayTraceResult.Type.BLOCK) {
            json.addProperty("type", "block");
            json.add("block", block(world, result.getBlockPos()));
            EnumFacing face = result.sideHit;
            json.addProperty("face", face == null ? "unknown" : face.getName());
            json.add("hitVector", vec(result.hitVec));
            return json;
        }
        json.addProperty("type", "entity");
        if (result.entityHit != null) {
            json.add("entity", entity(result.entityHit));
        }
        return json;
    }

    /**
     * The same ray trace, answering only "what am I now pointed at".
     *
     * <p>For the reports a tool appends to an action it has just taken, where the target is
     * confirmation rather than the question. {@link #rayTrace} embeds a whole {@link #block}
     * — display name, every state property, {@code actualState}, the bounding box, both light
     * levels, hardness — and against a modded block that came to 531 bytes on a {@code client_move}
     * reply whose own content is 140. The field was 79% of the response, on every call of a loop
     * that is nothing but repeated calls.
     *
     * <p>Depth was the waste, not the field. A model turning the camera and checking where it landed
     * needs the id, the position and the face; it does not need the hardness of the block it happens
     * to be facing, and when it does, {@code client_looking_at} and {@code client_get_block} answer
     * exactly that and are one call away. Those two keep the full form, as do the standalone
     * orienting payloads — {@code client_player_state}, the prompts and the resources — where the
     * detail is what was asked for rather than something appended to an answer about movement.
     */
    public static JsonObject rayTraceBrief(World world, @Nullable RayTraceResult result) {
        JsonObject json = new JsonObject();
        if (result == null || result.typeOfHit == RayTraceResult.Type.MISS) {
            json.addProperty("type", "miss");
            return json;
        }
        if (result.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos pos = result.getBlockPos();
            json.addProperty("type", "block");
            // Loaded is checked for the same reason block() checks it: getBlockState reads an
            // unloaded position as air, which would report empty sky in front of a wall.
            if (!isLoaded(world, pos)) {
                json.addProperty("block", "mcmcp:unloaded");
            }
            else {
                ResourceLocation name = world.getBlockState(pos).getBlock().getRegistryName();
                json.addProperty("block", name == null ? "unknown" : name.toString());
            }
            json.add("position", blockPos(pos));
            EnumFacing face = result.sideHit;
            json.addProperty("face", face == null ? "unknown" : face.getName());
            return json;
        }

        json.addProperty("type", "entity");
        if (result.entityHit != null) {
            Entity hit = result.entityHit;
            JsonObject brief = new JsonObject();
            brief.addProperty("id", hit.getEntityId());
            brief.addProperty("name", hit.getName());
            if (hit instanceof EntityPlayer) {
                brief.addProperty("type", "minecraft:player");
            }
            else {
                ResourceLocation key = EntityList.getKey(hit);
                brief.addProperty("type", key == null ? "unknown" : key.toString());
            }
            brief.add("blockPosition", blockPos(blockPosOf(hit)));
            json.add("entity", brief);
        }
        return json;
    }
}
