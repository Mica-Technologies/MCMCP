package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.ArgumentNames;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonExpect;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolActivity;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpLogLevel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Several input and read steps in one call.
 *
 * <h2>Why this exists</h2>
 *
 * {@code client_run_commands} batches chat commands, but every input step was its own call: hand
 * placing one directional block was a teleport, a slot select, a look, a click and a read-back. A
 * RealGrid regression test needed 24 of them, over a hundred calls before any wire was tested, and
 * agents took to scripting the game's HTTP endpoint directly to get round it — which bypasses the
 * orchestrator and leaves the person watching blind (issue #44).
 *
 * <h2>Each step is the real tool</h2>
 *
 * A step names a registered client tool and its usual arguments, and runs through that tool's own
 * handler. Nothing is reimplemented, so a step behaves exactly as the separate call would, ticks
 * and settling included: {@code client_look} still waits for its frames, {@code client_interact}
 * for its hold. What the dispatcher does before a handler runs is done here too — the side check,
 * the unknown-argument refusal — and it is done for every step before the first one runs, so a
 * misspelt argument in step nine does not leave steps one to eight applied.
 *
 * <p>Only tools that act on or read the player and the world as a player can be steps. The world
 * lifecycle, screenshots (whose images would pile up in one reply), profiling and this tool itself
 * cannot.
 */
@SideOnly(Side.CLIENT)
public final class ClientSequenceTools {

    private static final int MAX_STEPS = 100;

    /** One call stops starting new steps after this, and returns where to continue from. */
    private static final long MAX_RUN_MILLIS = 180_000L;

    private static final String EXPECT = "expect";

    private static final String QUIET = "quiet";

    /**
     * Where a step's progress starts, above the whole number of steps already finished.
     *
     * <p>Progress may only go up. A finished step reports its whole number; the next one has to say
     * "now running this" at a value above that, or the report is dropped as a repeat and whoever is
     * watching still sees the last step's outcome while the next one runs.
     */
    private static final double STEP_START = 0.001D;

    /** The longest failure reason put in a progress message or a log line. */
    private static final int REASON_CHARS = 160;

    /** The tools a step may name. */
    private static final Set<String> STEP_TOOLS = Collections.unmodifiableSet(new LinkedHashSet<>(
        Arrays.asList(
            "client_select_slot", "client_look", "client_interact", "client_use_on_block",
            "client_attack_block", "client_key", "client_move", "client_fly", "client_view",
            "client_send_chat", "client_run_commands", "client_command_block_run",
            "client_set_block_nbt", "client_chisel_block", "client_wait",
            "client_get_block", "client_get_blocks", "client_looking_at", "client_player_state",
            "client_inventory", "client_nearby_entities", "client_read_chat",
            "client_gui_state", "client_gui_widgets", "client_gui_click", "client_gui_click_at",
            "client_gui_key", "client_gui_text", "client_gui_close", "client_gui_open",
            "client_options")));

    private ClientSequenceTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("client_sequence")
            .title("Run a sequence of steps")
            .description("Run several client tools in one call, in order, each with its usual "
                + "arguments: select a slot, look, use, read the block back. Each step runs through "
                + "that tool's own handler, so it behaves exactly as the separate call would.\n\n"
                + "A step is an object with one key, the tool's name with or without 'client_', "
                + "whose value is that tool's arguments: {\"select_slot\": {\"slot\": 0}}. It may "
                + "add 'expect', checked against the step's result: every key given must match. "
                + "Keys may be dotted ('mainHand.item'); \"*\" means present, null means absent, "
                + "{\"$contains\": \"x\"} and {\"$notContains\": \"x\"} test a string such as NBT. "
                + "A tool that answers in text only is checked as {\"text\": ...}. "
                + "'quiet': true leaves the step's result out of the reply.\n\n"
                + "A step fails when its tool reports an error or an expectation does not match. "
                + "By default the sequence stops there; stop_on_fail false runs on. Every step is "
                + "checked before any runs, so a misspelt argument changes nothing.\n\n"
                + "Steps can be: " + String.join(", ", STEP_TOOLS) + ". One call stops starting "
                + "steps after about 3 minutes and returns nextIndex.")
            .schema(JsonSchema.object()
                .array("steps", "The steps, at most " + MAX_STEPS + ". Each is {\"<tool>\": "
                    + "{arguments}} with optional 'expect' and 'quiet'.", objectItems())
                .bool("stop_on_fail", "Stop at the first step that fails. Default true.")
                .required("steps")
                .build())
            .clientOnly()
            .destructive()
            .offGameThread()
            .handler(ClientSequenceTools::run)
            .build());
    }

    private static JsonObject objectItems() {
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        return items;
    }

    /** A step, checked and ready to run. */
    private static final class Step {

        final int index;
        final McpTool tool;
        final JsonObject arguments;
        @Nullable
        final JsonObject expect;
        final boolean quiet;

        Step(int index, McpTool tool, JsonObject arguments, @Nullable JsonObject expect,
             boolean quiet) {
            this.index = index;
            this.tool = tool;
            this.arguments = arguments;
            this.expect = expect;
            this.quiet = quiet;
        }
    }

    private static ToolResult run(ToolContext context) throws Exception {
        JsonElement raw = context.getArguments().get("steps");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() == 0) {
            return ToolResult.error("'steps' must be a non-empty array of steps.");
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() > MAX_STEPS) {
            return ToolResult.error("That is " + array.size() + " steps; one call runs at most "
                + MAX_STEPS + ". Split the list.");
        }
        boolean stopOnFail = context.getBoolean("stop_on_fail", true);

        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Object parsed = parse(context, i, array.get(i));
            if (parsed instanceof String) {
                String why = (String) parsed;
                return ToolResult.error("Step " + i + ": " + why
                    + (why.contains("Nothing was done") ? "" : " Nothing was run."));
            }
            steps.add((Step) parsed);
        }

        long deadline = System.currentTimeMillis() + MAX_RUN_MILLIS;
        JsonArray results = new JsonArray();
        int passed = 0;
        int failed = 0;
        Integer nextIndex = null;
        String stopReason = null;
        JsonObject firstError = null;

        for (Step step : steps) {
            if (context.getCancellation().isCancelled()) {
                nextIndex = step.index;
                stopReason = "cancelled";
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                nextIndex = step.index;
                stopReason = "time limit for one call";
                break;
            }
            // Progress counts finished steps, so a sequence that ran every step ends at its total,
            // and the message names the step running now, then how it ended.
            context.reportProgress(step.index + STEP_START, steps.size(), step.tool.getName());
            // The dispatcher notes each call so the client keeps its frame rate up while an agent is
            // driving; a long sequence is still an agent driving.
            ToolActivity.noteCall(context.getSide());

            JsonObject entry = runStep(context, step, steps.size());
            results.add(entry);
            // The last step's report is the sequence's last word, so it sums up the run instead: a
            // sequence that ended on a passing step is not a sequence that passed.
            boolean last = step.index + 1 == steps.size();
            if (entry.get("ok").getAsBoolean()) {
                passed++;
                context.reportProgress(step.index + 1, steps.size(),
                    last ? summary(steps.size(), passed, failed) : step.tool.getName() + " ok");
            }
            else {
                failed++;
                String reason = failureReason(entry);
                context.reportProgress(step.index + 1, steps.size(),
                    last ? summary(steps.size(), passed, failed)
                        : step.tool.getName() + " failed: " + reason);
                // A failed step does not fail the call, so it would otherwise only be visible to
                // whoever reads the reply. This puts it where a person watching the game sees it.
                context.log(McpLogLevel.WARNING, "client_sequence step " + step.index + " ("
                    + step.tool.getName() + ") failed: " + reason);
                if (firstError == null) {
                    firstError = summarizeFailure(entry);
                }
                if (stopOnFail) {
                    if (step.index + 1 < steps.size()) {
                        nextIndex = step.index + 1;
                    }
                    stopReason = "step " + step.index + " failed";
                    break;
                }
            }
        }

        JsonObject json = new JsonObject();
        json.addProperty("steps", steps.size());
        json.addProperty("ran", results.size());
        json.addProperty("passed", passed);
        json.addProperty("failed", failed);
        if (stopReason != null) {
            json.addProperty("stopReason", stopReason);
        }
        if (nextIndex != null) {
            json.addProperty("nextIndex", nextIndex);
        }
        // Up front, beside the counts: a caller looping over results for each step's 'result' skips
        // a failed step without noticing, and two whole-corridor scans reported "0 found" that way
        // before anyone saw the steps had failed (issue #48).
        if (firstError != null) {
            json.add("firstError", firstError);
        }
        json.add("results", results);
        return ToolResult.structured(json);
    }

    private static String summary(int ran, int passed, int failed) {
        return ran + " ran: " + passed + " passed" + (failed > 0 ? ", " + failed + " failed" : "");
    }

    /** Why a step failed, in one short line. */
    private static String failureReason(JsonObject entry) {
        String reason;
        if (entry.has("error")) {
            reason = entry.get("error").getAsString();
        }
        else if (entry.has("expectFailed")) {
            reason = entry.get("expectFailed").getAsJsonArray().get(0).getAsString();
        }
        else {
            reason = "unknown";
        }
        reason = reason.replace('\n', ' ').trim();
        return reason.length() <= REASON_CHARS ? reason : reason.substring(0, REASON_CHARS) + "…";
    }

    /** The failed step's index, tool and why it failed, without its result. */
    private static JsonObject summarizeFailure(JsonObject entry) {
        JsonObject failure = new JsonObject();
        failure.add("i", entry.get("i"));
        failure.add("tool", entry.get("tool"));
        if (entry.has("error")) {
            failure.add("error", entry.get("error"));
        }
        if (entry.has("expectFailed")) {
            failure.add("expectFailed", entry.get("expectFailed"));
        }
        return failure;
    }

    /**
     * Checks one step the way the dispatcher checks a call.
     *
     * @return the {@link Step}, or a String saying what is wrong with it
     */
    private static Object parse(ToolContext context, int index, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return "each step must be an object such as {\"look\": {\"yaw\": 90}}.";
        }
        JsonObject object = element.getAsJsonObject();
        String toolKey = null;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (EXPECT.equals(entry.getKey()) || QUIET.equals(entry.getKey())) {
                continue;
            }
            if (toolKey != null) {
                return "names two tools, '" + toolKey + "' and '" + entry.getKey() + "'; a step "
                    + "runs one.";
            }
            toolKey = entry.getKey();
        }
        if (toolKey == null) {
            return "names no tool.";
        }

        String name = toolKey.startsWith("client_") ? toolKey : "client_" + toolKey;
        if (!STEP_TOOLS.contains(name)) {
            // A real tool that is simply not allowed gets no guess: the closest allowed name to
            // "screenshot" is "select_slot", which helps nobody.
            String closest = McpRegistry.tool(name, context.getSide()) != null ? null
                : ArgumentNames.closest(name, STEP_TOOLS);
            return "'" + toolKey + "' cannot be a step"
                + (closest == null ? "." : "; did you mean '" + closest + "'?")
                + " Steps can be: " + String.join(", ", STEP_TOOLS) + ".";
        }
        McpTool tool = McpRegistry.tool(name, context.getSide());
        if (tool == null) {
            return "'" + name + "' is not available on this endpoint.";
        }

        JsonElement value = object.get(toolKey);
        JsonObject arguments;
        if (value == null || value.isJsonNull()) {
            arguments = new JsonObject();
        }
        else if (value.isJsonObject()) {
            arguments = value.getAsJsonObject();
        }
        else {
            return "the value of '" + toolKey + "' must be the tool's arguments, as an object.";
        }
        String unknown = ArgumentNames.describeUnknown(name, tool.getInputSchema(), arguments);
        if (unknown != null) {
            return unknown;
        }

        JsonElement expect = object.get(EXPECT);
        if (expect != null && !expect.isJsonNull() && !expect.isJsonObject()) {
            return "'expect' must be an object of the fields to check.";
        }
        boolean quiet = object.has(QUIET) && Json.getBoolean(object, QUIET, false);
        return new Step(index, tool, arguments,
            expect == null || expect.isJsonNull() ? null : expect.getAsJsonObject(), quiet);
    }

    /** Runs one step and describes the outcome. Only cancellation escapes. */
    private static JsonObject runStep(ToolContext context, Step step, int total) {
        JsonObject entry = new JsonObject();
        entry.addProperty("i", step.index);
        entry.addProperty("tool", step.tool.getName());

        ToolResult result;
        try {
            result = step.tool.call(context.forPart(step.arguments, step.index + STEP_START, total,
                step.tool.getName()));
        }
        catch (JsonRpcException e) {
            // The dispatcher's rule: cancellation stays a protocol error, everything else a handler
            // threw is a failed action. A timeout is a failed step here, not the end of the call.
            if (e.getCode() == JsonRpcException.REQUEST_CANCELLED) {
                throw e;
            }
            result = ToolResult.error(e.getMessage());
        }
        catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result = ToolResult.error("Tool '" + step.tool.getName() + "' failed: " + detail);
        }

        JsonObject payload = result.getStructuredContent();
        if (payload == null) {
            payload = new JsonObject();
            payload.addProperty("text", result.getText());
        }

        boolean ok = !result.isError();
        if (result.isError()) {
            entry.addProperty("error", result.getText());
        }
        else if (step.expect != null) {
            List<String> mismatches = JsonExpect.mismatches(step.expect, payload);
            if (!mismatches.isEmpty()) {
                ok = false;
                entry.add("expectFailed", Json.arrayOfStrings(mismatches));
            }
        }
        entry.addProperty("ok", ok);
        // A failed step always shows its result: that is what the caller needs to see next.
        if (!result.isError() && (!step.quiet || !ok)) {
            entry.add("result", payload);
        }
        return entry;
    }
}
