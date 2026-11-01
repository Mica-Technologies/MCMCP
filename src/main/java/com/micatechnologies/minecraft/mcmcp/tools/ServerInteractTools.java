package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
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
import java.util.UUID;
import net.minecraft.block.state.IBlockState;
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
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.Event;

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
        ClickWatch.register();
        register("server_use_item_on_block", "Use an item on a block",
            "Use the item in a hotbar slot on a block, as that player and from any distance: the click a "
                + "span-wire tool, a track tool or a door needs. Runs the game's own right-click path, so "
                + "protection mods still decide and a placement is a real placement; only reach is lifted. "
                + "The item must already be in the player's hotbar; to press a button or a lever with an empty "
                + "hand, use server_activate_block. Returns the block before and after, the block on the "
                + "clicked face after, and the item afterwards.", true);
        register("server_activate_block", "Activate a block",
            "Right-click a block with an empty hand, as that player and from any distance: press a button, "
                + "open a controller, flip a lever, open a container. The player's selected slot moves to an "
                + "empty hotbar slot for the click and back; with none empty, the held stack is set aside "
                + "for the click and put back. 'activated' is the block's own answer (vanilla's 'result' is "
                + "'pass' for an empty hand either way), and 'why' says what stopped it: a protection mod "
                + "cancelling or denying the click, sneaking, or the block refusing. Omit 'player' to click "
                + "as MCMCP's own fake player, for a server nobody has joined (through the companion the "
                + "click is always yours); the result then says fakePlayer: true. With 'container', read "
                + "the container that opens "
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
            .string("player", withItem ? "The player who clicks. Through the companion, always you."
                : "The player who clicks. Through the companion, always you. Omit for MCMCP's fake player.");
        if (!withItem) {
            schema = schema.integer("dimension", "With no 'player': the dimension of the block. Default 0.");
        }
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
        // An empty-hand click needs no one's inventory, so with nobody named it is made by a Forge fake
        // player: a headless test can press a button on a server nobody has joined (#104). An item use
        // still needs a real player's hotbar.
        final boolean fake = !withItem && (playerName == null || playerName.isEmpty());
        if (!fake && (playerName == null || playerName.isEmpty())) {
            return ToolResult.error("Name the 'player' who clicks. To press a block with no player online, use "
                + "server_activate_block without 'player'.");
        }
        // Another agent's lease on this player: its slots and position are theirs (#144).
        ToolResult leased = fake ? null : ServerPlayerLeaseTools.refusal(context, playerName);
        if (leased != null) {
            return leased;
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

        final int dimension = fake ? context.getInt("dimension", 0) : context.onGameThread(() -> {
            EntityPlayerMP player = requirePlayer(playerName);
            return player.dimension;
        });

        return ServerChunkTools.withChunks(context, dimension,
            Collections.singleton(ChunkKeys.ofBlock(pos.getX(), pos.getZ())), outcome -> {
                final UndoPoints.Recorder recorder = ServerUndoTools.start(context, true);
                JsonObject json = context.onGameThread(() -> {
                    EntityPlayerMP player = fake ? fakeClicker(ServerWorldTools.requireWorld(dimension), pos, face)
                        : requirePlayer(playerName);
                    WorldServer world = player.getServerWorld();
                    MinecraftServer server = ServerThreadBridge.server();
                    if (!world.isBlockLoaded(pos)) {
                        throw new IllegalStateException("That block's chunk is not loaded. Pass load: true, or hold it "
                            + "with server_keep_loaded.");
                    }
                    String refusal = refusalAt(server, world, player, pos, face);
                    if (refusal != null) {
                        throw new IllegalStateException(refusal);
                    }

                    JsonObject result = new JsonObject();
                    result.addProperty("player", player.getName());
                    if (fake) {
                        result.addProperty("fakePlayer", true);
                    }
                    result.addProperty("distance", Math.round(Math.sqrt(player.getDistanceSq(pos)) * 10.0D) / 10.0D);
                    result.add("before", GameJson.block(world, pos));
                    BlockPos beside = pos.offset(face);
                    UndoPoints.Before wasAt = recorder == null ? null : recorder.capture(world, pos);
                    UndoPoints.Before wasBeside = recorder == null ? null : recorder.capture(world, beside);
                    Container before = player.openContainer;

                    int previousSlot = player.inventory.currentItem;
                    boolean previousSneak = player.isSneaking();
                    EnumActionResult outcomeResult;
                    ItemStack setAside = ItemStack.EMPTY;
                    try {
                        if (withItem && slot >= 0) {
                            player.inventory.currentItem = slot;
                        }
                        if (!withItem) {
                            // A block's onBlockActivated reads the player's hand, not the stack passed in, so
                            // an empty-hand press selects an empty slot (#87). Only the selection moves: an
                            // item the block hands over lands in that slot and stays. With none empty, the
                            // selected stack is set aside for the click and put back (#174): a held tool is
                            // exactly what some blocks refuse (CSM's buttons ignore their link tool).
                            int empty = emptyHotbarSlot(player);
                            if (empty >= 0) {
                                player.inventory.currentItem = empty;
                            }
                            else {
                                setAside = player.inventory.getStackInSlot(player.inventory.currentItem);
                                player.inventory.setInventorySlotContents(player.inventory.currentItem, ItemStack.EMPTY);
                                result.addProperty("setAside", String.valueOf(setAside.getItem().getRegistryName()));
                            }
                            result.addProperty("handEmpty", player.getHeldItemMainhand().isEmpty());
                        }
                        player.setSneaking(sneak);
                        ItemStack stack = withItem ? player.getHeldItem(EnumHand.MAIN_HAND) : ItemStack.EMPTY;
                        if (withItem && stack.isEmpty()) {
                            throw new IllegalStateException("Hotbar slot " + player.inventory.currentItem
                                + " is empty. Put the item in the player's hotbar first, or use "
                                + "server_activate_block to press the block with an empty hand.");
                        }
                        ItemStack inHand = player.getHeldItemMainhand();
                        result.addProperty("item", inHand.isEmpty() ? "empty hand" : String.valueOf(inHand.getItem().getRegistryName()));
                        if (withItem) {
                            ClickWatch.Seen seen;
                            ClickWatch.begin(player, pos);
                            try {
                                outcomeResult = player.interactionManager.processRightClickBlock(player, world, stack,
                                    EnumHand.MAIN_HAND, pos, face, hitX, hitY, hitZ);
                            }
                            finally {
                                seen = ClickWatch.end();
                            }
                            if (outcomeResult != EnumActionResult.SUCCESS) {
                                String why = seen == null ? null : seen.why(player.isSneaking());
                                if (why != null) {
                                    result.addProperty("why", why);
                                }
                            }
                        }
                        else {
                            outcomeResult = activate(player, world, pos, face, hitX, hitY, hitZ, result);
                        }
                        if (withItem) {
                            ItemStack after = player.getHeldItem(EnumHand.MAIN_HAND);
                            JsonObject held = new JsonObject();
                            held.addProperty("item", after.isEmpty() ? "empty" : String.valueOf(after.getItem().getRegistryName()));
                            held.addProperty("count", after.getCount());
                            result.add("heldAfter", held);
                        }
                    }
                    finally {
                        if (!setAside.isEmpty()) {
                            putBack(player, setAside, result);
                        }
                        player.inventory.currentItem = previousSlot;
                        player.setSneaking(previousSneak);
                    }
                    // Whether anything visible changed, beside 'activated': a button's press or a
                    // controller's request often changes no block state.
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

    /**
     * An empty-hand right-click, step for step as {@code processRightClickBlock} makes one, with each
     * step's outcome read rather than folded into one result (#174). Vanilla answers PASS for an empty
     * hand even when the block reacted, so its result said nothing; this reports 'activated', the
     * block's own answer, and 'why' when it was not asked or said no. Game thread.
     *
     * <p>One difference, on purpose: the event's hit point is where the click lands, not a ray traced
     * from the player's eyes, which from 300 blocks away hits something else entirely.
     */
    private static EnumActionResult activate(EntityPlayerMP player, WorldServer world, BlockPos pos, EnumFacing face,
                                             float hitX, float hitY, float hitZ, JsonObject result) {
        if (player.interactionManager.getGameType() == net.minecraft.world.GameType.SPECTATOR) {
            // Spectators only open containers; vanilla's own path does that.
            EnumActionResult spectating = player.interactionManager.processRightClickBlock(player, world,
                ItemStack.EMPTY, EnumHand.MAIN_HAND, pos, face, hitX, hitY, hitZ);
            result.addProperty("activated", spectating == EnumActionResult.SUCCESS);
            if (spectating != EnumActionResult.SUCCESS) {
                result.addProperty("why", "The player is in spectator mode, which opens containers and "
                    + "activates nothing else.");
            }
            return spectating;
        }
        PlayerInteractEvent.RightClickBlock event = ForgeHooks.onRightClickBlock(player, EnumHand.MAIN_HAND, pos, face,
            new net.minecraft.util.math.Vec3d(pos.getX() + hitX, pos.getY() + hitY, pos.getZ() + hitZ));
        if (event.isCanceled()) {
            result.addProperty("activated", false);
            result.addProperty("why", "A PlayerInteractEvent.RightClickBlock listener cancelled the click before "
                + "the block saw it: usually a protection, claims or permissions mod refusing this player here.");
            return event.getCancellationResult();
        }
        boolean bypass = player.getHeldItemMainhand().doesSneakBypassUse(world, pos, player)
            && player.getHeldItemOffhand().doesSneakBypassUse(world, pos, player);
        if (player.isSneaking() && !bypass && event.getUseBlock() != Event.Result.ALLOW) {
            result.addProperty("activated", false);
            result.addProperty("why", "The click was made sneaking, which skips the block's own handler. Leave "
                + "'sneak' off to press it.");
            return EnumActionResult.PASS;
        }
        if (event.getUseBlock() == Event.Result.DENY) {
            result.addProperty("activated", false);
            result.addProperty("why", "A RightClickBlock listener denied using the block (useBlock DENY): usually "
                + "a protection or claims mod.");
            return EnumActionResult.PASS;
        }
        IBlockState state = world.getBlockState(pos);
        boolean activated = state.getBlock().onBlockActivated(world, pos, state, player, EnumHand.MAIN_HAND, face,
            hitX, hitY, hitZ);
        result.addProperty("activated", activated);
        if (!activated) {
            result.addProperty("why", "The block's own onBlockActivated returned false: it has no right-click "
                + "action, or refused this click (the face, the hit point, the player or what they hold).");
        }
        return activated ? EnumActionResult.SUCCESS : EnumActionResult.PASS;
    }

    /**
     * Puts a stack set aside for an empty-hand click back in its slot, or, when the block put something
     * there, wherever it fits, and on the ground at the player's feet when nothing fits. Game thread.
     */
    private static void putBack(EntityPlayerMP player, ItemStack stack, JsonObject result) {
        int slot = player.inventory.currentItem;
        if (player.inventory.getStackInSlot(slot).isEmpty()) {
            player.inventory.setInventorySlotContents(slot, stack);
            return;
        }
        if (!player.inventory.addItemStackToInventory(stack) || !stack.isEmpty()) {
            if (!stack.isEmpty()) {
                player.dropItem(stack, false);
                result.addProperty("setAsideDropped", true);
            }
        }
        result.addProperty("setAsideMoved", "the block put an item in the hand's slot, so the set-aside stack "
            + "went elsewhere in the inventory");
    }

    /**
     * Records what {@code PlayerInteractEvent.RightClickBlock} listeners decided for an item use MCMCP
     * makes, so a PASS or FAIL can say why (#174). Only the click in progress is recorded. Game thread.
     */
    static final class ClickWatch {

        /** What the listeners left on the event. */
        static final class Seen {
            boolean fired;
            boolean cancelled;
            Event.Result useBlock = Event.Result.DEFAULT;
            Event.Result useItem = Event.Result.DEFAULT;

            /** A sentence for an unsuccessful click, or null when the listeners are not why. */
            @javax.annotation.Nullable
            String why(boolean sneaking) {
                if (!fired) {
                    return null;
                }
                if (cancelled) {
                    return "A PlayerInteractEvent.RightClickBlock listener cancelled the click: usually a "
                        + "protection, claims or permissions mod refusing this player here.";
                }
                if (useBlock == Event.Result.DENY && useItem == Event.Result.DENY) {
                    return "A RightClickBlock listener denied both the block and the item.";
                }
                if (useItem == Event.Result.DENY) {
                    return "A RightClickBlock listener denied using the item (useItem DENY).";
                }
                if (sneaking) {
                    return "The click was made sneaking, which skips the block's own handler; the item had "
                        + "nothing to do on this block.";
                }
                return "Neither the block nor the item did anything with this click: the block has no "
                    + "right-click action for it, and the item cannot be used here.";
            }
        }

        private static EntityPlayerMP player;
        private static BlockPos pos;
        private static Seen seen;
        private static boolean registered;

        static synchronized void register() {
            if (!registered) {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ClickWatch());
                registered = true;
            }
        }

        static void begin(EntityPlayerMP who, BlockPos where) {
            player = who;
            pos = where;
            seen = new Seen();
        }

        static Seen end() {
            Seen result = seen;
            player = null;
            pos = null;
            seen = null;
            return result;
        }

        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent(
            priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST, receiveCanceled = true)
        public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            Seen current = seen;
            if (current == null || event.getEntityPlayer() != player || !event.getPos().equals(pos)) {
                return;
            }
            current.fired = true;
            current.cancelled = event.isCanceled();
            current.useBlock = event.getUseBlock();
            current.useItem = event.getUseItem();
        }
    }

    /** The selected hotbar slot if it is empty, else the first empty one, else -1. Game thread. */
    private static int emptyHotbarSlot(EntityPlayerMP player) {
        if (player.inventory.getStackInSlot(player.inventory.currentItem).isEmpty()) {
            return player.inventory.currentItem;
        }
        for (int i = 0; i < 9; i++) {
            if (player.inventory.getStackInSlot(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * What {@code processTryUseItemOnBlock} checks before the right-click, less reach; null if the click
     * may go ahead. Game thread.
     */
    @javax.annotation.Nullable
    static String refusalAt(MinecraftServer server, WorldServer world, EntityPlayerMP player, BlockPos pos,
                            EnumFacing face) {
        if (!world.isBlockLoaded(pos)) {
            return "That block's chunk is not loaded. Pass load: true, or hold it with server_keep_loaded.";
        }
        int buildLimit = server.getBuildLimit();
        if (!(pos.getY() < buildLimit - 1 || face != EnumFacing.UP && pos.getY() < buildLimit)) {
            return "That is above the build limit.";
        }
        if (server.isBlockProtected(world, pos, player)) {
            return "That block is inside spawn protection, which this player may not change.";
        }
        if (!world.getWorldBorder().contains(pos)) {
            return "That block is outside the world border.";
        }
        return null;
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

    /** MCMCP's fake player, so that a mod that treats fake players differently is not mistaken for broken. */
    private static final GameProfile FAKE_PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("mcmcp:fake-player".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "[MCMCP]");

    /**
     * The fake player, standing a block out from the clicked face and looking at it, with nothing in
     * hand. Game thread.
     */
    private static EntityPlayerMP fakeClicker(WorldServer world, BlockPos pos, EnumFacing face) {
        EntityPlayerMP player = FakePlayerFactory.get(world, FAKE_PROFILE);
        double x = pos.getX() + 0.5D + face.getXOffset() * 1.5D;
        double y = pos.getY() + 0.5D + face.getYOffset() * 1.5D - player.getEyeHeight();
        double z = pos.getZ() + 0.5D + face.getZOffset() * 1.5D;
        float yaw = face.getAxis() == EnumFacing.Axis.Y ? 0.0F : face.getOpposite().getHorizontalAngle();
        float pitch = face == EnumFacing.UP ? 90.0F : face == EnumFacing.DOWN ? -90.0F : 0.0F;
        player.setLocationAndAngles(x, y, z, yaw, pitch);
        player.inventory.currentItem = 0;
        player.inventory.clear();
        return player;
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
