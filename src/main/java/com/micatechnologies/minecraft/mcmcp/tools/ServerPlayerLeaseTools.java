package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.PlayerLeases;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * {@code server_player_lease} (#144): one agent borrows a shared player's hotbar and position, and
 * every other agent's item uses and teleports of that player wait for it.
 *
 * <p>The bookkeeping is {@link PlayerLeases}, which is pure. This adds the game's half: reading the
 * named slots when a lease is granted and putting them back when it ends, however it ends (released,
 * run out, taken over, its player leaving, or its agent's companion access ending).
 */
public final class ServerPlayerLeaseTools {

    static final int DEFAULT_LEASE_SECONDS = 300;
    static final int MAX_LEASE_SECONDS = 3600;
    static final int MAX_WAIT_SECONDS = 120;
    private static final long POLL_MILLIS = 500L;

    private ServerPlayerLeaseTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_player_lease")
            .title("Lease a shared player")
            .description("Borrow a player that several agents share, for work spanning calls such as linking "
                + "devices with an item held in a hotbar slot. While you hold the lease, other agents' "
                + "server_use_item_on_block(s), server_activate_block as that player, and server_teleport_player "
                + "of them are refused with who holds it. 'slots' (0-8 hotbar, 9-35 inventory, 40 offhand) are "
                + "recorded and put back when the lease ends, so the player's own items survive the borrowing. "
                + "op 'acquire' with waitSeconds queues you, first come first served; using the player renews "
                + "the lease. It ends on 'release', after leaseSeconds unused, or when the player leaves.")
            .schema(JsonSchema.object()
                .enumeration("op", "acquire (default), release, or status.", "acquire", "release", "status")
                .string("player", "Username of the player to lease.")
                .string("purpose", "acquire: what for, shown to agents that are refused.")
                .array("slots", "acquire: inventory slots to restore when the lease ends.", Json.parse("{\"type\":\"integer\",\"minimum\":0,\"maximum\":40}").getAsJsonObject())
                .integer("leaseSeconds", "acquire: how long unused before it ends. Default "
                    + DEFAULT_LEASE_SECONDS + ".", 30, MAX_LEASE_SECONDS)
                .integer("waitSeconds", "acquire: wait in line this long for another agent's lease to end. "
                    + "Default 0.", 0, MAX_WAIT_SECONDS)
                .bool("force", "acquire, release: end another agent's lease (its slots are restored first). Only "
                    + "for a lease you are sure is abandoned.")
                .required("player")
                .build())
            .serverOnly()
            .handler(ServerPlayerLeaseTools::lease)
            .build());
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    /** Restores what ran out, each second, and what a leaving player was lent. */
    public static final class Events {
        private int ticks;

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END && ++ticks % 20 == 0) {
                sweep(ServerThreadBridge.server());
            }
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.player instanceof EntityPlayerMP) {
                playerLeaving((EntityPlayerMP) event.player);
            }
        }
    }

    /**
     * The refusal an item-use or teleport tool returns when another agent leases {@code player}, or null
     * to go ahead. A use by the holder renews the lease.
     */
    @Nullable
    public static ToolResult refusal(ToolContext context, @Nullable String player) {
        if (player == null || player.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        String busy = PlayerLeases.get().refusalFor(player, context.callerKey(), now);
        if (busy == null) {
            return null;
        }
        JsonObject structured = new JsonObject();
        structured.add("busy", PlayerLeases.get().busy(player, now));
        return ToolResult.error(busy).withStructured(structured);
    }

    private static ToolResult lease(final ToolContext context) throws Exception {
        final String op = context.getString("op", "acquire");
        final String player = context.requireString("player");
        final String key = context.callerKey();
        final boolean force = context.getBoolean("force", false);
        if ("status".equals(op)) {
            return ToolResult.structured(context.onGameThread(() -> {
                sweep(ServerThreadBridge.server());
                return PlayerLeases.get().status(player, key, System.currentTimeMillis());
            }));
        }
        if ("release".equals(op)) {
            return ToolResult.structured(context.onGameThread(() -> {
                sweep(ServerThreadBridge.server());
                PlayerLeases.Lease ended = PlayerLeases.get().release(player, key, force);
                JsonObject json = new JsonObject();
                json.addProperty("released", ended != null);
                if (ended != null) {
                    json.add("restored", restore(ServerThreadBridge.server(), ended));
                }
                else {
                    json.addProperty("note", "You held no lease on " + player + ".");
                }
                return json;
            }));
        }
        if (!"acquire".equals(op)) {
            return ToolResult.error("'op' is acquire, release or status.");
        }
        final String purpose = context.getString("purpose", "");
        final long leaseMillis = Math.max(30, Math.min(MAX_LEASE_SECONDS,
            context.getInt("leaseSeconds", DEFAULT_LEASE_SECONDS))) * 1000L;
        final int waitSeconds = Math.max(0, Math.min(MAX_WAIT_SECONDS, context.getInt("waitSeconds", 0)));
        final int[] slots = slotsOf(context.getArguments());
        if (slots == null) {
            return ToolResult.error("'slots' are 0-35, or 40 for the offhand.");
        }
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (true) {
            Object outcome = context.onGameThread(() -> {
                MinecraftServer server = ServerThreadBridge.server();
                sweep(server);
                EntityPlayerMP target = server.getPlayerList().getPlayerByUsername(player);
                if (target == null) {
                    return "No player named " + player + " is online.";
                }
                if (force) {
                    PlayerLeases.Lease taken = PlayerLeases.get().release(player, key, true);
                    if (taken != null) {
                        restore(server, taken);
                    }
                }
                Map<Integer, String> saved = new LinkedHashMap<>();
                for (int slot : slots) {
                    saved.put(slot, target.inventory.getStackInSlot(slot).writeToNBT(new NBTTagCompound()).toString());
                }
                return PlayerLeases.get().acquire(target.getName(), key, context.callerLabel(), purpose, saved,
                    leaseMillis, System.currentTimeMillis());
            });
            if (outcome instanceof String) {
                return ToolResult.error((String) outcome);
            }
            PlayerLeases.Ask ask = (PlayerLeases.Ask) outcome;
            long now = System.currentTimeMillis();
            if (ask.granted != null) {
                JsonObject json = new JsonObject();
                json.addProperty("leased", true);
                json.addProperty("player", ask.granted.player);
                json.addProperty("secondsLeft", ask.granted.secondsLeft(now));
                json.addProperty("restoresSlots", ask.granted.slots.size());
                return ToolResult.structured(json);
            }
            if (now >= deadline) {
                // The caller keeps its place for a few seconds: one asking again, as bringPlayer does
                // each second with waitSeconds 0, stays in line. One that gave up drops out on its own.
                ToolResult refused = refusal(context, player);
                String why = refused != null ? "" : "The lease was free for a moment but another agent was ahead "
                    + "of you in line. ";
                JsonObject structured = new JsonObject();
                JsonObject busy = PlayerLeases.get().busy(player, now);
                busy.addProperty("placeInLine", ask.place);
                structured.add("busy", busy);
                return ToolResult.error(why + "Not leased after " + waitSeconds + " s: "
                    + (ask.heldBy == null ? "you were number " + ask.place + " in line."
                    : ask.heldBy.holderLabel + " holds " + player
                    + (ask.heldBy.purpose.isEmpty() ? "" : " (" + ask.heldBy.purpose + ")") + " for another "
                    + ask.heldBy.secondsLeft(now) + " s, and you were number " + ask.place + " in line.")
                    + " Ask again with waitSeconds to wait longer.").withStructured(structured);
            }
            context.awaitBudget(POLL_MILLIS, "waiting for " + player + "'s lease");
        }
    }

    /**
     * Ends the leases that ran out and puts their slots back. Server thread; called each second and
     * before every lease call, so an expired lease's slots are restored before anyone takes it next.
     */
    public static void sweep(@Nullable MinecraftServer server) {
        List<PlayerLeases.Lease> ended = PlayerLeases.get().expire(System.currentTimeMillis());
        for (PlayerLeases.Lease lease : ended) {
            restore(server, lease);
        }
    }

    /** Ends {@code player}'s lease as they log out, putting the slots back while they are still here. */
    public static void playerLeaving(EntityPlayerMP player) {
        PlayerLeases.Lease ended = PlayerLeases.get().releasePlayer(player.getName());
        if (ended != null) {
            restoreInto(player, ended);
        }
    }

    /** Ends every lease {@code holderKey} covers, on the server thread: its companion access ended. */
    public static void holderGoneLater(String holderKey) {
        com.micatechnologies.minecraft.mcmcp.game.ServerTaskPump.runLater(
            com.micatechnologies.minecraft.mcmcp.game.TaskCaller.MCMCP, () -> {
                MinecraftServer server = ServerThreadBridge.server();
                for (PlayerLeases.Lease lease : PlayerLeases.get().releaseHolder(holderKey)) {
                    restore(server, lease);
                }
            });
    }

    private static JsonArray restore(@Nullable MinecraftServer server, PlayerLeases.Lease lease) {
        if (server == null || lease.slots.isEmpty()) {
            return new JsonArray();
        }
        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(lease.player);
        return player == null ? new JsonArray() : restoreInto(player, lease);
    }

    /**
     * Puts the recorded slots back. What the agent left in a slot is not destroyed: it goes into a free
     * inventory slot, or is dropped at the player's feet when there is none.
     */
    private static JsonArray restoreInto(EntityPlayerMP player, PlayerLeases.Lease lease) {
        JsonArray restored = new JsonArray();
        for (Map.Entry<Integer, String> entry : lease.slots.entrySet()) {
            ItemStack saved;
            try {
                saved = new ItemStack(JsonToNBT.getTagFromJson(entry.getValue()));
            }
            catch (NBTException e) {
                continue;
            }
            int slot = entry.getKey();
            ItemStack current = player.inventory.getStackInSlot(slot);
            if (ItemStack.areItemStacksEqual(current, saved)) {
                continue;
            }
            player.inventory.setInventorySlotContents(slot, saved);
            JsonObject row = new JsonObject();
            row.addProperty("slot", slot);
            row.addProperty("item", saved.isEmpty() ? "empty" : String.valueOf(saved.getItem().getRegistryName()));
            if (!current.isEmpty()) {
                ItemStack moved = current.copy();
                if (!player.inventory.addItemStackToInventory(moved) && !moved.isEmpty()) {
                    player.dropItem(moved, false);
                    row.addProperty("displacedDropped", String.valueOf(current.getItem().getRegistryName()));
                }
                else {
                    row.addProperty("displacedMoved", String.valueOf(current.getItem().getRegistryName()));
                }
            }
            restored.add(row);
        }
        player.inventoryContainer.detectAndSendChanges();
        return restored;
    }

    /** The 'slots' argument, or null when one is out of range. */
    @Nullable
    private static int[] slotsOf(JsonObject arguments) {
        JsonArray raw = Json.getArray(arguments, "slots");
        if (raw == null) {
            return new int[0];
        }
        int[] slots = new int[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            JsonElement element = raw.get(i);
            int slot;
            try {
                slot = element.getAsInt();
            }
            catch (RuntimeException e) {
                return null;
            }
            if ((slot < 0 || slot > 35) && slot != 40) {
                return null;
            }
            slots[i] = slot;
        }
        return slots;
    }
}
