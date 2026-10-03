package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.client.ClientChatRecorder;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.ChiselBlob;
import com.micatechnologies.minecraft.mcmcp.tools.ChiselNbt;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import com.micatechnologies.minecraft.mcmcp.tools.ServerBuildTools;
import com.micatechnologies.minecraft.mcmcp.tools.TileEntityNbt;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Commands longer than chat allows, by way of a command block (#42).
 *
 * <h2>Why a command block</h2>
 *
 * A remote server takes a chat line of at most 256 characters, and on a server the client is all an
 * agent has. Large tile-entity data — a Chisels &amp; Bits voxel blob, a list of fifty linked
 * positions — does not fit. A command block's command arrives by another packet: the one its edit
 * screen sends on Done, {@code MC|AutoCmd}, which carries up to 32,767 bytes. Sending that packet
 * directly, with "always active" set on an impulse block, has the server store the command and run
 * it on its next tick, without a screen ever opening.
 *
 * <h2>Reading the result</h2>
 *
 * The server does not send a command block's result to anyone: its update packet goes out only
 * when a player opens the block. So the result is read the way {@code client_get_block} reads any
 * server tag, with {@code /blockdata x y z {}}. To tell "ran and failed" from "not run yet",
 * {@code SuccessCount} is first set to -1, which no run leaves behind.
 *
 * <p>The server allows this to a player in creative mode with operator level 2, and only when
 * {@code enable-command-block} is on. It answers a refusal in chat, which is how it is reported.
 */
@SideOnly(Side.CLIENT)
public final class ClientCommandBlockTools {

    /** What a command block's screen allows; the packet itself allows a little more. */
    static final int MAX_COMMAND_CHARS = 32_000;

    /** The packet's own ceiling, checked against the command's UTF-8 size plus the other fields. */
    private static final int MAX_PAYLOAD_BYTES = 32_767;

    private static final long CHAT_REPLY_MILLIS = 2_000L;
    private static final long QUIET_MILLIS = 60L;
    private static final long POLL_MILLIS = 100L;

    /** How much of the command block's last output is returned. */
    private static final int MAX_OUTPUT_CHARS = 600;

    private ClientCommandBlockTools() {
    }

    public static void register() {
        registerRun();
        registerSetBlockNbt();
        registerChiselBlock();
    }

    // ------------------------------------------------------------------
    // Shared schema
    // ------------------------------------------------------------------

    private static final String REQUIREMENTS = "Needs creative mode, operator level 2 and "
        + "enable-command-block=true on the server.";

    private static JsonSchema withCommandBlockArgs(JsonSchema schema) {
        return schema
            .array("command_block", "[x, y, z] of an air block (or an impulse command block) to run "
                + "from. Default: the first air block 2-6 above the player.",
                Json.obj("type", new JsonPrimitive("integer")))
            .bool("keep_block", "Leave a command block this call placed. Default false: it is "
                + "set back to air.")
            .integer("timeout_ms", "How long to wait for the command to run. Default 5000.", 500,
                30_000);
    }

    // ------------------------------------------------------------------
    // client_command_block_run
    // ------------------------------------------------------------------

    private static void registerRun() {
        McpRegistry.registerTool(McpTool.named("client_command_block_run")
            .title("Run a long command")
            .description("Run one command of up to " + MAX_COMMAND_CHARS + " characters on the "
                + "server through a command block, for what does not fit chat's 256: /blockdata or "
                + "/setblock with large NBT, /summon with long data. Places a temporary command "
                + "block, sets its command by the packet its edit screen sends, runs it once, reads "
                + "the result back and removes the block. " + REQUIREMENTS + "\n\n"
                + "The command runs as the command block, not the player: ~ and @p resolve from the "
                + "block, so use absolute coordinates. Returns successCount (0 means it failed) and "
                + "the block's last output line; success messages appear there only while the "
                + "commandBlockOutput gamerule is on, errors always.")
            .schema(withCommandBlockArgs(JsonSchema.object()
                .string("command", "The command, with or without a leading '/'.")
                .required("command"))
                .build())
            .clientOnly()
            .destructive()
            .handler(context -> {
                String command = context.requireString("command");
                Run run = run(context, command);
                return run.toResult();
            })
            .build());
    }

    /** What one command-block run did. */
    static final class Run {

        final JsonObject json = new JsonObject();
        /** Set when the command never ran: refused here or by the server. */
        String refusal;
        int successCount = -1;

        ToolResult toResult() {
            if (refusal != null) {
                return ToolResult.error(refusal).withStructured(json);
            }
            return ToolResult.structured(json);
        }

        Run refuse(String why) {
            refusal = why;
            json.addProperty("status", "refused");
            json.addProperty("error", why);
            return this;
        }
    }

    /** Runs {@code rawCommand} through a command block, with the call's command-block arguments. */
    static Run run(ToolContext context, String rawCommand) throws Exception {
        Run run = new Run();
        String command = rawCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty()) {
            return run.refuse("'command' is empty.");
        }
        if (!McmcpConfig.isAllowCommands()) {
            return run.refuse("Command execution is disabled by permissions.allowCommands in the "
                + "MCMCP config.");
        }
        if (McmcpConfig.isCommandBlocked("/" + command)) {
            return run.refuse("That command is on the blocked list in permissions.blockedCommands.");
        }
        if (command.length() > MAX_COMMAND_CHARS) {
            return run.refuse("That command is " + command.length() + " characters; a command "
                + "block holds at most " + MAX_COMMAND_CHARS + ".");
        }
        int payload = command.getBytes(StandardCharsets.UTF_8).length + 32;
        if (payload > MAX_PAYLOAD_BYTES) {
            return run.refuse("That command is " + payload + " bytes as UTF-8; the packet carries "
                + "at most " + MAX_PAYLOAD_BYTES + ".");
        }
        run.json.addProperty("commandChars", command.length());

        final int[] requested = intTriple(context.getArguments(), "command_block");
        if (context.has("command_block") && requested == null) {
            return run.refuse("command_block must be three integers [x, y, z].");
        }
        boolean keep = context.getBoolean("keep_block", false);
        long timeout = context.getBoundedInt("timeout_ms", 5000, 500, 30_000);

        final Object[] chosen = new Object[2];
        String problem = context.onGameThread(new Callable<String>() {
            @Override
            public String call() {
                Minecraft mc = ClientStateTools.requireInWorld();
                if (!mc.playerController.isInCreativeMode()) {
                    return "The server lets only a player in creative mode set a command block; this "
                        + "player is not. Switch with /gamemode 1 first.";
                }
                if (mc.player.getPermissionLevel() < 2) {
                    return "The server lets only an operator (level 2+) set a command block; this "
                        + "player's level is " + mc.player.getPermissionLevel() + ".";
                }
                if (requested != null) {
                    BlockPos pos = new BlockPos(requested[0], requested[1], requested[2]);
                    if (!GameJson.isLoaded(mc.world, pos)) {
                        return pos + " is not loaded on this client.";
                    }
                    Block block = mc.world.getBlockState(pos).getBlock();
                    if (block == Blocks.COMMAND_BLOCK) {
                        chosen[0] = pos;
                        chosen[1] = false;
                        return null;
                    }
                    if (block != Blocks.AIR) {
                        return "command_block " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                            + " is " + block.getRegistryName() + "; give an air block or an "
                            + "impulse command block.";
                    }
                    chosen[0] = pos;
                    chosen[1] = true;
                    return null;
                }
                BlockPos base = GameJson.blockPosOf(mc.player).up(2);
                for (int up = 0; up < 5; up++) {
                    BlockPos pos = base.up(up);
                    if (pos.getY() < 256 && mc.world.isAirBlock(pos)) {
                        chosen[0] = pos;
                        chosen[1] = true;
                        return null;
                    }
                }
                return "No air block 2-6 above the player to put a command block in; give "
                    + "command_block.";
            }
        });
        if (problem != null) {
            return run.refuse(problem);
        }
        BlockPos pos = (BlockPos) chosen[0];
        boolean place = (Boolean) chosen[1];
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        run.json.add("commandBlock", GameJson.blockPos(pos));
        run.json.addProperty("placed", place);

        try (ClientChatRecorder.Capture chat = ClientChatRecorder.capture()) {
            if (place) {
                Reply placed = chatCommand(context, chat, "/setblock " + at
                    + " minecraft:command_block");
                if (placed.error != null) {
                    return run.refuse("Placing the command block failed: " + placed.error);
                }
            }
            // Arm it: -1 marks "not run yet"; auto off so the packet's auto on is an edge that
            // schedules a run; the name is what an operator's chat shows as the command's sender.
            Reply armed = chatCommand(context, chat, "/blockdata " + at + " {SuccessCount:-1,"
                + "auto:0b,powered:0b,TrackOutput:1b,CustomName:\"MCMCP\"}");
            if (armed.error != null) {
                removeIfPlaced(context, chat, run, place, keep, at);
                return run.refuse("Preparing the command block failed: " + armed.error);
            }

            final String toSend = command;
            final BlockPos target = pos;
            context.onGameThread(new Callable<Void>() {
                @Override
                public Void call() {
                    Minecraft mc = ClientStateTools.requireInWorld();
                    PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
                    buffer.writeInt(target.getX());
                    buffer.writeInt(target.getY());
                    buffer.writeInt(target.getZ());
                    buffer.writeString(toSend);
                    buffer.writeBoolean(true); // track output
                    buffer.writeString("REDSTONE"); // impulse
                    buffer.writeBoolean(false); // conditional
                    buffer.writeBoolean(true); // always active: runs it
                    mc.getConnection().sendPacket(new CPacketCustomPayload("MC|AutoCmd", buffer));
                    return null;
                }
            });

            String refused = awaitCommandSet(chat);
            if (refused != null) {
                removeIfPlaced(context, chat, run, place, keep, at);
                return run.refuse(refused);
            }

            long deadline = System.currentTimeMillis() + timeout;
            NBTTagCompound tag = null;
            String readError = null;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MILLIS);
                ClientCommandTools.ServerTag read = ClientCommandTools.readServerTag(context, pos);
                if (read.error != null) {
                    readError = read.error;
                    break;
                }
                if (read.tag == null) {
                    readError = "The command block at " + at + " is gone.";
                    break;
                }
                if (read.tag.getInteger("SuccessCount") != -1) {
                    tag = read.tag;
                    break;
                }
            }

            if (tag == null) {
                run.json.addProperty("status", "no_result");
                run.json.addProperty("error", readError != null ? readError
                    : "The command block had not run within " + timeout + " ms.");
            } else {
                run.successCount = tag.getInteger("SuccessCount");
                run.json.addProperty("status", run.successCount > 0 ? "ok" : "failed");
                run.json.addProperty("successCount", run.successCount);
                String output = lastOutput(tag);
                if (output != null) {
                    run.json.addProperty("lastOutput", output);
                }
            }
            removeIfPlaced(context, chat, run, place, keep, at);
        }
        return run;
    }

    /** Waits for the server's answer to the packet; the refusal text, or null if it was taken. */
    @Nullable
    private static String awaitCommandSet(ClientChatRecorder.Capture chat)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + CHAT_REPLY_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            for (ClientChatRecorder.Entry entry; (entry = chat.poll()) != null; ) {
                String key = entry.getTranslationKey();
                if ("advMode.notEnabled".equals(key)) {
                    return "The server has command blocks disabled (enable-command-block=false in "
                        + "server.properties).";
                }
                if ("advMode.notAllowed".equals(key)) {
                    return "The server refused: setting a command block needs creative mode and "
                        + "operator level 2.";
                }
                if ("advMode.setCommand.success".equals(key)) {
                    return null;
                }
            }
            Thread.sleep(5L);
        }
        // No answer is not a refusal: a mod may have swallowed the message. The result read says.
        return null;
    }

    private static void removeIfPlaced(ToolContext context, ClientChatRecorder.Capture chat,
        Run run, boolean placed, boolean keep, String at) throws Exception {
        if (!placed || keep) {
            return;
        }
        Reply removed = chatCommand(context, chat, "/setblock " + at + " minecraft:air");
        run.json.addProperty("removed", removed.error == null);
        if (removed.error != null) {
            run.json.addProperty("removeError", removed.error);
        }
    }

    /** The block's last output line, without its timestamp, cut to a readable length. */
    @Nullable
    private static String lastOutput(NBTTagCompound tag) {
        if (!tag.hasKey("LastOutput", 8)) {
            return null;
        }
        String text;
        try {
            ITextComponent component = ITextComponent.Serializer.jsonToComponent(
                tag.getString("LastOutput"));
            text = component == null ? "" : component.getUnformattedText();
        } catch (RuntimeException e) {
            text = tag.getString("LastOutput");
        }
        text = text.replaceFirst("^\\[\\d{2}:\\d{2}:\\d{2}] ", "");
        return text.length() > MAX_OUTPUT_CHARS
            ? text.substring(0, MAX_OUTPUT_CHARS) + "… (" + text.length() + " characters)" : text;
    }

    /** A short command's outcome: null error when it went through. */
    private static final class Reply {

        String error;
    }

    /** Sends a chat command and waits for its replies, as {@code client_run_commands} does. */
    private static Reply chatCommand(ToolContext context, ClientChatRecorder.Capture chat,
        final String command) throws Exception {
        Reply reply = new Reply();
        String refusal = ClientInputTools.chatRefusal(command);
        if (refusal != null) {
            reply.error = refusal;
            return reply;
        }
        drain(chat);
        boolean client = context.onGameThread(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return ClientInputTools.sendAsPlayer(command);
            }
        });
        if (client) {
            reply.error = command + " did not reach the server: a client-side handler took it.";
            return reply;
        }
        long start = System.currentTimeMillis();
        long lastLine = -1L;
        while (true) {
            for (ClientChatRecorder.Entry entry; (entry = chat.poll()) != null; ) {
                if (!"SYSTEM".equals(entry.getType())) {
                    continue;
                }
                lastLine = System.currentTimeMillis();
                if (entry.isError() && reply.error == null) {
                    reply.error = entry.getText();
                }
            }
            long now = System.currentTimeMillis();
            if (lastLine < 0 ? now - start >= CHAT_REPLY_MILLIS : now - lastLine >= QUIET_MILLIS) {
                return reply;
            }
            Thread.sleep(5L);
        }
    }

    private static void drain(ClientChatRecorder.Capture chat) {
        while (chat.poll() != null) {
            // Lines from before this command are not its replies.
        }
    }

    @Nullable
    private static int[] intTriple(JsonObject arguments, String name) {
        JsonElement element = arguments.get(name);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            return null;
        }
        int[] values = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement value = element.getAsJsonArray().get(i);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            values[i] = value.getAsInt();
        }
        return values;
    }

    // ------------------------------------------------------------------
    // client_set_block_nbt
    // ------------------------------------------------------------------

    private static void registerSetBlockNbt() {
        McpRegistry.registerTool(McpTool.named("client_set_block_nbt")
            .title("Write tile-entity data")
            .description("Merge NBT into the tile entity at x, y, z on the server, as /blockdata "
                + "does, with no 256-character limit: it runs through a command block (see "
                + "client_command_block_run). " + REQUIREMENTS + " Reads the server's tag back "
                + "afterwards and reports which of the given keys now hold the given values. For a "
                + "Chisels & Bits block, client_chisel_block builds the data for you.")
            .schema(withCommandBlockArgs(JsonSchema.object()
                .integer("x", "Block X.")
                .integer("y", "Block Y, 0-255.")
                .integer("z", "Block Z.")
                .property("nbt", ServerBuildTools.nbtSchema("The data to merge. Nested compounds "
                    + "merge; a list or array replaces the old one whole."))
                .required("x", "y", "z", "nbt"))
                .build())
            .clientOnly()
            .destructive()
            .handler(context -> {
                final NBTTagCompound patch;
                try {
                    patch = TileEntityNbt.parse(context.getArguments().get("nbt"));
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }
                if (patch.isEmpty()) {
                    return ToolResult.error("'nbt' is empty; nothing to write.");
                }
                BlockPos pos = new BlockPos(context.requireInt("x"), context.requireInt("y"),
                    context.requireInt("z"));
                Run run = run(context, "blockdata " + pos.getX() + " " + pos.getY() + " "
                    + pos.getZ() + " " + patch);
                run.json.add("target", GameJson.blockPos(pos));
                if (run.refusal == null && run.successCount > 0) {
                    // The read-back says more than vanilla's echo of the whole merged tag.
                    run.json.remove("lastOutput");
                    verify(context, pos, patch, run.json);
                }
                return run.toResult();
            })
            .build());
    }

    /** Which of the patch's top-level keys the server's tag now holds as given. */
    private static void verify(ToolContext context, BlockPos pos, NBTTagCompound patch,
        JsonObject json) throws Exception {
        ClientCommandTools.ServerTag read = ClientCommandTools.readServerTag(context, pos);
        if (read.error != null || read.tag == null) {
            json.addProperty("verifyError", read.error != null ? read.error
                : "There is no tile entity at the target.");
            return;
        }
        List<String> written = new ArrayList<>();
        List<String> differs = new ArrayList<>();
        for (String key : patch.getKeySet()) {
            (contains(read.tag.getTag(key), patch.getTag(key)) ? written : differs).add(key);
        }
        json.add("written", Json.arrayOfStrings(written));
        if (!differs.isEmpty()) {
            json.add("differs", Json.arrayOfStrings(differs));
        }
    }

    /** Whether {@code have} holds {@code want}: equal, or for compounds, every key of it held. */
    private static boolean contains(@Nullable NBTBase have, NBTBase want) {
        if (have == null) {
            return false;
        }
        if (want instanceof NBTTagCompound && have instanceof NBTTagCompound) {
            NBTTagCompound wanted = (NBTTagCompound) want;
            for (String key : wanted.getKeySet()) {
                if (!contains(((NBTTagCompound) have).getTag(key), wanted.getTag(key))) {
                    return false;
                }
            }
            return true;
        }
        return have.equals(want);
    }

    // ------------------------------------------------------------------
    // client_chisel_block
    // ------------------------------------------------------------------

    private static final JsonPrimitive STRING_ARRAY = new JsonPrimitive("array");

    private static final String[] FACES = {"north", "south", "west", "east", "up", "down"};

    private static void registerChiselBlock() {
        JsonObject coord = Json.obj("type", new JsonPrimitive("integer"));
        McpRegistry.registerTool(McpTool.named("client_chisel_block")
            .title("Chisel a block")
            .description("Set the bits of a Chisels & Bits block at x, y, z: lettering, logos, "
                + "relief. Builds the voxel blob and the tile-entity tags C&B needs, places or "
                + "updates the chiseled block on the server through a command block (see "
                + "client_command_block_run; " + REQUIREMENTS + "), then reads it back to check "
                + "every bit.\n\n"
                + "A block is 16x16x16 bits. In every grid string, ' ' leaves a bit as it was, '.' "
                + "makes it air, and any other character is a key of 'palette'. Edits apply in "
                + "order: start, layers, boxes, faces. 'faces' is the easy way to draw on a side: "
                + "16 rows, top row first, as you see that side standing outside it, painted "
                + "'depth' bits deep. client_get_block with nbt and chisel_grid reads a block back "
                + "in the same layers shape.")
            .schema(withCommandBlockArgs(JsonSchema.object()
                .integer("x", "Block X.")
                .integer("y", "Block Y, 0-255.")
                .integer("z", "Block Z.")
                .property("palette", paletteSchema())
                .string("start", "What the bits are before edits: 'empty' (air, default), "
                    + "'existing' (the block there now: its bits if chiseled, or all of it if a "
                    + "plain block), or a palette key to fill with.")
                .array("layers", "Every bit: 16 layers, y 0 (bottom) first, each 16 strings, z 0 "
                    + "(north) first, each 16 characters, x 0 (west) first. Shorter rows and "
                    + "missing layers leave the rest unchanged.", Json.obj("type", STRING_ARRAY))
                .array("boxes", "Fill boxes of bits, inclusive corners.", JsonSchema.object()
                    .array("from", "[x, y, z], each 0-15.", coord)
                    .array("to", "[x, y, z], each 0-15.", coord)
                    .string("key", "A palette key, or '.' for air.")
                    .required("from", "to", "key")
                    .build())
                .array("faces", "Draw on a side of the block.", JsonSchema.object()
                    .enumeration("face", "The side, by the direction it faces. 'up' and 'down' are "
                        + "drawn as on a map: top row north, left column west.", FACES)
                    .stringArray("rows", "Up to 16 strings of up to 16 characters, top row "
                        + "first, left to right as seen from outside.")
                    .integer("depth", "How many bits deep each character goes. Default 1.", 1, 16)
                    .required("face", "rows")
                    .build())
                .bool("dry_run", "Build and size everything but write nothing. Default false.")
                .required("x", "y", "z"))
                .build())
            .clientOnly()
            .destructive()
            .handler(ClientCommandBlockTools::chisel)
            .build());
    }

    private static JsonObject paletteSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "Single-character keys to block states, e.g. {\"#\": "
            + "\"minecraft:stone\", \"r\": \"minecraft:wool[color=red]\"}. '.' and ' ' are "
            + "reserved.");
        schema.add("additionalProperties", Json.obj("type",
            new JsonPrimitive("string")));
        return schema;
    }

    private static ToolResult chisel(ToolContext context) throws Exception {
        final JsonObject args = context.getArguments();
        final BlockPos pos = new BlockPos(context.requireInt("x"), context.requireInt("y"),
            context.requireInt("z"));
        final String start = context.getString("start", "empty");
        final boolean dryRun = context.getBoolean("dry_run", false);

        // Resolve the palette and draw the grid on the client thread: states come from its
        // registries, which Forge synced from the server at login.
        final IBlockState[] cells = new IBlockState[ChiselBlob.VOXELS];
        final String[] hereNow = new String[1];
        final boolean needsServerBits = "existing".equals(start);
        String problem = context.onGameThread(new Callable<String>() {
            @Override
            public String call() {
                Minecraft mc = ClientStateTools.requireInWorld();
                if (!Block.REGISTRY.containsKey(new ResourceLocation(ChiselNbt.MOD_ID,
                    "chiseled_rock"))) {
                    return "Chisels & Bits is not installed in this game.";
                }
                if (!GameJson.isLoaded(mc.world, pos)) {
                    return pos + " is not loaded on this client.";
                }
                IBlockState now = mc.world.getBlockState(pos);
                hereNow[0] = String.valueOf(now.getBlock().getRegistryName());
                return null;
            }
        });
        if (problem != null) {
            return ToolResult.error(problem);
        }

        IBlockState fillState = Blocks.AIR.getDefaultState();
        if (needsServerBits && ChiselNbt.isChiseledBlock(hereNow[0])) {
            ClientCommandTools.ServerTag read = ClientCommandTools.readServerTag(context, pos);
            if (read.error != null) {
                return ToolResult.error("Reading the existing bits failed: " + read.error);
            }
            if (read.tag == null || !ChiselNbt.hasBlob(read.tag)) {
                return ToolResult.error("The chiseled block at " + pos + " has no voxel data.");
            }
            IBlockState[] existing = ChiselNbt.states(read.tag.getByteArray(ChiselNbt.TAG_BLOB));
            for (int i = 0; i < existing.length; i++) {
                if (existing[i] == null) {
                    return ToolResult.error("The existing bits include a state this client does "
                        + "not know; start from 'empty' instead.");
                }
            }
            System.arraycopy(existing, 0, cells, 0, cells.length);
        }

        final Map<Character, IBlockState> palette;
        final String[] drawError = new String[1];
        palette = context.onGameThread(new Callable<Map<Character, IBlockState>>() {
            @Override
            public Map<Character, IBlockState> call() {
                Minecraft mc = ClientStateTools.requireInWorld();
                Map<Character, IBlockState> keys = new HashMap<>();
                keys.put('.', Blocks.AIR.getDefaultState());
                JsonObject given = Json.getObjectOrEmpty(args, "palette");
                for (Map.Entry<String, JsonElement> entry : given.entrySet()) {
                    String key = entry.getKey();
                    if (key.length() != 1 || key.equals(".") || key.equals(" ")) {
                        drawError[0] = "Palette key '" + key + "' must be one character other "
                            + "than '.' or ' '.";
                        return null;
                    }
                    if (!entry.getValue().isJsonPrimitive()) {
                        drawError[0] = "Palette key '" + key + "' must name a block state.";
                        return null;
                    }
                    try {
                        // Through its numeric id and back: a bit is stored as that id, so a
                        // property it cannot hold (a stair's shape) would never read back.
                        IBlockState state = ChiselNbt.parseState(entry.getValue().getAsString());
                        keys.put(key.charAt(0), Block.getStateById(Block.getStateId(state)));
                    } catch (IllegalArgumentException e) {
                        drawError[0] = "Palette key '" + key + "': " + e.getMessage();
                        return null;
                    }
                }
                if ("existing".equals(start)) {
                    if (cells[0] == null) {
                        IBlockState now = mc.world.getBlockState(pos);
                        Arrays.fill(cells, now.getBlock() == Blocks.AIR
                            || ChiselNbt.isChiseledBlock(hereNow[0])
                            ? Blocks.AIR.getDefaultState() : now);
                    }
                } else if ("empty".equals(start)) {
                    Arrays.fill(cells, Blocks.AIR.getDefaultState());
                } else if (start.length() == 1 && keys.containsKey(start.charAt(0))) {
                    Arrays.fill(cells, keys.get(start.charAt(0)));
                } else {
                    drawError[0] = "start must be 'empty', 'existing' or a palette key; got '"
                        + start + "'.";
                    return null;
                }
                return keys;
            }
        });
        if (palette == null) {
            return ToolResult.error(drawError[0]);
        }

        String drawn = draw(args, palette, cells);
        if (drawn != null) {
            return ToolResult.error(drawn);
        }

        // States to a palette and indices, then the tag.
        final List<IBlockState> states = new ArrayList<>();
        final int[] indices = new int[ChiselBlob.VOXELS];
        Map<IBlockState, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            Integer index = indexOf.get(cells[i]);
            if (index == null) {
                index = states.size();
                indexOf.put(cells[i], index);
                states.add(cells[i]);
            }
            indices[i] = index;
        }
        ChiselNbt.Built built = context.onGameThread(new Callable<ChiselNbt.Built>() {
            @Override
            public ChiselNbt.Built call() {
                return ChiselNbt.build(states, indices);
            }
        });

        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String command;
        if (built.blobBytes == 0) {
            command = "setblock " + at + " minecraft:air";
        } else if (built.blockId.equals(hereNow[0])) {
            command = "blockdata " + at + " " + built.tag;
        } else {
            command = "setblock " + at + " " + built.blockId + " 0 replace " + built.tag;
        }

        JsonObject summary = new JsonObject();
        summary.addProperty("block", built.blobBytes == 0 ? "minecraft:air" : built.blockId);
        summary.addProperty("blobBytes", built.blobBytes);
        summary.addProperty("commandChars", command.length());
        JsonObject counts = new JsonObject();
        int[] tally = new int[states.size()];
        for (int index : indices) {
            tally[index]++;
        }
        for (int i = 0; i < states.size(); i++) {
            counts.addProperty(ChiselNbt.stateName(states.get(i)), tally[i]);
        }
        summary.add("bits", counts);
        if (command.length() > MAX_COMMAND_CHARS) {
            return ToolResult.error("The block's data comes to a " + command.length()
                + "-character command; a command block holds " + MAX_COMMAND_CHARS + ". Use fewer "
                + "states or simpler shapes: noise compresses badly.").withStructured(summary);
        }
        if (dryRun) {
            summary.addProperty("dryRun", true);
            return ToolResult.structured(summary);
        }

        Run run = run(context, command);
        for (Map.Entry<String, JsonElement> entry : summary.entrySet()) {
            run.json.add(entry.getKey(), entry.getValue());
        }
        if (run.refusal == null && run.successCount > 0 && built.blobBytes > 0) {
            run.json.remove("lastOutput");
            checkBits(context, pos, cells, run.json);
        }
        return run.toResult();
    }

    /** Applies layers, boxes and faces to {@code cells}; an error message, or null. */
    @Nullable
    static String draw(JsonObject args, Map<Character, IBlockState> palette, IBlockState[] cells) {
        JsonElement layers = args.get("layers");
        if (layers != null) {
            if (!layers.isJsonArray() || layers.getAsJsonArray().size() > 16) {
                return "layers must be an array of at most 16 layers.";
            }
            JsonArray ys = layers.getAsJsonArray();
            for (int y = 0; y < ys.size(); y++) {
                if (!ys.get(y).isJsonArray() || ys.get(y).getAsJsonArray().size() > 16) {
                    return "layers[" + y + "] must be an array of at most 16 strings.";
                }
                JsonArray rows = ys.get(y).getAsJsonArray();
                for (int z = 0; z < rows.size(); z++) {
                    String row = rows.get(z).getAsString();
                    if (row.length() > 16) {
                        return "layers[" + y + "][" + z + "] is " + row.length()
                            + " characters; at most 16.";
                    }
                    for (int x = 0; x < row.length(); x++) {
                        String bad = put(palette, cells, row.charAt(x), x, y, z);
                        if (bad != null) {
                            return "layers[" + y + "][" + z + "]: " + bad;
                        }
                    }
                }
            }
        }

        JsonArray boxes = Json.getArray(args, "boxes");
        for (int i = 0; boxes != null && i < boxes.size(); i++) {
            JsonObject box = boxes.get(i).getAsJsonObject();
            int[] from = intTriple(box, "from");
            int[] to = intTriple(box, "to");
            String key = Json.getString(box, "key", "");
            if (from == null || to == null || !inBlock(from) || !inBlock(to)) {
                return "boxes[" + i + "]: from and to must be [x, y, z] with each 0-15.";
            }
            if (key.length() != 1) {
                return "boxes[" + i + "]: key must be one character.";
            }
            for (int x = Math.min(from[0], to[0]); x <= Math.max(from[0], to[0]); x++) {
                for (int y = Math.min(from[1], to[1]); y <= Math.max(from[1], to[1]); y++) {
                    for (int z = Math.min(from[2], to[2]); z <= Math.max(from[2], to[2]); z++) {
                        String bad = put(palette, cells, key.charAt(0), x, y, z);
                        if (bad != null) {
                            return "boxes[" + i + "]: " + bad;
                        }
                    }
                }
            }
        }

        JsonArray faces = Json.getArray(args, "faces");
        for (int i = 0; faces != null && i < faces.size(); i++) {
            JsonObject face = faces.get(i).getAsJsonObject();
            String side = Json.getString(face, "face", "");
            List<String> rows = Json.getStringList(face, "rows");
            int depth = Json.getInt(face, "depth", 1);
            if (Arrays.asList(FACES).indexOf(side) < 0) {
                return "faces[" + i + "]: face must be one of north, south, west, east, up, down.";
            }
            if (rows.size() > 16 || depth < 1 || depth > 16) {
                return "faces[" + i + "]: at most 16 rows, and depth 1-16.";
            }
            for (int r = 0; r < rows.size(); r++) {
                String row = rows.get(r);
                if (row.length() > 16) {
                    return "faces[" + i + "].rows[" + r + "] is " + row.length()
                        + " characters; at most 16.";
                }
                for (int c = 0; c < row.length(); c++) {
                    for (int d = 0; d < depth; d++) {
                        int[] xyz = faceToBlock(side, r, c, d);
                        String bad = put(palette, cells, row.charAt(c), xyz[0], xyz[1], xyz[2]);
                        if (bad != null) {
                            return "faces[" + i + "].rows[" + r + "]: " + bad;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Where row {@code r}, column {@code c}, {@code d} bits in from the {@code face} side lands, for
     * someone outside that side looking at it: rows run top to bottom, columns left to right.
     * {@code up} and {@code down} read as a map, north at the top.
     */
    static int[] faceToBlock(String face, int r, int c, int d) {
        int top = 15 - r;
        switch (face) {
            case "north": // looking south: left is east
                return new int[]{15 - c, top, d};
            case "south": // looking north: left is west
                return new int[]{c, top, 15 - d};
            case "west": // looking east: left is north
                return new int[]{d, top, c};
            case "east": // looking west: left is south
                return new int[]{15 - d, top, 15 - c};
            case "up":
                return new int[]{c, 15 - d, r};
            default: // down
                return new int[]{c, d, r};
        }
    }

    @Nullable
    private static String put(Map<Character, IBlockState> palette, IBlockState[] cells, char key,
        int x, int y, int z) {
        if (key == ' ') {
            return null;
        }
        IBlockState state = palette.get(key);
        if (state == null) {
            return "'" + key + "' is not a palette key.";
        }
        cells[ChiselBlob.index(x, y, z)] = state;
        return null;
    }

    private static boolean inBlock(int[] xyz) {
        for (int value : xyz) {
            if (value < 0 || value > 15) {
                return false;
            }
        }
        return true;
    }

    /** Reads the block back from the server and counts bits that are not what was written. */
    private static void checkBits(ToolContext context, BlockPos pos, IBlockState[] wanted,
        JsonObject json) throws Exception {
        ClientCommandTools.ServerTag read = ClientCommandTools.readServerTag(context, pos);
        if (read.error != null || read.tag == null || !ChiselNbt.hasBlob(read.tag)) {
            json.addProperty("verifyError", read.error != null ? read.error
                : "The server's block has no voxel data after the write.");
            return;
        }
        IBlockState[] stored;
        try {
            stored = ChiselNbt.states(read.tag.getByteArray(ChiselNbt.TAG_BLOB));
        } catch (IllegalArgumentException e) {
            json.addProperty("verifyError", "The server's blob could not be read: " + e.getMessage());
            return;
        }
        int wrong = 0;
        for (int i = 0; i < wanted.length; i++) {
            if (stored[i] != wanted[i]) {
                wrong++;
            }
        }
        json.addProperty("verified", wrong == 0);
        if (wrong > 0) {
            json.addProperty("bitsDiffering", wrong);
        }
    }
}
