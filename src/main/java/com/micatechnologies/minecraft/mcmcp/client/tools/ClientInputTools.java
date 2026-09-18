package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.client.ClientFrameClock;
import com.micatechnologies.minecraft.mcmcp.client.ClientInputLock;
import com.micatechnologies.minecraft.mcmcp.client.ClientInputScheduler;
import com.micatechnologies.minecraft.mcmcp.client.WindowFocus;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.event.ForgeEventFactory;
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
        ClientInputLock.register();
        ClientFrameClock.register();
        registerSendChat();
        registerLook();
        registerMove();
        registerKeyPress();
        registerInteract();
        registerSelectHotbarSlot();
        registerInputLock();
    }

    /** The lock's state, in the shape every path here reports it. */
    static JsonObject lockStatus() {
        JsonObject json = new JsonObject();
        json.addProperty("locked", ClientInputLock.isLocked());
        json.addProperty("secondsRemaining", ClientInputLock.secondsRemaining());
        String why = ClientInputLock.reason();
        if (!why.isEmpty()) {
            json.addProperty("reason", why);
        }
        return json;
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

    /** The names {@link #bindings()} answers to, in the order the schemas list them. */
    private static final String[] KEY_NAMES = {
        "forward", "back", "left", "right", "jump", "sneak", "sprint",
        "attack", "use", "drop", "inventory", "pickBlock", "swapHands"};

    @Nullable
    private static KeyBinding binding(String name) {
        return bindings().get(name);
    }

    /**
     * Bindings the game reads as held state when it updates the player: movement, jump, sneak,
     * sprint. Every other binding is an action handled as a click.
     */
    private static final Set<String> STATE_KEYS = new HashSet<>(Arrays.asList(
        "forward", "back", "left", "right", "jump", "sneak", "sprint"));

    /**
     * How many ticks a combination's state keys go down ahead of its actions.
     *
     * <p>{@code Minecraft.runTick} handles clicks before it updates the player, and the player update
     * is where sneak and sprint are read and sent to the server. Pressed in the same tick, sneak and
     * use right-click as a player who is not sneaking yet. One tick of lead is enough for both the
     * client and the server's packet order to see the modifier first.
     */
    static int comboLeadTicks(List<String> keys) {
        boolean hasState = false;
        boolean hasAction = false;
        for (String key : keys) {
            if (STATE_KEYS.contains(key)) {
                hasState = true;
            }
            else {
                hasAction = true;
            }
        }
        return hasState && hasAction ? 1 : 0;
    }

    /**
     * Holds several named bindings as one combination. State keys lead by {@link #comboLeadTicks}
     * and are held until the actions release, so each action spends its whole hold modified.
     *
     * <p>Must run on the client thread. The future completes when the last key is released.
     */
    private static CompletableFuture<Void> holdTogether(List<String> keys, int ticks) {
        int lead = comboLeadTicks(keys);
        List<CompletableFuture<Void>> holds = new ArrayList<>(keys.size());
        for (String key : keys) {
            KeyBinding target = binding(key);
            if (target == null) {
                throw new IllegalArgumentException("Unknown key '" + key + "'");
            }
            holds.add(STATE_KEYS.contains(key)
                ? ClientInputScheduler.hold(target, 0, ticks + lead)
                : ClientInputScheduler.hold(target, lead, ticks));
        }
        return CompletableFuture.allOf(holds.toArray(new CompletableFuture[0]));
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
     * any server-side install. It reproduces {@code GuiScreen.sendChatMessage} step for step — the
     * Forge {@code ClientChatEvent}, the sent-message history, the client command handler, and only
     * then the packet — so a command runs at the player's own permission level on whatever server
     * they are on, and nothing here can elevate that.
     *
     * <p>{@code EntityPlayerSP.sendChatMessage} on its own is <em>not</em> that path, and using it was
     * a bug: it does nothing but send the packet. Client-side commands — anything registered with
     * Forge's {@code ClientCommandHandler}, such as MalisisCore's {@code /malisis} — never ran. They
     * went to the server instead and came back as "Unknown command", which reads exactly like the mod
     * having failed to register its command. Mods that rewrite or cancel chat through
     * {@code ClientChatEvent} were bypassed for the same reason.
     *
     * <p>Output does not come back in the tool result: the server replies asynchronously into the
     * chat window, and there is no request/response pairing to hook. Read it with
     * {@code client_read_chat} afterwards. A command handled on the client produces no server reply at
     * all, which is why the result reports which of the two happened.
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

                Boolean handledOnClient = context.onGameThread(new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        // Mirrors GuiScreen.sendChatMessage(msg, true), which is what pressing Enter in
                        // the chat box runs. Each step matters: the event lets mods rewrite or cancel
                        // the message, the history makes it reachable with the up arrow, and the client
                        // command handler is the only place a client-side command ever runs.
                        String outgoing = ForgeEventFactory.onClientSendMessage(message);
                        if (outgoing.isEmpty()) {
                            return Boolean.TRUE;
                        }
                        mc.ingameGUI.getChatGUI().addToSentMessages(outgoing);
                        if (ClientCommandHandler.instance.executeCommand(mc.player, outgoing) != 0) {
                            return Boolean.TRUE;
                        }
                        mc.player.sendChatMessage(outgoing);
                        return Boolean.FALSE;
                    }
                });

                boolean clientSide = Boolean.TRUE.equals(handledOnClient);
                JsonObject json = new JsonObject();
                json.addProperty("message", message);
                json.addProperty("isCommand", isCommand);
                json.addProperty("handledOnClient", clientSide);
                return ToolResult.text((isCommand ? "Command sent: " : "Message sent: ") + message
                    + (clientSide
                        ? "\nHandled on the client, so no server reply will arrive. Any output went "
                            + "straight to the chat window; read it with client_read_chat."
                        : "\nUse client_read_chat to see the response."))
                    .withStructured(json);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    /**
     * What {@code client_look} lets the game do before reading the rotation back: two frames, because
     * the mouse turns the camera per frame, and one tick, because a mount clamps its rider and a
     * server corrects a player per tick. Measured against a live client at 120 frames a second this
     * is about 40ms; the fixed 100ms sleep it replaced waited longer than that and, on a client
     * drawing eight frames a second, could still have returned before one of them.
     */
    private static final int LOOK_SETTLE_FRAMES = 2;
    private static final int LOOK_SETTLE_TICKS = 1;

    /** Longest the settle is waited for. Past it the game is hitching, and the read goes ahead. */
    private static final long LOOK_SETTLE_TIMEOUT_MILLIS = 500L;

    /**
     * How far the camera may be from where it was put and still count as having stayed there. Wide
     * enough to ignore float noise, and far narrower than anything that changes what is on screen.
     */
    private static final float LOOK_HELD_TOLERANCE_DEGREES = 1.0F;

    private static void registerLook() {
        McpRegistry.registerTool(McpTool.named("client_look")
            .title("Look")
            .description("Turn the player's camera. Set yaw and pitch directly, turn by a relative "
                + "amount, or aim at a world position — which is usually what you want, since it "
                + "computes the angles for you.\n\n"
                + "Yaw is degrees clockwise from south: 0 faces south (+Z), 90 faces west (-X), 180 "
                + "faces north (-Z), 270 faces east (+X). Pitch is degrees down from horizontal, from "
                + "-90 (straight up) to 90 (straight down).\n\n"
                + "The reply is the rotation read back a moment after the turn, with yaw as "
                + "-180 to 180, and is an error if something turned the camera away again.")
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

                final float[] applied = context.onGameThread(new Callable<float[]>() {
                    @Override
                    public float[] call() {
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
                        yaw = MathHelper.wrapDegrees(yaw);

                        mc.player.rotationYaw = yaw;
                        mc.player.rotationPitch = pitch;
                        // Setting the previous rotation too suppresses the interpolated camera swing
                        // that would otherwise be rendered between the old and new angles.
                        mc.player.prevRotationYaw = yaw;
                        mc.player.prevRotationPitch = pitch;
                        mc.player.rotationYawHead = yaw;
                        return new float[]{yaw, pitch};
                    }
                });

                // Read it back after the game has had a few frames with it, and report that —
                // not what was written. Writing the rotation always succeeds; what goes wrong is
                // that something else turns the camera on the very next frame, and the reply used
                // to echo the requested angles regardless. Seen against a live client: mouse
                // movement reaching the window drove the pitch into its 90 degree clamp and spun the
                // yaw every frame, client_look answered "pitch 6.39" each time it was asked, and five
                // screenshots of the ground were taken before anybody thought to doubt it.
                //
                // On the worker, not the client thread, for the reason client_wait gives: a
                // scheduled task that waits stops the frames being waited for.
                final long settleStart = System.currentTimeMillis();
                ClientFrameClock.await(LOOK_SETTLE_FRAMES, LOOK_SETTLE_TICKS,
                    LOOK_SETTLE_TIMEOUT_MILLIS);
                final long settledAfter = System.currentTimeMillis() - settleStart;

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        float yaw = MathHelper.wrapDegrees(mc.player.rotationYaw);
                        float pitch = mc.player.rotationPitch;

                        JsonObject json = new JsonObject();
                        json.addProperty("yaw", Math.round(yaw * 100.0D) / 100.0D);
                        json.addProperty("pitch", Math.round(pitch * 100.0D) / 100.0D);
                        json.add("lookingAt", ClientStateTools.freshLookTarget(mc));

                        float yawDrift = Math.abs(MathHelper
                            .wrapDegrees(yaw - applied[0]));
                        float pitchDrift = Math.abs(pitch - applied[1]);
                        if (yawDrift > LOOK_HELD_TOLERANCE_DEGREES
                            || pitchDrift > LOOK_HELD_TOLERANCE_DEGREES) {
                            json.addProperty("held", false);
                            json.addProperty("riding", mc.player.isRiding());
                            json.addProperty("windowFocused",
                                WindowFocus.isFocused());
                            json.addProperty("inputLocked", ClientInputLock.isLocked());
                        }
                        return json;
                    }
                });

                if (result.has("held")) {
                    return ToolResult.error("The camera did not stay where it was put. client_look "
                        + "set yaw " + Math.round(applied[0] * 100.0D) / 100.0D + ", pitch "
                        + Math.round(applied[1] * 100.0D) / 100.0D + "; " + settledAfter
                        + "ms later the player is at yaw " + result.get("yaw").getAsDouble()
                        + ", pitch " + result.get("pitch").getAsDouble() + " (windowFocused="
                        + result.get("windowFocused").getAsBoolean() + ", inputLocked="
                        + result.get("inputLocked").getAsBoolean() + "). A screenshot taken now "
                        + "shows that view, not the one asked for. "
                        + (result.get("riding").getAsBoolean()
                            ? "The player is riding something, and a vehicle or mount limits how far "
                                + "its rider can turn — a boat allows 105 degrees either side of its "
                                + "own heading. Turn the vehicle, or dismount with client_key sneak."
                            : result.get("inputLocked").getAsBoolean()
                            ? "The input lock is held, so this is not the mouse: something in the "
                                + "game is setting the rotation, most likely the server correcting "
                                + "the player."
                            : "Something is turning the camera between frames, almost always mouse "
                                + "movement reaching this window, and looking again will not help "
                                + "while it is. Take client_input_lock, which keeps the mouse out, "
                                + "then call client_look again."));
                }
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
            .description("Hold the game's named keybindings down for a number of ticks. This is "
                + "the general-purpose input tool; client_move and client_interact are more convenient "
                + "wrappers over the common cases.\n\n"
                + "Pass 'keys' to press a combination, such as sneak + use or sprint + jump + forward. "
                + "Movement, jump, sneak and sprint go down one tick before any action key, so the "
                + "action happens with them already in effect.\n\n"
                + "Only the named bindings listed in the schema can be driven — arbitrary key codes "
                + "are deliberately not accepted, since they could hit any key another mod has bound.")
            .schema(JsonSchema.object()
                .enumeration("key", "Which keybinding to press.", KEY_NAMES)
                .array("keys", "Several keybindings to hold together, instead of 'key'.",
                    JsonSchema.enumItems(KEY_NAMES))
                .integer("ticks", "How long to hold them, in ticks (20 per second).", 1, 1200)
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Player control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                // Both spellings are merged rather than one rejected: a model that sends key and keys
                // together means all of them.
                Set<String> requested = new LinkedHashSet<>();
                String single = context.getString("key", null);
                if (single != null) {
                    requested.add(single);
                }
                requested.addAll(Json.getStringList(context.getArguments(), "keys"));
                if (requested.isEmpty()) {
                    return ToolResult.error("Name a keybinding with 'key', or several with 'keys'.");
                }
                // Checked before anything is pressed, so a bad name in a combination cannot leave
                // the valid half of it held with nobody waiting on the release.
                for (String name : requested) {
                    if (!Arrays.asList(KEY_NAMES).contains(name)) {
                        return ToolResult.error("Unknown key '" + name + "'. Use one of: "
                            + String.join(", ", KEY_NAMES) + ".");
                    }
                }
                final List<String> keys = new ArrayList<>(requested);
                final int ticks = context.getBoundedInt("ticks", 1, 1, McmcpConfig.getMaxInputTicks());

                CompletableFuture<Void> hold = context.onGameThread(new Callable<CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> call() {
                        ClientStateTools.requireInWorld();
                        return holdTogether(keys, ticks);
                    }
                });

                awaitHold(hold, ticks + comboLeadTicks(keys));
                // Same focus gate client_interact explains: only continuous attack is affected, so
                // only attack is worth warning about. Everything else applies regardless.
                boolean unfocusedAttack = keys.contains("attack")
                    && !context.onGameThread(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            return Minecraft.getMinecraft().inGameHasFocus;
                        }
                    });
                return ToolResult.text("Held '" + String.join("' + '", keys) + "' for " + ticks + " tick(s)."
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
                .bool("sneak", "Sneak while clicking. With 'use', this places a block or uses the "
                    + "held item against a block that would otherwise open or activate.")
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
                final boolean sneak = context.getBoolean("sneak", false);
                final String keyName = "attack".equals(action) ? "attack" : "use";

                final JsonObject targetBefore = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        // Brief, and this is the call where it counts most: client_interact reports
                        // a target on both sides of the action, so the full form was paid for twice
                        // in one reply. "Did what I was aiming at change" is answered by the id and
                        // the position, which is all either half of the comparison needs.
                        return GameJson.rayTraceBrief(mc.world, mc.objectMouseOver);
                    }
                });

                final List<String> keys = sneak ? Arrays.asList("sneak", keyName) : Arrays.asList(keyName);
                CompletableFuture<Void> hold = context.onGameThread(new Callable<CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> call() {
                        return holdTogether(keys, ticks);
                    }
                });

                awaitHold(hold, ticks + comboLeadTicks(keys));

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        JsonObject json = new JsonObject();
                        json.addProperty("action", action);
                        json.addProperty("ticks", ticks);
                        if (sneak) {
                            json.addProperty("sneak", true);
                        }
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

    /**
     * Takes the human's keyboard and mouse out of the game while a model drives it.
     *
     * <p>Registered here rather than in a file of its own because it is the same subject as the rest
     * of this class from the model's side — one of these tools drives input, and this one decides
     * whether anything else is driving it at the same time.
     *
     * <p>Gated on {@code allowPlayerControl}. Somebody who turned off a model's ability to move
     * their player has certainly not agreed to it taking their mouse, and a second permission for
     * that would be a second thing to get wrong.
     *
     * <p>Not marked destructive: it changes nothing in the world and undoes itself. It is also not
     * idempotent — calling it again while locked extends the lock, which is the point.
     */
    private static void registerInputLock() {
        McpRegistry.registerTool(McpTool.named("client_input_lock")
            .title("Lock or unlock the player's own input")
            .description("Hold the human's keyboard and mouse out of the game so they cannot disturb "
                + "what you are doing. Use this before a sequence where a stray mouse movement would "
                + "break the plan — aiming at a block and then mining it, lining up a jump, reading "
                + "the crosshair target and acting on it — and unlock as soon as it is done.\n\n"
                + "While locked, nothing the person at the keyboard does reaches the game: no camera "
                + "movement, no clicks, no keys, no pause menu. Your own input tools are unaffected.\n\n"
                + "They can always take it back by pressing Escape twice, and it expires on its own, "
                + "so it is not a promise that you keep control — check 'locked' in the result of a "
                + "later call rather than assuming. Nothing tells you when a human releases it.\n\n"
                + "Lock for as little as the task needs. Somebody is sitting there unable to use their "
                + "computer, and a lock left on after you finish reads as a crash.")
            .schema(JsonSchema.object()
                .bool("locked", "True to take the keyboard and mouse, false to give them back.")
                .integer("seconds", "How long to hold them for, in seconds. Locking again before this "
                    + "runs out extends it. Ignored when releasing.", 1, 7200)
                .string("reason", "A short phrase shown on screen, so the person watching can tell a "
                    + "task in progress from a stuck agent. For example 'mining the iron vein'.")
                .required("locked")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("Player control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                if (!context.has("locked")) {
                    // Never defaulted. Guessing 'true' would take somebody's mouse on a malformed
                    // call, and guessing 'false' would report success for a lock that never happened.
                    return ToolResult.error("client_input_lock needs 'locked': true to take the "
                        + "keyboard and mouse, false to give them back.");
                }
                final boolean wanted = context.getBoolean("locked", false);
                final int seconds = context.getBoundedInt("seconds", 300, 1,
                    McmcpConfig.getMaxInputLockSeconds());
                final String why = context.getString("reason", "");

                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        // requireInWorld rather than a null check: locking at the main menu would
                        // hold the keyboard away from the only screen that can open a world, and
                        // there is nothing there worth protecting from a stray click anyway.
                        ClientStateTools.requireInWorld();

                        boolean was = ClientInputLock.isLocked();
                        if (wanted) {
                            ClientInputLock.lock(seconds, why);
                        }
                        else {
                            ClientInputLock.release("released by the model");
                        }

                        JsonObject json = lockStatus();
                        json.addProperty("wasLocked", was);
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
