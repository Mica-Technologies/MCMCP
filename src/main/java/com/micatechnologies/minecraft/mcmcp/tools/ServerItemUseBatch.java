package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;

/**
 * Many item uses in one call (#76): planting 454 street trees was 454 calls, plus an item swap per
 * species through the client.
 *
 * <p>Each use is the same right-click {@link ServerInteractTools} runs, as the named player and from
 * any distance. The item is either one the player already holds in a hotbar slot, or one this call
 * supplies. A supplied item is lent: it goes into the selected hotbar slot for the use and the slot
 * is put back exactly as it was afterwards, so the player's inventory never changes. One lent stack
 * lasts the whole call, so a linking tool that remembers its first click in its NBT still sees it on
 * the second; a stack used up is replaced with a fresh one.
 *
 * <p>Through the companion, lending an item the player does not own needs creative mode or operator
 * rights: otherwise a use grant would hand out any item in the game.
 */
public final class ServerItemUseBatch {

    private static final int MAX_USES = 1000;

    /** Uses per game-thread task, so a long call lets ticks run between slices. */
    private static final int USES_PER_TASK = 50;

    private ServerItemUseBatch() {
    }

    public static void register() {
        JsonObject nbt = new JsonObject();
        JsonArray nbtTypes = new JsonArray();
        nbtTypes.add("string");
        nbtTypes.add("object");
        nbt.add("type", nbtTypes);
        nbt.addProperty("description", "The item's NBT, as SNBT or a JSON object.");
        JsonObject itemSchema = JsonSchema.object()
            .string("id", "Item id, e.g. 'csm:tree_tool'.")
            .integer("meta", "Item metadata (damage). Default 0.", 0, 32767)
            .integer("count", "Stack size. Default 1.", 1, 64)
            .property("nbt", nbt)
            .required("id")
            .build();
        JsonObject useItemSchema = Json.obj("type", "object");
        useItemSchema.addProperty("description", "As 'item', for this click only.");
        McpRegistry.registerTool(McpTool.named("server_use_item_on_blocks")
            .title("Use an item on many blocks")
            .description("Use an item on many blocks in one call, in order, as that player and from any "
                + "distance: tree tools, linking tools, configuration tools. 'item' lends the player an item "
                + "for the call without touching their inventory, or 'slot' uses one already in their hotbar; "
                + "a use may give its own 'item'. The lent stack lasts the whole call, so a linking tool keeps "
                + "what its first click stored. Returns each use's result and, with readBack, the block after "
                + "it. Runs the game's own right-click path, so protection mods still decide.")
            .schema(JsonSchema.object()
                .string("player", "The player who clicks. Through the companion, always you.")
                .property("item", itemSchema)
                .integer("slot", "Use the item in this hotbar slot, 0-8, instead of lending one.", 0, 8)
                .array("uses", "The clicks, in order. At most " + MAX_USES + ".", JsonSchema.object()
                    .integer("x", "Block X.")
                    .integer("y", "Block Y.")
                    .integer("z", "Block Z.")
                    .enumeration("face", "The face clicked. Default 'up'.", "down", "up", "north", "south",
                        "west", "east")
                    .number("hitX", "Where on the face, 0-1. Default the face's centre.", 0.0D, 1.0D)
                    .number("hitY", "As hitX, in Y.", 0.0D, 1.0D)
                    .number("hitZ", "As hitX, in Z.", 0.0D, 1.0D)
                    .bool("sneak", "Click while sneaking.")
                    .property("item", useItemSchema)
                    .required("x", "y", "z")
                    .build())
                .bool("readBack", "Report the block at each position after its use.")
                .array("readBackKeys", "With readBack, also these tile-entity NBT fields (dot paths).",
                    Json.obj("type", "string"))
                .bool("stopOnError", "Stop at the first use that fails or is refused. Default false.")
                .bool("load", "Load the chunks the uses need for this call.")
                .required("player", "uses")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerItemUseBatch::run)
            .build());
    }

    /** One click, parsed. */
    private static final class Use {
        final BlockPos pos;
        final EnumFacing face;
        final float hitX;
        final float hitY;
        final float hitZ;
        final boolean sneak;
        /** Key into the lent stacks, or null to use the call's item or slot. */
        @Nullable
        final String itemKey;

