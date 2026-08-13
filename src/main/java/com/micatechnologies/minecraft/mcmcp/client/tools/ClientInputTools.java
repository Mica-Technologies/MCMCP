package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.client.ClientInputScheduler;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Driving the player: chat and commands, camera, movement, interaction and hotbar.
 *
 * <h2>Everything goes through the real input path</h2>
 *
 * These tools do not teleport the player, do not call {@code clickMouse} directly, and do not send
 * fabricated packets. They set keybinding state through {@link KeyBinding#setKeyBindState} and
 * {@link KeyBinding#onTick} — the same two methods the keyboard handler calls — and let the game's
 * own tick loop do the rest.
 *
 * <p>This is a design decision worth stating plainly, because the shortcut is tempting and wrong.
 * Going through the input path means synthetic actions are subject to every rule a human's are:
 * reach distance, block-break progress, item cooldowns, the movement checks on whatever server the
 * player is connected to. A model driving this cannot do anything the player could not do by hand,
 * which is the property that makes it safe to run the client endpoint on a server you do not own.
 *
 * <h2>Actions take time</h2>
 *
 * Every movement and interaction tool takes a duration in ticks and returns once that input has been
 * <em>applied</em> — not once the world has finished reacting to it. Walking forward for 20 ticks
 * returns after one second of held input; whether the player actually got anywhere depends on what
 * was in the way. Re-read state afterwards rather than assuming.
 */
@SideOnly(Side.CLIENT)
public final class ClientInputTools {

    private ClientInputTools() {
    }

    public static void register() {
        ClientInputScheduler.register();
        registerSendChat();
        registerLook();
        registerMove();
        registerKeyPress();
        registerInteract();
        registerSelectHotbarSlot();
    }

    /**
     * Named keybindings a model may drive, mapped to the game's settings.
     *
     * <p>An allow-list, not a passthrough to arbitrary LWJGL key codes. Two reasons: raw key codes
     * are meaningless to a model and it would guess wrong, and an arbitrary keypress can hit
     * anything another mod has bound — including keys with irreversible effects that have nothing to
     * do with playing the game.
     */
    private static Map<String, KeyBinding> bindings() {
        Minecraft mc = Minecraft.getMinecraft();
        Map<String, KeyBinding> map = new LinkedHashMap<>();
        map.put("forward", mc.gameSettings.keyBindForward);
        map.put("back", mc.gameSettings.keyBindBack);
        map.put("left", mc.gameSettings.keyBindLeft);
        map.put("right", mc.gameSettings.keyBindRight);
        map.put("jump", mc.gameSettings.keyBindJump);
        map.put("sneak", mc.gameSettings.keyBindSneak);
        map.put("sprint", mc.gameSettings.keyBindSprint);
        map.put("attack", mc.gameSettings.keyBindAttack);
        map.put("use", mc.gameSettings.keyBindUseItem);
        map.put("drop", mc.gameSettings.keyBindDrop);
        // "inventory" was wired to keyBindPickBlock, which is middle-click, not E. A model asking to
        // open the inventory got a pick-block on whatever it happened to be looking at instead —
        // silently wrong rather than an error, and in creative that also rewrites the hotbar slot.
        map.put("inventory", mc.gameSettings.keyBindInventory);
        map.put("pickBlock", mc.gameSettings.keyBindPickBlock);
        map.put("swapHands", mc.gameSettings.keyBindSwapHands);
        return map;
    }

    @Nullable
    private static KeyBinding binding(String name) {
        return bindings().get(name);
    }

    /**
     * Blocks until a scheduled hold releases.
     *
     * <p>The wait budget is the requested duration plus two seconds of slack: the client thread runs
     * at 20 ticks per second when healthy, but a chunk load or a GC pause makes ticks arbitrarily
     * long, and failing a tool because the game hitched would be wrong. The slack is bounded so a
     * genuinely stalled client still returns an error rather than holding an HTTP worker forever.
     */
    private static void awaitHold(CompletableFuture<Void> future, int ticks) throws Exception {
        future.get(ticks * 50L + 2000L, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // Chat and commands
    // ------------------------------------------------------------------

    /**
     * Sends chat or a command as the player.
     *
     * <p>The client-side counterpart to {@code server_run_command}, and the one that works without
     * any server-side install. It goes through {@code sendChatMessage}, which is byte-for-byte what
     * pressing T and typing produces — so a command runs at the player's own permission level on
     * whatever server they are on, and nothing here can elevate that.
     *
     * <p>Output does not come back in the tool result: the server replies asynchronously into the
     * chat window, and there is no request/response pairing to hook. Read it with
     * {@code client_read_chat} afterwards.
     */
    private static void registerSendChat() {
        McpRegistry.registerTool(McpTool.named("client_send_chat")
            .title("Send chat or command")
            .description("Send a chat message or run a command as the player, exactly as if typed into "
                + "the chat box. Prefix with '/' to run a command; it executes at the player's own "
                + "permission level on whatever server they are connected to.\n\n"
                + "The reply is not returned here — the server answers asynchronously into the chat "
                + "window. Call client_read_chat afterwards to read it.")
            .schema(JsonSchema.object()
                .string("message", "The message or command to send. Maximum 256 characters, which is "
                    + "the vanilla chat limit.")
                .required("message")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowChat()) {
                    return ToolResult.error("Chat is disabled by permissions.allowChat in the MCMCP "
                        + "config.");
                }
                final String message = context.requireString("message");
                final boolean isCommand = message.startsWith("/");

                if (isCommand && !McmcpConfig.isAllowCommands()) {
                    return ToolResult.error("Command execution is disabled by "
                        + "permissions.allowCommands in the MCMCP config.");
                }
                if (isCommand && McmcpConfig.isCommandBlocked(message)) {
                    return ToolResult.error("That command is on the blocked list in "
                        + "permissions.blockedCommands.");
                }
                if (message.length() > 256) {
                    return ToolResult.error("Chat messages are limited to 256 characters; that one is "
                        + message.length() + ". Longer messages are rejected by the server, not "
                        + "truncated.");
                }

                context.onGameThread(new Callable<Void>() {
                    @Override
                    public Void call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        mc.player.sendChatMessage(message);
                        return null;
                    }
                });
                return ToolResult.text((isCommand ? "Command sent: " : "Message sent: ") + message
                    + "\nUse client_read_chat to see the response.");
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    private static void registerLook() {
        McpRegistry.registerTool(McpTool.named("client_look")
            .title("Look")
            .description("Turn the player's camera. Set yaw and pitch directly, turn by a relative "
                + "amount, or aim at a world position — which is usually what you want, since it "
                + "computes the angles for you.\n\n"
                + "Yaw is degrees clockwise from south: 0 faces south (+Z), 90 faces west (-X), 180 "
                + "faces north (-Z), 270 faces east (+X). Pitch is degrees down from horizontal, from "
                + "-90 (straight up) to 90 (straight down).")
            .schema(JsonSchema.object()
                .number("yaw", "Absolute yaw in degrees.")
                .number("pitch", "Absolute pitch in degrees, -90 to 90.", -90.0D, 90.0D)
                .number("deltaYaw", "Turn right by this many degrees, relative to the current facing.")
                .number("deltaPitch", "Tilt down by this many degrees, relative to the current facing.")
                .number("lookAtX", "Aim at this world X coordinate.")
                .number("lookAtY", "Aim at this world Y coordinate.")
                .number("lookAtZ", "Aim at this world Z coordinate.")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Player control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        float yaw = mc.player.rotationYaw;
                        float pitch = mc.player.rotationPitch;

                        if (context.has("lookAtX") && context.has("lookAtY") && context.has("lookAtZ")) {
                            // Eye position, not feet: aiming from the player's origin points the
                            // camera roughly 1.6 blocks too low, which for a nearby target is the
                            // difference between hitting it and hitting the ground.
                            double dx = context.requireDouble("lookAtX") - mc.player.posX;
                            double dy = context.requireDouble("lookAtY")
                                - (mc.player.posY + mc.player.getEyeHeight());
                            double dz = context.requireDouble("lookAtZ") - mc.player.posZ;
                            double horizontal = Math.sqrt(dx * dx + dz * dz);
                            yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                            pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
                        }
                        else {
                            if (context.has("yaw")) {
                                yaw = (float) context.requireDouble("yaw");
                            }
                            if (context.has("pitch")) {
                                pitch = (float) context.requireDouble("pitch");
                            }
                            if (context.has("deltaYaw")) {
                                yaw += (float) context.requireDouble("deltaYaw");
                            }
                            if (context.has("deltaPitch")) {
                                pitch += (float) context.requireDouble("deltaPitch");
                            }
                        }

                        // The server rejects a pitch outside [-90, 90] outright, and an unwrapped yaw
                        // accumulates without bound across repeated relative turns.
                        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));
                        yaw = net.minecraft.util.math.MathHelper.wrapDegrees(yaw);

                        mc.player.rotationYaw = yaw;
                        mc.player.rotationPitch = pitch;
                        // Setting the previous rotation too suppresses the interpolated camera swing
                        // that would otherwise be rendered between the old and new angles.
                        mc.player.prevRotationYaw = yaw;
                        mc.player.prevRotationPitch = pitch;
                        mc.player.rotationYawHead = yaw;

                        JsonObject json = new JsonObject();
                        json.addProperty("yaw", Math.round(yaw * 100.0D) / 100.0D);
                        json.addProperty("pitch", Math.round(pitch * 100.0D) / 100.0D);
                        json.add("lookingAt", ClientStateTools.freshLookTarget(mc));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Movement
    // ------------------------------------------------------------------

    private static void registerMove() {
        McpRegistry.registerTool(McpTool.named("client_move")
            .title("Move")
            .description("Walk the player by holding a movement key for a number of ticks (20 ticks = "
                + "1 second). Direction is relative to where the camera is facing — use client_look "
                + "first to aim, then move forward.\n\n"
                + "Returns once the input has been applied, with the distance actually travelled. That "
                + "distance can be zero if something was in the way; check it rather than assuming the "
                + "player moved.")
            .schema(JsonSchema.object()
                .enumeration("direction", "Which way to walk, relative to the camera.",
                    "forward", "back", "left", "right")
                .integer("ticks", "How long to hold the key, in ticks. 20 ticks is one second, which "
                    + "is roughly 4.3 blocks of sprinting or 2.2 blocks of walking.", 1, 1200)
                .bool("sprint", "Hold sprint at the same time. Consumes hunger.")
                .bool("jump", "Jump once at the start of the movement, for climbing a single block.")
                .bool("sneak", "Hold sneak, which prevents walking off edges.")
                .required("direction", "ticks")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Player control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                final String direction = context.requireString("direction");
                final int ticks = context.getBoundedInt("ticks", 20, 1, McmcpConfig.getMaxInputTicks());
                final boolean sprint = context.getBoolean("sprint", false);
                final boolean jump = context.getBoolean("jump", false);
                final boolean sneak = context.getBoolean("sneak", false);

                final JsonObject before = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        return GameJson.vec(mc.player.posX, mc.player.posY, mc.player.posZ);
                    }
                });

                CompletableFuture<Void> hold = context.onGameThread(new Callable<CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> call() {
                        KeyBinding movement = binding(direction);
                        if (movement == null) {
                            throw new IllegalArgumentException("Unknown direction '" + direction + "'");
                        }
                        if (sprint) {
                            ClientInputScheduler.hold(binding("sprint"), ticks);
                        }
                        if (sneak) {
                            ClientInputScheduler.hold(binding("sneak"), ticks);
                        }
                        if (jump) {
                            // One tick is enough: the jump binding is edge-triggered, and holding it
                            // longer just means jumping again on landing.
                            ClientInputScheduler.hold(binding("jump"), 1);
                        }
                        return ClientInputScheduler.hold(movement, ticks);
                    }
                });

                awaitHold(hold, ticks);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        JsonObject after = GameJson.vec(mc.player.posX, mc.player.posY, mc.player.posZ);
                        double dx = after.get("x").getAsDouble() - before.get("x").getAsDouble();
                        double dy = after.get("y").getAsDouble() - before.get("y").getAsDouble();
                        double dz = after.get("z").getAsDouble() - before.get("z").getAsDouble();

                        JsonObject json = new JsonObject();
                        json.addProperty("direction", direction);
                        json.addProperty("ticks", ticks);
                        json.add("from", before);
                        json.add("to", after);
                        json.addProperty("distanceMoved",
                            Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 100.0D) / 100.0D);
                        json.addProperty("onGround", mc.player.onGround);
                        json.add("lookingAt", ClientStateTools.freshLookTarget(mc));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerKeyPress() {
        McpRegistry.registerTool(McpTool.named("client_key")
            .title("Press a key")
            .description("Hold one of the game's named keybindings down for a number of ticks. This is "
                + "the general-purpose input tool; client_move and client_interact are more convenient "
                + "wrappers over the common cases.\n\n"
                + "Only the named bindings listed in the schema can be driven — arbitrary key codes "
                + "are deliberately not accepted, since they could hit any key another mod has bound.")
            .schema(JsonSchema.object()
                .enumeration("key", "Which keybinding to press.",
                    "forward", "back", "left", "right", "jump", "sneak", "sprint",
                    "attack", "use", "drop", "inventory", "pickBlock", "swapHands")
                .integer("ticks", "How long to hold it, in ticks (20 per second).", 1, 1200)
                .required("key")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Player control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                final String key = context.requireString("key");
                final int ticks = context.getBoundedInt("ticks", 1, 1, McmcpConfig.getMaxInputTicks());

                CompletableFuture<Void> hold = context.onGameThread(new Callable<CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> call() {
                        ClientStateTools.requireInWorld();
                        KeyBinding target = binding(key);
                        if (target == null) {
                            throw new IllegalArgumentException("Unknown key '" + key + "'");
                        }
                        return ClientInputScheduler.hold(target, ticks);
                    }
                });

                awaitHold(hold, ticks);
                // Same focus gate client_interact explains: only continuous attack is affected, so
                // only attack is worth warning about. Everything else applies regardless.
                boolean unfocusedAttack = "attack".equals(key)
                    && !context.onGameThread(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            return Minecraft.getMinecraft().inGameHasFocus;
                        }
                    });
                return ToolResult.text("Held '" + key + "' for " + ticks + " tick(s)."
                    + (unfocusedAttack
                        ? " The game does not have input focus, so this did nothing beyond the first"
                            + " click — call client_view with grabInputFocus true first."
                        : ""));
            })
            .build());
    }

    private static void registerInteract() {
        McpRegistry.registerTool(McpTool.named("client_interact")
            .title("Attack or use")
            .description("Attack (left click) or use (right click) whatever the crosshair is pointing "
                + "at. Check client_looking_at first — this acts on the current target, and the "
                + "player's reach is about 4.5 blocks in survival.\n\n"
                + "Breaking a block takes many ticks of held attack and depends on the tool held; hold "
                + "for longer and re-check rather than expecting one call to finish the job.\n\n"
                + "Held attack additionally needs the game to have input focus, which an unattended "
                + "client does not have. Without it the input is applied and nothing happens; the "
                + "reply flags that rather than leaving you to conclude the block is unbreakable. "
                + "Fix it with client_view grabInputFocus, or client_gui_close.")
            .schema(JsonSchema.object()
                .enumeration("action", "'attack' is left click: hit an entity, or mine a block. "
                    + "'use' is right click: place a block, open a container, use an item.",
                    "attack", "use")
                .integer("ticks", "How long to hold the button. One tick is a single click; longer "
                    + "holds mine continuously.", 1, 1200)
                .required("action")
                .build())
            .clientOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Player control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                final String action = context.requireString("action");
                final int ticks = context.getBoundedInt("ticks", 1, 1, McmcpConfig.getMaxInputTicks());
                final String keyName = "attack".equals(action) ? "attack" : "use";

                final JsonObject targetBefore = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        return GameJson.rayTrace(mc.world, mc.objectMouseOver);
                    }
                });

                CompletableFuture<Void> hold = context.onGameThread(new Callable<CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> call() {
                        return ClientInputScheduler.hold(binding(keyName), ticks);
                    }
                });

                awaitHold(hold, ticks);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        JsonObject json = new JsonObject();
                        json.addProperty("action", action);
                        json.addProperty("ticks", ticks);
                        json.add("targetBefore", targetBefore);
                        json.add("targetAfter", ClientStateTools.freshLookTarget(mc));
                        json.add("mainHand", GameJson.itemStack(mc.player.getHeldItemMainhand()));
                        // Minecraft routes continuous left-click through a path gated on
                        // inGameHasFocus, so a held attack without focus is a no-op that reports
                        // success. Say so, or the only symptom is a block that never breaks.
                        if ("attack".equals(action) && !mc.inGameHasFocus) {
                            json.addProperty("inGameFocus", false);
                            json.addProperty("warning", "The game does not have input focus, so held "
                                + "attack did nothing beyond the first click. Call client_view with "
                                + "grabInputFocus true, then attack again.");
                        }
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    private static void registerSelectHotbarSlot() {
        McpRegistry.registerTool(McpTool.named("client_select_slot")
            .title("Select hotbar slot")
            .description("Select a hotbar slot, 0 to 8 left to right. Do this before using an item or "
                + "placing a block — client_interact acts with whatever is in the selected slot.")
            .schema(JsonSchema.object()
                .integer("slot", "Hotbar slot index, 0-8.", 0, 8)
                .required("slot")
                .build())
            .clientOnly()
            .idempotent()
            .handler(context -> {
                if (!McmcpConfig.isAllowInventoryChanges()) {
                    return ToolResult.error("Inventory changes are disabled by "
                        + "permissions.allowInventoryChanges in the MCMCP config.");
                }
                final int slot = context.getBoundedInt("slot", 0, 0, 8);

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        // PlayerControllerMP.syncCurrentPlayItem notices the change on the next tick
                        // and sends the held-item packet, so no packet is needed here.
                        mc.player.inventory.currentItem = slot;

                        JsonObject json = new JsonObject();
                        json.addProperty("selectedSlot", slot);
                        json.add("holding", GameJson.itemStack(mc.player.inventory.getStackInSlot(slot)));
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }
}
