package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.client.ClientChatRecorder;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.CommandTargets;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import com.micatechnologies.minecraft.mcmcp.tools.NbtJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Running commands as the player and getting each one's answer back.
 *
 * <h2>How a reply is matched to its command</h2>
 *
 * A server's reply to a command is an ordinary chat packet with nothing tying it to the command
 * that caused it (see {@link ClientChatRecorder}). What does tie them is order: a server runs one
 * player's commands in the order they arrive and answers each before reading the next. So this sends
 * one command, collects the system lines that arrive until the replies stop, and only then sends the
 * next. Lines arriving before a command is sent belong to the one before it.
 *
 * <p>That is also its limit, and the tool says so: an unrelated system line — a server broadcast, a
 * plugin's announcement — that lands inside a command's window is attributed to it. Player chat is
 * left out, since no command reply is sent as chat.
 *
 * <h2>Success and failure</h2>
 *
 * A failed command is recognised by colour, not wording: vanilla colours every command failure red,
 * and so do WorldEdit, FAWE and ForgeEssentials. See {@link ClientChatRecorder#isErrorStyled}.
 */
@SideOnly(Side.CLIENT)
public final class ClientCommandTools {

    /** Most commands in one call. A longer build is several calls, resumed from nextIndex. */
    private static final int MAX_COMMANDS = 1000;

    /** How long one call may run before it stops and hands back nextIndex; inside a two-minute call. */
    private static final long MAX_RUN_MILLIS = 90_000L;

    /**
     * How long a command's reply is considered finished once no further line has come. A server
     * answers a command in one tick, so its lines arrive together; one tick and a little is enough.
     */
    private static final long QUIET_MILLIS = 60L;

    private static final long POLL_MILLIS = 5L;

    private ClientCommandTools() {
    }

    public static void register() {
        ClientChatRecorder.register();
        registerRunCommands();
    }

    private static void registerRunCommands() {
        McpRegistry.registerTool(McpTool.named("client_run_commands")
            .title("Run commands")
            .description("Run a list of commands as the player, in order, and return what the "
                + "server answered to each. Use this for scripted building (/fill, /setblock, "
                + "/clone, /blockdata) instead of client_send_chat in a loop: each command's replies "
                + "come back with it, and failures are picked out.\n\n"
                + "Each command is sent once the previous one's reply has arrived. Its status is "
                + "'error' when the server answered in red (how vanilla, WorldEdit, FAWE and "
                + "ForgeEssentials report failure), 'ok' when it answered otherwise, 'no_reply' when "
                + "nothing came within reply_timeout_ms (normal when the sendCommandFeedback gamerule "
                + "is off), 'client' for a client-side command, and 'refused', 'unloaded' or "
                + "'not_run' when it was not sent. A server broadcast arriving mid-command may be "
                + "counted as that command's reply.\n\n"
                + "Results list only commands that were not 'ok' unless all_results is true. One "
                + "call stops after about 90 seconds and returns nextIndex; call again with the rest. "
                + "A player who is not an operator is kicked for sending commands faster than about "
                + "one a tick; use delay_ms there.")
            .schema(JsonSchema.object()
                .stringArray("commands", "The commands, each starting with '/', at most "
                    + MAX_COMMANDS + " per call and 256 characters each.")
                .integer("delay_ms", "Extra pause after each command's reply. Default 0.", 0, 5000)
                .integer("reply_timeout_ms", "How long to wait for a command's first reply before "
                    + "calling it 'no_reply'. Default 1000.", 50, 10000)
                .bool("stop_on_error", "Stop at the first 'error' or 'unloaded' command. Default "
                    + "false.")
                .bool("check_loaded", "Before sending /fill, /setblock, /clone or /blockdata, check "
                    + "that the chunks it touches are loaded on this client, and mark it 'unloaded' "
                    + "without sending if not. Default false. The server may have chunks loaded "
                    + "that the client does not, so this can skip a command that would have worked.")
                .bool("all_results", "Also list the replies of commands that were 'ok'. Default "
                    + "false.")
                .required("commands")
                .build())
            .clientOnly()
            .destructive()
            .handler(context -> {
                final List<String> commands = Json.getStringList(context.getArguments(), "commands");
                if (commands.isEmpty()) {
                    return ToolResult.error("'commands' is empty; nothing to run.");
                }
                if (commands.size() > MAX_COMMANDS) {
                    return ToolResult.error("That is " + commands.size() + " commands; one call runs "
                        + "at most " + MAX_COMMANDS + ". Split the list.");
                }
                final int delayMillis = context.getBoundedInt("delay_ms", 0, 0, 5000);
                final int replyTimeout = context.getBoundedInt("reply_timeout_ms", 1000, 50, 10000);
                final boolean stopOnError = context.getBoolean("stop_on_error", false);
                final boolean checkLoaded = context.getBoolean("check_loaded", false);
                final boolean allResults = context.getBoolean("all_results", false);

                return ToolResult.structured(run(context, commands, delayMillis, replyTimeout,
                    stopOnError, checkLoaded, allResults));
            })
            .build());
    }

    private static JsonObject run(ToolContext context, List<String> commands, int delayMillis,
        int replyTimeout, boolean stopOnError, boolean checkLoaded, boolean allResults)
        throws Exception {

        long deadline = System.currentTimeMillis() + MAX_RUN_MILLIS;
        List<Outcome> outcomes = new ArrayList<>();
        Integer nextIndex = null;
        String stopReason = null;
        Outcome lastSent = null;

        try (ClientChatRecorder.Capture chat = ClientChatRecorder.capture()) {
            for (int i = 0; i < commands.size(); i++) {
                if (context.getCancellation().isCancelled()) {
                    nextIndex = i;
                    stopReason = "cancelled";
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    nextIndex = i;
                    stopReason = "time limit for one call";
                    break;
                }
                if (i % 25 == 0) {
                    context.reportProgress(i, commands.size(), null);
                }

                // Anything still arriving belongs to the last command the server was sent.
                drain(chat, lastSent);

                final String command = commands.get(i).trim();
                Outcome outcome = new Outcome(i, command);
                outcomes.add(outcome);

                if (!command.startsWith("/")) {
                    outcome.status = "refused";
                    outcome.replies.add("Not a command: it does not start with '/'. Send chat with "
                        + "client_send_chat.");
                    continue;
                }
                String refusal = ClientInputTools.chatRefusal(command);
                if (refusal != null) {
                    outcome.status = "refused";
                    outcome.replies.add(refusal);
                    continue;
                }

                String sent = context.onGameThread(new Callable<String>() {
                    @Override
                    public String call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        if (checkLoaded) {
                            String unloaded = unloadedTarget(mc, command);
                            if (unloaded != null) {
                                return unloaded;
                            }
                        }
                        return ClientInputTools.sendAsPlayer(command) ? "client" : "server";
                    }
                });
                if ("client".equals(sent)) {
                    outcome.status = "client";
                } else if (!"server".equals(sent)) {
                    outcome.status = "unloaded";
                    outcome.replies.add(sent);
                } else {
                    lastSent = outcome;
                    collectReplies(chat, outcome, replyTimeout);
                }

                if (stopOnError && ("error".equals(outcome.status)
                    || "unloaded".equals(outcome.status))) {
                    nextIndex = i + 1;
                    stopReason = "stop_on_error";
                    break;
                }
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
            }
            // A last look for replies to the final command that arrived after its quiet window.
            Thread.sleep(QUIET_MILLIS);
            drain(chat, lastSent);
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArray results = new JsonArray();
        for (Outcome outcome : outcomes) {
            Integer count = counts.get(outcome.status);
            counts.put(outcome.status, count == null ? 1 : count + 1);
            boolean ok = "ok".equals(outcome.status);
            if (ok && !allResults) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("i", outcome.index);
            entry.addProperty("status", outcome.status);
            if (!ok) {
                entry.addProperty("command", outcome.command);
            }
            if (!outcome.replies.isEmpty()) {
                entry.add("replies", Json.arrayOfStrings(outcome.replies));
            }
            results.add(entry);
        }

        JsonObject json = new JsonObject();
        json.addProperty("total", commands.size());
        json.addProperty("ran", outcomes.size());
        JsonObject countJson = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            countJson.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("counts", countJson);
        if (nextIndex != null && nextIndex < commands.size()) {
            json.addProperty("nextIndex", nextIndex);
            json.addProperty("stopped", stopReason);
        }
        json.add("results", results);
        return json;
    }

    /**
     * Waits for {@code outcome}'s replies: up to {@code timeout} for the first, then until no line
     * has come for {@link #QUIET_MILLIS}.
     */
    private static void collectReplies(ClientChatRecorder.Capture chat, Outcome outcome,
        long timeout) throws InterruptedException {
        long start = System.currentTimeMillis();
        long lastLine = -1L;
        while (true) {
            if (drain(chat, outcome)) {
                lastLine = System.currentTimeMillis();
            }
            long now = System.currentTimeMillis();
            if (lastLine < 0 ? now - start >= timeout : now - lastLine >= QUIET_MILLIS) {
                break;
            }
            Thread.sleep(POLL_MILLIS);
        }
        if (outcome.status == null) {
            outcome.status = "no_reply";
        }
    }

    /**
     * Moves every captured system line into {@code outcome}, or drops it when there is none.
     *
     * @return whether any line counted
     */
    private static boolean drain(ClientChatRecorder.Capture chat, Outcome outcome) {
        boolean any = false;
        for (ClientChatRecorder.Entry entry; (entry = chat.poll()) != null; ) {
            if (!"SYSTEM".equals(entry.getType()) || outcome == null) {
                continue;
            }
            any = true;
            outcome.replies.add(entry.getText());
            if (entry.isError()) {
                outcome.status = "error";
            } else if (outcome.status == null || "no_reply".equals(outcome.status)) {
                outcome.status = "ok";
            }
        }
        return any;
    }

    /** Why the command's target is not loaded here, or null. Client thread. */
    private static String unloadedTarget(Minecraft mc, String command) {
        BlockPos at = GameJson.blockPosOf(mc.player);
        for (int[] box : CommandTargets.boxes(command, new int[]{at.getX(), at.getY(), at.getZ()})) {
            for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
                for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                    if (!GameJson.isLoaded(mc.world, new BlockPos(cx << 4, 0, cz << 4))) {
                        return "Chunk " + cx + ", " + cz + " (blocks " + (cx << 4) + ", "
                            + (cz << 4) + ") is not loaded on this client; not sent.";
                    }
                }
            }
        }
        return null;
    }

    private static final class Outcome {

        final int index;
        final String command;
        final List<String> replies = new ArrayList<>();
        String status;

        Outcome(int index, String command) {
            this.index = index;
            this.command = command;
        }
    }

    // ------------------------------------------------------------------
    // The server's copy of a tile entity
    // ------------------------------------------------------------------

    /** How long to wait for /blockdata's answer. */
    private static final long BLOCKDATA_TIMEOUT_MILLIS = 3000L;

    /**
     * The tile entity's tag as the <em>server</em> holds it, read by running
     * {@code /blockdata x y z {}} as the player.
     *
     * <p>Merging an empty tag changes nothing, and vanilla then answers "The data tag did not change:
     * {...}" with the whole tag as the message's argument. Reading that argument from the translated
     * message — rather than scraping the line out of chat — is what makes it structured, and it is
     * one line however long the tag is: the chat window's wrapping is what turned a large tag into
     * hundreds of lines (#32).
     *
     * @return the {@code blockEntity} object for {@code client_get_block}: {@code source}, and
     *         {@code nbt}, {@code present: false} or {@code error}
     */
    static JsonObject serverBlockEntity(ToolContext context, final BlockPos pos) throws Exception {
        final String command = "/blockdata " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
            + " {}";
        JsonObject json = new JsonObject();
        json.addProperty("source", "server-blockdata");
        String refusal = ClientInputTools.chatRefusal(command);
        if (refusal != null) {
            json.addProperty("error", refusal);
            return json;
        }

        try (ClientChatRecorder.Capture chat = ClientChatRecorder.capture()) {
            boolean client = context.onGameThread(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return ClientInputTools.sendAsPlayer(command);
                }
            });
            if (client) {
                json.addProperty("error", "The command did not reach the server: a client-side "
                    + "handler took it.");
                return json;
            }

            long deadline = System.currentTimeMillis() + BLOCKDATA_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                for (ClientChatRecorder.Entry entry; (entry = chat.poll()) != null; ) {
                    if (!"SYSTEM".equals(entry.getType())) {
                        continue;
                    }
                    String key = entry.getTranslationKey();
                    String[] args = entry.getArguments();
                    if (("commands.blockdata.failed".equals(key)
                        || "commands.blockdata.success".equals(key)) && args.length > 0) {
                        try {
                            JsonElement nbt = NbtJson.toJson(JsonToNBT.getTagFromJson(args[0]));
                            json.add("nbt", nbt);
                        } catch (NBTException e) {
                            json.addProperty("error", "The server's tag could not be parsed: "
                                + e.getMessage());
                            json.addProperty("raw", args[0]);
                        }
                        return json;
                    }
                    if ("commands.blockdata.notValid".equals(key)) {
                        json.addProperty("present", false);
                        return json;
                    }
                    if (entry.isError()) {
                        json.addProperty("error", "/blockdata failed: " + entry.getText());
                        return json;
                    }
                }
                Thread.sleep(POLL_MILLIS * 4);
            }
            json.addProperty("error", "No answer to " + command + " within "
                + BLOCKDATA_TIMEOUT_MILLIS + "ms.");
            return json;
        }
    }
}
