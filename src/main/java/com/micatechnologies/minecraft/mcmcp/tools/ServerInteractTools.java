package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.Collections;
import java.util.Locale;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Using an item on a block, or activating a block, as a player but from any distance (#57): stringing
 * a span wire between two anchors, a track tool's clicks, placing a door, opening a controller.
 *
 * <h2>What is and is not skipped</h2>
 *
 * It runs vanilla's own right-click path, {@code PlayerInteractionManager.processRightClickBlock}, as
 * the real player: Forge's interact event fires, so claim and protection mods still decide; the item's
 * {@code onItemUseFirst}, the block's {@code onBlockActivated} and the item's {@code onItemUse} run as
 * they would for a click; a placement fires the place event. What the network handler normally checks
 * before calling it is re-applied here — build height, spawn protection, the world border — except the
 * one thing this exists to lift: reach.
 *
 * <h2>Containers</h2>
 *
 * A container opened from further than about eight blocks is closed by the server on the next tick.
 * So reading its slots, or pressing one of its buttons, happens in the same game-thread task that
 * opens it, through the {@code container} argument.
 */
public final class ServerInteractTools {

    private ServerInteractTools() {
    }

    public static void register() {
        register("server_use_item_on_block", "Use an item on a block",
            "Use the item in a hotbar slot on a block, as that player and from any distance: the click a "
                + "span-wire tool, a track tool or a door needs. Runs the game's own right-click path, so "
                + "protection mods still decide and a placement is a real placement; only reach is lifted. "
                + "The item must already be in the player's hotbar. Returns the block before and after, the "
                + "block on the clicked face after, and the item afterwards.", true);
        register("server_activate_block", "Activate a block",
            "Right-click a block with an empty hand, as that player and from any distance: open a "
                + "controller, flip a lever, open a container. With 'container', read the container that opens "
                + "and press one of its buttons in the same moment — a container opened from afar closes again "
                + "on the next tick.", false);
    }

    private static void register(String name, String title, String description, final boolean withItem) {
        JsonSchema schema = JsonSchema.object()
            .integer("x", "Block X.")
            .integer("y", "Block Y.")
            .integer("z", "Block Z.")
            .enumeration("face", "The face clicked. Default 'up'.", "down", "up", "north", "south", "west", "east")
            .number("hitX", "Where on the face, 0-1 across the block. Default the face's centre.", 0.0D, 1.0D)
            .number("hitY", "As hitX, in Y.", 0.0D, 1.0D)
            .number("hitZ", "As hitX, in Z.", 0.0D, 1.0D)
            .bool("sneak", "Click while sneaking. Default false.")
            .string("player", "The player who clicks. Through the companion, always you.");
        if (withItem) {
            schema = schema.integer("slot", "Hotbar slot 0-8 holding the item. Default the selected slot.", 0, 8);
        }
        schema = schema.property("container", com.micatechnologies.minecraft.mcmcp.json.Json.obj("type", "object"))
            .bool("load", "Load the block's chunk for this call if it is not loaded.");
        McpRegistry.registerTool(McpTool.named(name)
            .title(title)
            .description(description + " 'container': {\"read\": true} lists the slots of a container the click "
                + "opens; {\"button\": n} presses button n as its own buttons do.")
            .schema(schema.required("x", "y", "z").build())
            .serverOnly()
            .destructive()
            .handler(context -> interact(context, withItem))
            .build());
    }

    private static ToolResult interact(final ToolContext context, final boolean withItem) throws Exception {
        String denied = context.refusal(Capability.ITEM_USE, McmcpConfig.isAllowWorldEdits(),
            "Using items on blocks is disabled by permissions.allowWorldEdits in the MCMCP config.");
        if (denied != null) {
            return ToolResult.error(denied);
        }
        final String playerName = context.getString("player", null);
        if (playerName == null || playerName.isEmpty()) {
            return ToolResult.error("Name the 'player' who clicks.");
        }
        final BlockPos pos = new BlockPos(context.requireInt("x"), context.requireInt("y"), context.requireInt("z"));
        final EnumFacing face = EnumFacing.byName(context.getString("face", "up").toLowerCase(Locale.ROOT));
        if (face == null) {
            return ToolResult.error("'face' is one of down, up, north, south, west, east.");
        }
        final float hitX = (float) context.getDouble("hitX", 0.5D + 0.5D * face.getXOffset());
        final float hitY = (float) context.getDouble("hitY", 0.5D + 0.5D * face.getYOffset());
        final float hitZ = (float) context.getDouble("hitZ", 0.5D + 0.5D * face.getZOffset());
        final boolean sneak = context.getBoolean("sneak", false);
        final int slot = context.getInt("slot", -1);
        final JsonObject containerAsk = com.micatechnologies.minecraft.mcmcp.json.Json.getObjectOrEmpty(
            context.getArguments(), "container");

        final int dimension = context.onGameThread(() -> {
            EntityPlayerMP player = requirePlayer(playerName);
            return player.dimension;
        });

        return ServerChunkTools.withChunks(context, dimension,
            Collections.singleton(ChunkKeys.ofBlock(pos.getX(), pos.getZ())), outcome -> {
                final UndoPoints.Recorder recorder = ServerUndoTools.start(context, true);
                JsonObject json = context.onGameThread(() -> {
                    EntityPlayerMP player = requirePlayer(playerName);
                    WorldServer world = player.getServerWorld();
                    MinecraftServer server = ServerThreadBridge.server();
                    if (!world.isBlockLoaded(pos)) {
                        throw new IllegalStateException("That block's chunk is not loaded. Pass load: true, or hold it "
                            + "with server_keep_loaded.");
                    }
                    // What processTryUseItemOnBlock checks before calling the same method, less reach.
                    int buildLimit = server.getBuildLimit();
                    if (!(pos.getY() < buildLimit - 1 || face != EnumFacing.UP && pos.getY() < buildLimit)) {
                        throw new IllegalStateException("That is above the build limit.");
                    }
                    if (server.isBlockProtected(world, pos, player)) {
                        throw new IllegalStateException("That block is inside spawn protection, which this player "
                            + "may not change.");
                    }
                    if (!world.getWorldBorder().contains(pos)) {
                        throw new IllegalStateException("That block is outside the world border.");
                    }

                    JsonObject result = new JsonObject();
                    result.addProperty("player", player.getName());
                    result.addProperty("distance", Math.round(Math.sqrt(player.getDistanceSq(pos)) * 10.0D) / 10.0D);
                    result.add("before", GameJson.block(world, pos));
                    BlockPos beside = pos.offset(face);
                    UndoPoints.Before wasAt = recorder == null ? null : recorder.capture(world, pos);
                    UndoPoints.Before wasBeside = recorder == null ? null : recorder.capture(world, beside);
                    Container before = player.openContainer;

                    int previousSlot = player.inventory.currentItem;
                    boolean previousSneak = player.isSneaking();
                    EnumActionResult outcomeResult;
                    try {
                        if (withItem && slot >= 0) {
                            player.inventory.currentItem = slot;
                        }
                        player.setSneaking(sneak);
                        ItemStack stack = withItem ? player.getHeldItem(EnumHand.MAIN_HAND) : ItemStack.EMPTY;
                        if (withItem && stack.isEmpty()) {
                            throw new IllegalStateException("Hotbar slot " + player.inventory.currentItem
                                + " is empty. Put the item in the player's hotbar first.");
                        }
                        result.addProperty("item", stack.isEmpty() ? "empty hand" : String.valueOf(stack.getItem().getRegistryName()));
                        outcomeResult = player.interactionManager.processRightClickBlock(player, world, stack,
                            EnumHand.MAIN_HAND, pos, face, hitX, hitY, hitZ);
                        if (withItem) {
                            ItemStack after = player.getHeldItem(EnumHand.MAIN_HAND);
                            JsonObject held = new JsonObject();
                            held.addProperty("item", after.isEmpty() ? "empty" : String.valueOf(after.getItem().getRegistryName()));
                            held.addProperty("count", after.getCount());
                            result.add("heldAfter", held);
                        }
                    }
                    finally {
                        player.inventory.currentItem = previousSlot;
                        player.setSneaking(previousSneak);
                    }
                    // Vanilla answers 'pass' for an empty hand even when the block reacted, so say plainly
                    // whether anything changed.
                    result.addProperty("result", outcomeResult.name().toLowerCase(Locale.ROOT));
                    result.add("after", GameJson.block(world, pos));
                    result.add("besideAfter", GameJson.block(world, beside));
                    result.addProperty("changed", !result.get("before").equals(result.get("after"))
                        || (wasBeside != null && world.getBlockState(beside) != wasBeside.state)
                        || player.openContainer != before);
                    // Only what changed: a lever flip or a chest opened is not worth an undo point, and fifty of
                    // them would push a real build's point out of the kept fifty.
                    if (recorder != null && wasAt != null && world.getBlockState(pos) != wasAt.state) {
                        recorder.record(pos, wasAt, world.getBlockState(pos));
                    }
                    if (recorder != null && wasBeside != null && world.getBlockState(beside) != wasBeside.state) {
                        recorder.record(beside, wasBeside, world.getBlockState(beside));
                    }

                    Container opened = player.openContainer;
                    if (opened != before && opened != player.inventoryContainer) {
                        result.add("container", container(player, opened, containerAsk));
                    }
                    return result;
                });
                if (recorder != null) {
                    ServerUndoTools.finish(context, recorder, withItem ? "server_use_item_on_block" : "server_activate_block",
                        dimension, null, null, json, null);
                }
                return ToolResult.structured(json);
            });
    }

    /** Game thread: the container a click opened, read and clicked in the same moment. */
    private static JsonObject container(EntityPlayerMP player, Container opened, JsonObject ask) {
        JsonObject json = new JsonObject();
        json.addProperty("type", opened.getClass().getName());
        json.addProperty("windowId", opened.windowId);
        json.addProperty("usableFromHere", opened.canInteractWith(player));
        if (com.micatechnologies.minecraft.mcmcp.json.Json.getBoolean(ask, "read", false)) {
            JsonArray slots = new JsonArray();
            for (int i = 0; i < opened.inventorySlots.size() && slots.size() < 200; i++) {
                Slot slot = opened.inventorySlots.get(i);
                if (slot.inventory == player.inventory) {
                    continue;
                }
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("slot", i);
                item.addProperty("item", String.valueOf(stack.getItem().getRegistryName()));
                item.addProperty("meta", stack.getMetadata());
                item.addProperty("count", stack.getCount());
                slots.add(item);
            }
            json.add("slots", slots);
        }
        if (ask.has("button")) {
            int button = com.micatechnologies.minecraft.mcmcp.json.Json.getInt(ask, "button", 0);
            boolean pressed = opened.enchantItem(player, button);
            opened.detectAndSendChanges();
            json.addProperty("button", button);
            json.addProperty("buttonAccepted", pressed);
        }
        if (!opened.canInteractWith(player)) {
            json.addProperty("note", "The player is too far for this container to stay open; the server closes "
                + "it on the next tick. Anything to do in it belongs in this call's 'container'.");
        }
        return json;
    }

    private static EntityPlayerMP requirePlayer(String name) {
        MinecraftServer server = ServerThreadBridge.server();
        EntityPlayerMP player = server == null ? null : server.getPlayerList().getPlayerByUsername(name);
        if (player == null) {
            throw new IllegalStateException("No player named '" + name + "' is connected.");
        }
        return player;
    }
}