        Use(BlockPos pos, EnumFacing face, float hitX, float hitY, float hitZ, boolean sneak, @Nullable String itemKey) {
            this.pos = pos;
            this.face = face;
            this.hitX = hitX;
            this.hitY = hitY;
            this.hitZ = hitZ;
            this.sneak = sneak;
            this.itemKey = itemKey;
        }
    }

    /** An item to lend, and the stack lent so far this call. */
    private static final class Lent {
        final String id;
        final Item item;
        final int meta;
        final int count;
        @Nullable
        final NBTTagCompound nbt;
        ItemStack stack = ItemStack.EMPTY;

        Lent(String id, Item item, int meta, int count, @Nullable NBTTagCompound nbt) {
            this.id = id;
            this.item = item;
            this.meta = meta;
            this.count = count;
            this.nbt = nbt;
        }

        ItemStack fresh() {
            ItemStack made = new ItemStack(item, count, meta);
            if (nbt != null) {
                made.setTagCompound(nbt.copy());
            }
            return made;
        }
    }

    private static ToolResult run(final ToolContext context) throws Exception {
        String denied = context.refusal(Capability.ITEM_USE, McmcpConfig.isAllowWorldEdits(),
            "Using items on blocks is disabled by permissions.allowWorldEdits in the MCMCP config.");
        if (denied != null) {
            return ToolResult.error(denied);
        }
        final String playerName = context.getString("player", null);
        if (playerName == null || playerName.isEmpty()) {
            return ToolResult.error("Name the 'player' who clicks.");
        }
        JsonArray rawUses = Json.getArray(context.getArguments(), "uses");
        if (rawUses == null || rawUses.size() == 0 || rawUses.size() > MAX_USES) {
            return ToolResult.error("'uses' takes 1 to " + MAX_USES + " clicks.");
        }
        final Map<String, Lent> lent = new HashMap<>();
        final String callItem;
        try {
            callItem = context.has("item") ? lend(lent, context.getArguments().get("item")) : null;
        }
        catch (IllegalArgumentException e) {
            return ToolResult.error("'item': " + e.getMessage());
        }
        final int slot = context.getInt("slot", -1);
        if (callItem != null && slot >= 0) {
            return ToolResult.error("Give 'item' to lend one, or 'slot' to use the player's own, not both.");
        }
        final List<Use> uses = new ArrayList<>();
        final Set<Long> chunks = new LinkedHashSet<>();
        for (int i = 0; i < rawUses.size(); i++) {
            JsonElement element = rawUses.get(i);
            if (!element.isJsonObject()) {
                return ToolResult.error("Use " + i + " is not an object {x, y, z}.");
            }
            JsonObject at = element.getAsJsonObject();
            if (!at.has("x") || !at.has("y") || !at.has("z")) {
                return ToolResult.error("Use " + i + " needs x, y and z.");
            }
            EnumFacing face = EnumFacing.byName(Json.getString(at, "face", "up").toLowerCase(Locale.ROOT));
            if (face == null) {
                return ToolResult.error("Use " + i + ": 'face' is one of down, up, north, south, west, east.");
            }
            String itemKey;
            try {
                itemKey = at.has("item") ? lend(lent, at.get("item")) : callItem;
            }
            catch (IllegalArgumentException e) {
                return ToolResult.error("Use " + i + " 'item': " + e.getMessage());
            }
            BlockPos pos = new BlockPos(Json.getInt(at, "x", 0), Json.getInt(at, "y", 0), Json.getInt(at, "z", 0));
            uses.add(new Use(pos, face,
                (float) Json.getDouble(at, "hitX", 0.5D + 0.5D * face.getXOffset()),
                (float) Json.getDouble(at, "hitY", 0.5D + 0.5D * face.getYOffset()),
                (float) Json.getDouble(at, "hitZ", 0.5D + 0.5D * face.getZOffset()),
                Json.getBoolean(at, "sneak", false), itemKey));
            chunks.add(ChunkKeys.ofBlock(pos.getX(), pos.getZ()));
        }
        final boolean readBack = context.getBoolean("readBack", false);
        final List<String> readBackKeys = Json.getStringList(context.getArguments(), "readBackKeys");
        final boolean stopOnError = context.getBoolean("stopOnError", false);
        final boolean companion = context.getPrincipal().isCompanion();

        final int dimension = context.onGameThread(() -> {
            EntityPlayerMP player = requirePlayer(playerName);
            if (companion && !lent.isEmpty() && !mayLend(player)) {
                throw new IllegalStateException("Lending an item needs creative mode or operator rights here; put "
                    + "the item in your hotbar and pass 'slot' instead.");
            }
            return player.dimension;
        });

        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final UndoPoints.Recorder recorder = ServerUndoTools.start(context, true);
            final JsonArray results = new JsonArray();
            final int[] counts = new int[4];  // succeeded, changed, failed, refused
            final boolean[] stopped = {false};
            for (int from = 0; from < uses.size() && !stopped[0]; from += USES_PER_TASK) {
                final int start = from;
                final int end = Math.min(uses.size(), from + USES_PER_TASK);
                context.onGameThread(() -> {
                    EntityPlayerMP player = requirePlayer(playerName);
                    WorldServer world = player.getServerWorld();
                    for (int i = start; i < end && !stopped[0]; i++) {
                        JsonObject result = useOnce(server(), world, player, uses.get(i), slot, lent, recorder,
                            readBack, readBackKeys);
                        result.addProperty("i", i);
                        results.add(result);
                        String state = result.get("result").getAsString();
                        if (result.has("refused")) {
                            counts[3]++;
                        }
                        else if ("success".equals(state)) {
                            counts[0]++;
                        }
                        else if ("fail".equals(state)) {
                            counts[2]++;
                        }
                        if (Json.getBoolean(result, "changed", false)) {
                            counts[1]++;
                        }
                        if (stopOnError && (result.has("refused") || "fail".equals(state))) {
                            stopped[0] = true;
                        }
                    }
                    return null;
                });
                if (uses.size() > USES_PER_TASK) {
                    context.reportProgress(end, uses.size(), "Used " + end + " of " + uses.size());
                }
            }
            JsonObject json = new JsonObject();
            json.addProperty("player", playerName);
            json.addProperty("attempted", uses.size());
            json.addProperty("ran", results.size());
            json.addProperty("succeeded", counts[0]);
            json.addProperty("changed", counts[1]);
            json.addProperty("failed", counts[2]);
            json.addProperty("refused", counts[3]);
            if (stopped[0]) {
                json.addProperty("stoppedAt", results.size() - 1);
            }
            json.add("uses", results);
            if (!lent.isEmpty()) {
                JsonArray after = new JsonArray();
                for (Lent item : lent.values()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("item", item.id);
                    entry.addProperty("count", item.stack.getCount());
                    after.add(entry);
                }
                json.add("lentAfter", after);
            }
            if (recorder != null) {
                ServerUndoTools.finish(context, recorder, "server_use_item_on_blocks", dimension, null, null, json, null);
            }
            return ToolResult.structured(json);
        });
    }

    /** Game thread: one click, with the lent item swapped in and the slot restored after. */
    private static JsonObject useOnce(MinecraftServer server, WorldServer world, EntityPlayerMP player, Use use,
                                      int slot, Map<String, Lent> lent, @Nullable UndoPoints.Recorder recorder,
                                      boolean readBack, List<String> readBackKeys) {
        JsonObject result = GameJson.blockPos(use.pos);
        String refusal = ServerInteractTools.refusalAt(server, world, player, use.pos, use.face);
        if (refusal != null) {
            result.addProperty("result", "refused");
            result.addProperty("refused", refusal);
            return result;
        }
        BlockPos beside = use.pos.offset(use.face);
        UndoPoints.Before wasAt = recorder == null ? null : recorder.capture(world, use.pos);
        UndoPoints.Before wasBeside = recorder == null ? null : recorder.capture(world, beside);
        net.minecraft.block.state.IBlockState before = world.getBlockState(use.pos);
        net.minecraft.block.state.IBlockState beforeBeside = world.getBlockState(beside);

        int previousSlot = player.inventory.currentItem;
        boolean previousSneak = player.isSneaking();
        Lent item = use.itemKey == null ? null : lent.get(use.itemKey);
        ItemStack owned = null;
        EnumActionResult outcome;
        try {
            if (slot >= 0) {
                player.inventory.currentItem = slot;
            }
            if (item != null) {
                owned = player.inventory.getStackInSlot(player.inventory.currentItem);
                if (item.stack.isEmpty()) {
                    item.stack = item.fresh();
                }
                player.inventory.setInventorySlotContents(player.inventory.currentItem, item.stack);
            }
            player.setSneaking(use.sneak);
            ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);
            if (stack.isEmpty()) {
                result.addProperty("result", "refused");
                result.addProperty("refused", "Hotbar slot " + player.inventory.currentItem + " is empty.");
                return result;
            }
            outcome = player.interactionManager.processRightClickBlock(player, world, stack, EnumHand.MAIN_HAND,
                use.pos, use.face, use.hitX, use.hitY, use.hitZ);
            if (item != null) {
                // The use may have replaced the stack (a bucket emptied) rather than changed it.
                item.stack = player.inventory.getStackInSlot(player.inventory.currentItem);
            }
        }
        finally {
            if (item != null) {
                player.inventory.setInventorySlotContents(player.inventory.currentItem,
                    owned == null ? ItemStack.EMPTY : owned);
            }
            player.inventory.currentItem = previousSlot;
            player.setSneaking(previousSneak);
        }
        result.addProperty("result", outcome.name().toLowerCase(Locale.ROOT));
        boolean changed = world.getBlockState(use.pos) != before || world.getBlockState(beside) != beforeBeside;
        result.addProperty("changed", changed);
        if (recorder != null && wasAt != null && world.getBlockState(use.pos) != wasAt.state) {
            recorder.record(use.pos, wasAt, world.getBlockState(use.pos));
        }
        if (recorder != null && wasBeside != null && world.getBlockState(beside) != wasBeside.state) {
            recorder.record(beside, wasBeside, world.getBlockState(beside));
        }
        if (readBack) {
            result.addProperty("after", ServerRegionTools.describe(net.minecraft.block.Block.getStateId(
                world.getBlockState(use.pos))));
            if (!readBackKeys.isEmpty()) {
                TileEntity tile = world.getTileEntity(use.pos);
                if (tile != null) {
                    JsonElement tag = NbtJson.toJson(tile.writeToNBT(new NBTTagCompound()));
                    if (tag.isJsonObject()) {
                        result.add("nbt", NbtProjection.project(tag.getAsJsonObject(), readBackKeys));
                    }
                }
            }
        }
        return result;
    }

    /** Adds an item to lend and returns its key; the same item given twice is one stack. */
    private static String lend(Map<String, Lent> lent, JsonElement spec) {
        if (spec == null || !spec.isJsonObject()) {
            throw new IllegalArgumentException("an item is an object {id, meta, count, nbt}.");
        }
        JsonObject object = spec.getAsJsonObject();
        String key = Json.write(object);
        if (lent.containsKey(key)) {
            return key;
        }
        String id = Json.getString(object, "id", null);
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("'id' is required.");
        }
        Item item = Item.getByNameOrId(id);
        if (item == null) {
            throw new IllegalArgumentException("no item '" + id + "' is registered.");
        }
        NBTTagCompound nbt = object.has("nbt") && !object.get("nbt").isJsonNull()
            ? TileEntityNbt.parse(object.get("nbt")) : null;
        lent.put(key, new Lent(id, item, Math.max(0, Json.getInt(object, "meta", 0)),
            Math.max(1, Math.min(64, Json.getInt(object, "count", 1))), nbt));
        return key;
    }

    private static boolean mayLend(EntityPlayerMP player) {
        MinecraftServer server = player.getServer();
        return player.interactionManager.getGameType() == GameType.CREATIVE
            || server != null && server.getPlayerList().canSendCommands(player.getGameProfile());
    }

    private static MinecraftServer server() {
        return ServerThreadBridge.server();
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
