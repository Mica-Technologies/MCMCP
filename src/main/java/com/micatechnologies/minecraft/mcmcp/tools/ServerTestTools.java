package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionPolicy;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.jobs.TestExpressions;
import com.micatechnologies.minecraft.mcmcp.json.ArgumentNames;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.server.MinecraftServer;

/**
 * {@code server_run_test}: a building test as one call, with a short verdict and the world put back
 * (#111).
 *
 * <h2>Why</h2>
 *
 * The acceptance tests for a building — ride every lift to every landing, prove the fire recall
 * parks every car, sound the alarms and reset the panel — were 10 to 40 agent turns each of send,
 * wait, read and reset, 30 to 80 per building, each turn re-reading a context of 300,000 tokens or
 * more. MCMCP's own replies were 1-3% of that; the turns were the cost. A script the knowledge base
 * keeps, run in one call, is one turn.
 *
 * <h2>What a script is</h2>
 *
 * Steps that are ordinary tool calls ({@code call}), with only the glue a test needs around them:
 * waiting until a result says something ({@code waitUntil}), loops over what a call found
 * ({@code forEach}), variables and {@code ${...}} templates ({@link TestExpressions}), checks that
 * pass or fail, and rows for the report. Nothing about elevators or alarms is in MCMCP: the script
 * names the blocks, NBT keys and commands, so MCMCP stays free of every mod it tests.
 *
 * <h2>The world is put back</h2>
 *
 * A {@code call} marked {@code restore: true} always leaves an undo point, and {@code holdItem}
 * remembers the slot it fills. All of them are undone at the end, last first, whether the script
 * finished, failed a check, hit an error, timed out or was cancelled, before {@code finally} steps
 * run. A redstone block left in a wall by a dropped connection was the failure this exists to end.
 * Only an emergency stop prevents it: then nothing is written, restores included, and the result
 * names what was left.
 *
 * <p>Every step is checked before the first one runs: a misspelt tool or argument in step nine does
 * not leave steps one to eight applied.
 */
public final class ServerTestTools {

    static final int DEFAULT_SECONDS = 300;
    static final int MAX_SECONDS = 1800;
    static final int MAX_ROWS = 500;
    static final int MAX_FAILURES_LISTED = 20;

    /** Tools a step may not call: itself, the job tools, and what stops the server or MCMCP. */
    private static final Set<String> NOT_STEPS = new HashSet<>(Arrays.asList(
        "server_run_test", "server_job_submit", "server_job_cancel", "server_job_pause", "server_job_resume",
        "server_stop", "server_estop"));

    private static final Set<String> VERBS = new LinkedHashSet<>(Arrays.asList(
        "call", "waitUntil", "sleep", "sleepUntil", "mark", "set", "forEach", "repeat", "if", "include", "check", "row",
        "holdItem", "releaseItem", "restoreNow"));

    private ServerTestTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_run_test")
            .title("Run a test script")
            .description("Run a building test in one call: a script of tool calls with waits, loops, "
                + "checks and report rows, then put the world back. Returns a short verdict: passed, the "
                + "failed checks, and a table of the rows the script recorded. For tests that are many "
                + "turns of send / wait / read / reset: ride every lift to every landing, trip a fire "
                + "panel and prove the cars recall, sound the alarms and reset.\n\n"
                + "Steps (each an object with one verb):\n"
                + "- {call: tool, args, save?: var, append?: list, restore?, optional?: true}: any server tool. "
                + "restore: true undoes what it wrote (its undo point) when the script ends, however it ends; "
                + "restore: [[x,y,z], {x,y,z,toX,toY,toZ}] instead reads those cells first and writes them "
                + "back, for a /setblock inside server_run_commands. save names may be templated.\n"
                + "- {waitUntil: {call, args, until, every?: s, timeout?: s}, save?}: poll until 'until' holds; "
                + "saves {met, seconds, polls, result}. until: \"${expr}\" with the result as 'result', or "
                + "{path, anyIn | equals | where | atLeast | none}, e.g. {path: 'blocks[*].y', anyIn: "
                + "['${c.y+1}']} or {path: 'tileEntities[*]', where: {'nbt.open': true}}.\n"
                + "- {forEach: '${ctl.blocks}', as?: 'it', steps}, {repeat: n, until?, steps}, {if: '${expr}', "
                + "then, else?}, {set: {var: value}}, {sleep: s}, {mark: name}, {sleepUntil: s, since?: mark} "
                + "(variables 'elapsed' and 'since.<mark>' are seconds), {include: name} runs defs.<name>.\n"
                + "- {check: '${expr}', label}: a pass or a fail in the verdict. {row: {col: value}}: a "
                + "report row.\n"
                + "- {holdItem: {player, slot, item, count?, meta?, nbt?}}: put an item in a hotbar slot, "
                + "given back at the end, or now with {releaseItem: {player, slot}}; a second holdItem in the "
                + "slot keeps the first original. nbt is SNBT. {restoreNow: true}: undo everything "
                + "restorable so far.\n"
                + "Strings take ${...}: paths (c.y, r.positions[*][1]), [lists], {maps} and indexing "
                + "(BACK[face][0]), arithmetic, comparisons, && || !, and len abs floor round min max sort "
                + "unique slice concat minBy maxBy meta. A step's error stops the script unless optional. "
                + "Every step is checked before any runs.\n\n"
                + "Long tests belong in a job: server_job_submit kind 'test' with these arguments, then "
                + "server_job_status for the verdict.")
            .schema(JsonSchema.object()
                .array("steps", "The script.", Json.obj("type", "object"))
                .array("finally", "Steps run after everything restorable is restored, however the script "
                    + "ended: resetting a panel, for instance.", Json.obj("type", "object"))
                .property("vars", Json.obj("type", "object"))
                .property("defs", Json.obj("type", "object"))
                .array("hold", "Chunk areas to keep loaded for the whole run, each [x0, z0, x1, z1].",
                    Json.obj("type", "array"))
                .integer("dimension", "Dimension of 'hold'. Default 0.")
                .integer("maxSeconds", "Stop after this long, restoring as usual. Default " + DEFAULT_SECONDS
                    + ".", 1, MAX_SECONDS)
                .bool("stopOnFail", "Stop at the first failed check. Default false.")
                .required("steps")
                .build())
            .serverOnly()
            .destructive()
            .handler(ServerTestTools::run)
            .build());
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    /** Why a script stopped before its end. */
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop(String message) {
            super(message, null, false, false);
        }
    }

    /** One run's state: variables, what to put back, and the verdict as it builds. */
    private static final class Run {
        final ToolContext context;
        final Map<String, JsonElement> vars = new HashMap<>();
        final Deque<Runnable> restores = new ArrayDeque<>();
        final Map<String, JsonArray> defs = new HashMap<>();
        /** When each mark was set, for since.name and sleepUntil. */
        final Map<String, Long> marks = new HashMap<>();
        /** Held hotbar slots, by "player/slot", so a second holdItem keeps the first original. */
        final Map<String, Runnable> heldSlots = new HashMap<>();
        final long startedMillis = System.currentTimeMillis();
        int includeDepth;
        final List<String> restoreNames = new ArrayList<>();
        final List<String> restoreFailures = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final List<String> columns = new ArrayList<>();
        final JsonArray rows = new JsonArray();
        final boolean stopOnFail;
        final long deadline;
        int calls;
        int passed;
        int failed;
        double progress;

        Run(ToolContext context, boolean stopOnFail, long deadline) {
            this.context = context;
            this.stopOnFail = stopOnFail;
            this.deadline = deadline;
        }
    }

    private static ToolResult run(ToolContext context) throws Exception {
        JsonArray steps = Json.getArray(context.getArguments(), "steps");
        if (steps == null || steps.size() == 0) {
            return ToolResult.error("'steps' is the script: an array of steps, each an object with one verb ("
                + String.join(", ", VERBS) + ").");
        }
        JsonArray finallySteps = Json.getArray(context.getArguments(), "finally");
        List<String> problems = new ArrayList<>();
        JsonObject defsArgument = Json.getObject(context.getArguments(), "defs");
        Set<String> defNames = new HashSet<>();
        if (defsArgument != null) {
            for (Map.Entry<String, JsonElement> entry : defsArgument.entrySet()) {
                defNames.add(entry.getKey());
            }
            for (Map.Entry<String, JsonElement> entry : defsArgument.entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    validate(context, entry.getValue().getAsJsonArray(), "defs." + entry.getKey(), problems, defNames);
                }
            }
        }
        validate(context, steps, "steps", problems, defNames);
        if (finallySteps != null) {
            validate(context, finallySteps, "finally", problems, defNames);
        }
        if (!problems.isEmpty()) {
            return ToolResult.error("Nothing was run. " + String.join(" ", problems.subList(0, Math.min(5, problems.size())))
                + (problems.size() > 5 ? " (and " + (problems.size() - 5) + " more)" : ""));
        }
        int seconds = context.getBoundedInt("maxSeconds", DEFAULT_SECONDS, 1, MAX_SECONDS);
        final Run run = new Run(context, context.getBoolean("stopOnFail", false),
            System.currentTimeMillis() + seconds * 1000L);
        JsonObject vars = Json.getObject(context.getArguments(), "vars");
        if (vars != null) {
            for (Map.Entry<String, JsonElement> entry : vars.entrySet()) {
                run.vars.put(entry.getKey(), entry.getValue());
            }
        }
        JsonObject defs = Json.getObject(context.getArguments(), "defs");
        if (defs != null) {
            for (Map.Entry<String, JsonElement> entry : defs.entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    return ToolResult.error("defs." + entry.getKey() + " is a list of steps.");
                }
                run.defs.put(entry.getKey(), entry.getValue().getAsJsonArray());
            }
        }
        Set<Long> chunks = new java.util.LinkedHashSet<>();
        JsonElement holdTemplate = context.getArguments().get("hold");
        JsonArray hold;
        try {
            // Templated, so a building's box from vars can be held with a margin: "${box[0]-4}".
            JsonElement resolved = holdTemplate == null ? null : TestExpressions.resolve(holdTemplate, run.vars);
            hold = resolved != null && resolved.isJsonArray() ? resolved.getAsJsonArray() : null;
        } catch (IllegalArgumentException e) {
            return ToolResult.error("'hold': " + e.getMessage());
        }
        if (hold != null) {
            for (JsonElement area : hold) {
                if (!area.isJsonArray() || area.getAsJsonArray().size() != 4) {
                    return ToolResult.error("Each 'hold' entry is [x0, z0, x1, z1]; got " + area + ".");
                }
                JsonArray box = area.getAsJsonArray();
                int x0 = box.get(0).getAsInt();
                int z0 = box.get(1).getAsInt();
                int x1 = box.get(2).getAsInt();
                int z1 = box.get(3).getAsInt();
                chunks.addAll(ChunkKeys.box(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1)));
            }
        }
        final long started = System.currentTimeMillis();
        return ServerChunkTools.withChunks(context, context.getInt("dimension", 0), chunks, !chunks.isEmpty(), outcome -> {
            String stopped = null;
            try {
                runSteps(run, steps);
            }
            catch (Stop stop) {
                stopped = stop.getMessage();
            }
            catch (RuntimeException e) {
                stopped = context.getCancellation().isCancelled() ? "cancelled"
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            // However it ended: put back what was marked, then the script's own finally.
            restoreAll(run);
            if (finallySteps != null && finallySteps.size() > 0) {
                Run after = new Run(cleanContext(context), false, System.currentTimeMillis() + 120_000L);
                after.vars.putAll(run.vars);
                try {
                    runSteps(after, finallySteps);
                }
                catch (RuntimeException e) {
                    run.warnings.add("finally stopped: " + e.getMessage());
                }
                restoreAll(after);
                run.failures.addAll(after.failures);
                run.failed += after.failed;
                run.passed += after.passed;
                run.restoreFailures.addAll(after.restoreFailures);
            }
            JsonObject json = new JsonObject();
            json.addProperty("passed", stopped == null && run.failed == 0);
            json.addProperty("checksPassed", run.passed);
            json.addProperty("checksFailed", run.failed);
            if (!run.failures.isEmpty()) {
                json.add("failures", Json.arrayOfStrings(run.failures.subList(0,
                    Math.min(MAX_FAILURES_LISTED, run.failures.size()))));
            }
            if (stopped != null) {
                json.addProperty("stopped", stopped);
            }
            json.addProperty("calls", run.calls);
            json.addProperty("seconds", Math.round((System.currentTimeMillis() - started) / 100.0D) / 10.0D);
            if (run.rows.size() > 0) {
                JsonObject table = new JsonObject();
                table.add("columns", Json.arrayOfStrings(run.columns));
                table.add("rows", run.rows);
                json.add("table", table);
            }
            json.addProperty("restored", run.restoreNames.size() - run.restoreFailures.size());
            if (!run.restoreFailures.isEmpty()) {
                json.add("restoreFailures", Json.arrayOfStrings(run.restoreFailures));
            }
            if (!run.warnings.isEmpty()) {
                json.add("warnings", Json.arrayOfStrings(run.warnings));
            }
            return ToolResult.structured(json);
        });
    }

    private static void runSteps(Run run, JsonArray steps) {
        for (JsonElement element : steps) {
            if (System.currentTimeMillis() > run.deadline) {
                throw new Stop("maxSeconds reached");
            }
            run.context.getCancellation().throwIfCancelled();
            tickClocks(run);
            runStep(run, element.getAsJsonObject());
        }
    }

    /** elapsed: seconds since the script began; since.name: seconds since that mark. */
    private static void tickClocks(Run run) {
        long now = System.currentTimeMillis();
        run.vars.put("elapsed", new JsonPrimitive(Math.round((now - run.startedMillis) / 100.0D) / 10.0D));
        JsonObject since = new JsonObject();
        for (Map.Entry<String, Long> mark : run.marks.entrySet()) {
            since.addProperty(mark.getKey(), Math.round((now - mark.getValue()) / 100.0D) / 10.0D);
        }
        run.vars.put("since", since);
    }

    private static void runStep(Run run, JsonObject step) {
        String verb = verbOf(step);
        switch (verb) {
            case "call": {
                JsonElement restore = step.get("restore");
                boolean undoPoint = restore != null && restore.isJsonPrimitive() && TestExpressions.truthy(restore);
                if (restore != null && (restore.isJsonObject() || restore.isJsonArray())) {
                    // Cells named outright, for a call whose writes no undo point can see: a /setblock
                    // inside server_run_commands. Read before the call, written back at the end.
                    snapshotCells(run, TestExpressions.resolve(restore, run.vars), step.get("call").getAsString());
                }
                JsonElement result = call(run, step.get("call").getAsString(),
                    resolveObject(run, step.get("args")), undoPoint, Json.getBoolean(step, "optional", false));
                save(run, step, result);
                return;
            }
            case "mark": {
                run.marks.put(TestExpressions.display(TestExpressions.resolve(step.get("mark"), run.vars)),
                    System.currentTimeMillis());
                return;
            }
            case "sleepUntil": {
                // {sleepUntil: 40, since: 'alarm'}: until 40 s after the mark, or after the start.
                double seconds = TestExpressions.resolve(step.get("sleepUntil"), run.vars).getAsDouble();
                String mark = Json.getString(step, "since", null);
                Long from = mark == null ? Long.valueOf(run.startedMillis) : run.marks.get(mark);
                if (from == null) {
                    throw new Stop("sleepUntil since '" + mark + "', and no step has marked it.");
                }
                double left = (from + seconds * 1000.0D - System.currentTimeMillis()) / 1000.0D;
                if (left > 0) {
                    sleep(run, Math.min(1800.0D, left));
                }
                return;
            }
            case "repeat": {
                // {repeat: 3, until: '${...}', steps}: the steps once, then again while until is false.
                int times = Math.max(1, Math.min(1000, TestExpressions.resolve(step.get("repeat"), run.vars).getAsInt()));
                JsonArray body = step.getAsJsonArray("steps");
                for (int i = 0; i < times; i++) {
                    run.vars.put("repeatIndex", new JsonPrimitive(i));
                    runSteps(run, body);
                    if (step.has("until") && TestExpressions.truthy(TestExpressions.resolve(step.get("until"), run.vars))) {
                        return;
                    }
                }
                return;
            }
            case "include": {
                String name = Json.getString(step, "include", "");
                JsonArray body = run.defs.get(name);
                if (body == null) {
                    throw new Stop("No defs." + name + ".");
                }
                if (++run.includeDepth > 16) {
                    throw new Stop("include nested more than 16 deep: defs." + name + " includes itself?");
                }
                try {
                    runSteps(run, body);
                }
                finally {
                    run.includeDepth--;
                }
                return;
            }
            case "releaseItem": {
                JsonObject spec = resolveObject(run, step.get("releaseItem"));
                String key = Json.getString(spec, "player", "") + "/" + Json.getInt(spec, "slot", -1);
                Runnable giveBack = run.heldSlots.remove(key.toLowerCase(java.util.Locale.ROOT));
                if (giveBack == null) {
                    run.warnings.add("releaseItem " + key + ": that slot was not held.");
                }
                else {
                    run.restores.remove(giveBack);
                    giveBack.run();
                }
                return;
            }
            case "waitUntil": {
                save(run, step, waitUntil(run, step.getAsJsonObject("waitUntil")));
                return;
            }
            case "sleep": {
                double seconds = TestExpressions.resolve(step.get("sleep"), run.vars).getAsDouble();
                sleep(run, Math.max(0.0D, Math.min(600.0D, seconds)));
                return;
            }
            case "set": {
                for (Map.Entry<String, JsonElement> entry : step.getAsJsonObject("set").entrySet()) {
                    run.vars.put(entry.getKey(), TestExpressions.resolve(entry.getValue(), run.vars));
                }
                return;
            }
            case "forEach": {
                JsonElement list = TestExpressions.resolve(step.get("forEach"), run.vars);
                if (!list.isJsonArray()) {
                    throw new Stop("forEach " + step.get("forEach") + " is not a list: " + list);
                }
                String as = Json.getString(step, "as", "it");
                JsonArray body = step.getAsJsonArray("steps");
                int index = 0;
                for (JsonElement item : list.getAsJsonArray()) {
                    run.vars.put(as, item);
                    run.vars.put(as + "Index", new JsonPrimitive(index++));
                    runSteps(run, body);
                }
                return;
            }
            case "if": {
                boolean holds = TestExpressions.truthy(TestExpressions.resolve(step.get("if"), run.vars));
                JsonArray branch = holds ? Json.getArray(step, "then") : Json.getArray(step, "else");
                if (branch != null) {
                    runSteps(run, branch);
                }
                return;
            }
            case "check": {
                boolean holds = TestExpressions.truthy(TestExpressions.resolve(step.get("check"), run.vars));
                String label = TestExpressions.display(TestExpressions.resolve(
                    step.has("label") ? step.get("label") : step.get("check"), run.vars));
                if (holds) {
                    run.passed++;
                }
                else {
                    run.failed++;
                    run.failures.add(label);
                    if (run.stopOnFail) {
                        throw new Stop("check failed: " + label);
                    }
                }
                return;
            }
            case "row": {
                if (run.rows.size() >= MAX_ROWS) {
                    return;
                }
                JsonObject row = resolveObject(run, step.get("row"));
                for (Map.Entry<String, JsonElement> entry : row.entrySet()) {
                    if (!run.columns.contains(entry.getKey())) {
                        run.columns.add(entry.getKey());
                    }
                }
                JsonArray cells = new JsonArray();
                for (String column : run.columns) {
                    cells.add(row.has(column) ? row.get(column) : com.google.gson.JsonNull.INSTANCE);
                }
                run.rows.add(cells);
                return;
            }
            case "holdItem": {
                holdItem(run, resolveObject(run, step.get("holdItem")));
                return;
            }
            case "restoreNow": {
                restoreAll(run);
                return;
            }
            default:
                throw new Stop("Unknown step " + step);
        }
    }

    /** save: a name, templated ("sent_${car.id}"); append: a list the value is added to. */
    private static void save(Run run, JsonObject step, JsonElement value) {
        if (step.has("save")) {
            String name = TestExpressions.display(TestExpressions.resolve(step.get("save"), run.vars));
            if (!name.isEmpty()) {
                run.vars.put(name, value);
            }
        }
        if (step.has("append")) {
            String name = TestExpressions.display(TestExpressions.resolve(step.get("append"), run.vars));
            JsonElement list = run.vars.get(name);
            if (list == null || !list.isJsonArray()) {
                list = new JsonArray();
                run.vars.put(name, list);
            }
            list.getAsJsonArray().add(value);
        }
    }

    /** Most cells a restore may name outright: it is a test's few switch cells, not a region. */
    static final int MAX_RESTORE_CELLS = 4096;

    /**
     * Reads named cells, block, metadata and tile-entity NBT, before a call, and pushes a restore
     * that writes them back as they were.
     */
    private static void snapshotCells(Run run, JsonElement spec, String tool) {
        final List<int[]> cells = new ArrayList<>();
        List<JsonElement> items = new ArrayList<>();
        if (spec.isJsonArray()) {
            spec.getAsJsonArray().forEach(items::add);
        }
        else {
            items.add(spec);
        }
        for (JsonElement item : items) {
            if (item.isJsonArray() && item.getAsJsonArray().size() == 3) {
                JsonArray at = item.getAsJsonArray();
                cells.add(new int[] {at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt()});
            }
            else if (item.isJsonObject() && item.getAsJsonObject().has("x")) {
                JsonObject box = item.getAsJsonObject();
                int x = Json.getInt(box, "x", 0);
                int y = Json.getInt(box, "y", 0);
                int z = Json.getInt(box, "z", 0);
                int toX = Json.getInt(box, "toX", x);
                int toY = Json.getInt(box, "toY", y);
                int toZ = Json.getInt(box, "toZ", z);
                long volume = (long) (Math.abs(toX - x) + 1) * (Math.abs(toY - y) + 1) * (Math.abs(toZ - z) + 1);
                if (volume + cells.size() > MAX_RESTORE_CELLS) {
                    throw new Stop("restore names more than " + MAX_RESTORE_CELLS + " cells; mark the call restore: "
                        + "true instead if it leaves an undo point.");
                }
                for (int cy = Math.min(y, toY); cy <= Math.max(y, toY); cy++) {
                    for (int cz = Math.min(z, toZ); cz <= Math.max(z, toZ); cz++) {
                        for (int cx = Math.min(x, toX); cx <= Math.max(x, toX); cx++) {
                            cells.add(new int[] {cx, cy, cz});
                        }
                    }
                }
            }
            else {
                throw new Stop("restore is true, a cell [x, y, z] or {x, y, z, toX?, toY?, toZ?}, or a list of them; got "
                    + item + ".");
            }
        }
        if (cells.size() > MAX_RESTORE_CELLS) {
            throw new Stop("restore names more than " + MAX_RESTORE_CELLS + " cells.");
        }
        String denied = run.context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
            "restore with cells writes them back directly, which needs permissions.allowWorldEdits.");
        if (denied != null) {
            throw new Stop(denied);
        }
        Principal principal = run.context.getPrincipal();
        if (principal.isCompanion() && !principal.has(Principal.CLASS_WRITE)) {
            throw new Stop("restore with cells writes blocks, and your companion access has no write class.");
        }
        final int dimension = run.context.getInt("dimension", 0);
        final Object[] before = run.context.onGameThread(() -> {
            net.minecraft.world.WorldServer world = ServerWorldTools.requireWorld(dimension);
            Object[] saved = new Object[cells.size() * 2];
            for (int i = 0; i < cells.size(); i++) {
                net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(cells.get(i)[0], cells.get(i)[1], cells.get(i)[2]);
                if (!world.isBlockLoaded(pos)) {
                    throw new IllegalStateException("restore cell " + pos + " is in an unloaded chunk; hold it first.");
                }
                // As plain values: no Minecraft object leaves the game thread.
                saved[i * 2] = net.minecraft.block.Block.getStateId(world.getBlockState(pos));
                net.minecraft.tileentity.TileEntity entity = world.getTileEntity(pos);
                saved[i * 2 + 1] = entity == null ? null : entity.writeToNBT(new net.minecraft.nbt.NBTTagCompound()).toString();
            }
            return saved;
        });
        final String what = cells.size() + " cell(s) before " + tool;
        run.restoreNames.add(what);
        run.restores.push(() -> {
            try {
                cleanContext(run.context).onGameThread((Callable<Void>) () -> {
                    net.minecraft.world.WorldServer world = ServerWorldTools.requireWorld(dimension);
                    for (int i = 0; i < cells.size(); i++) {
                        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(cells.get(i)[0], cells.get(i)[1], cells.get(i)[2]);
                        if (!world.isBlockLoaded(pos)) {
                            throw new IllegalStateException(pos + " was unloaded before it could be put back");
                        }
                        net.minecraft.block.state.IBlockState state = net.minecraft.block.Block.getStateById((Integer) before[i * 2]);
                        if (world.getBlockState(pos) != state) {
                            world.setBlockState(pos, state, 3);
                        }
                        String snbt = (String) before[i * 2 + 1];
                        net.minecraft.nbt.NBTTagCompound tag = snbt == null ? null : JsonToNBT.getTagFromJson(snbt);
                        net.minecraft.tileentity.TileEntity entity = tag == null ? null : world.getTileEntity(pos);
                        if (entity != null) {
                            entity.readFromNBT(tag);
                            entity.markDirty();
                            world.notifyBlockUpdate(pos, state, state, 3);
                        }
                    }
                    return null;
                });
            }
            catch (RuntimeException e) {
                run.restoreFailures.add(what + ": " + e.getMessage());
            }
        });
    }

    private static JsonObject resolveObject(Run run, @Nullable JsonElement template) {
        if (template == null || template.isJsonNull()) {
            return new JsonObject();
        }
        JsonElement resolved = TestExpressions.resolve(template, run.vars);
        if (!resolved.isJsonObject()) {
            throw new Stop("Expected an object, got " + resolved);
        }
        return resolved.getAsJsonObject();
    }

    /** One tool call as its own call: the tool's own handler, checks and limits. */
    private static JsonElement call(Run run, String name, JsonObject args, boolean restore, boolean optional) {
        McpTool tool = McpRegistry.tool(name, McmcpSide.SERVER);
        ToolContext part = nested(run.context, args, tool);
        part.setUndoRequired(restore);
        run.calls++;
        run.context.reportProgress(run.progress += 1.0D, -1.0D, "Step " + (int) run.progress + ": " + name);
        ToolResult result;
        try {
            result = tool.call(part);
        }
        catch (Exception e) {
            if (run.context.getCancellation().isCancelled()) {
                throw new Stop("cancelled");
            }
            result = ToolResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        JsonObject structured = result.getStructuredContent();
        if (restore && !result.isError()) {
            final String point = structured == null ? null : Json.getString(structured, "undoPoint", null);
            if (point == null) {
                run.warnings.add(name + " was marked restore but left no undo point, so nothing of it is put back.");
            }
            else {
                run.restoreNames.add(point);
                run.restores.push(() -> {
                    JsonObject undo = new JsonObject();
                    undo.addProperty("op", "restore");
                    undo.addProperty("id", point);
                    undo.addProperty("force", true);
                    McpTool undoTool = McpRegistry.tool("server_undo", McmcpSide.SERVER);
                    ToolResult restored;
                    try {
                        restored = undoTool.call(nested(cleanContext(run.context), undo, undoTool));
                    }
                    catch (Exception e) {
                        restored = ToolResult.error(String.valueOf(e.getMessage()));
                    }
                    if (restored.isError()) {
                        run.restoreFailures.add(point + " (" + name + "): " + restored.getText());
                    }
                });
            }
        }
        if (result.isError()) {
            if (!optional) {
                throw new Stop(name + " failed: " + result.getText());
            }
            JsonObject failed = new JsonObject();
            failed.addProperty("error", result.getText());
            return failed;
        }
        if (structured != null) {
            return structured;
        }
        JsonObject text = new JsonObject();
        text.addProperty("text", result.getText());
        return text;
    }

    private static JsonElement waitUntil(Run run, JsonObject spec) {
        String name = spec.get("call").getAsString();
        JsonElement until = spec.get("until");
        double every = Math.max(0.05D, Math.min(60.0D, Json.getDouble(spec, "every", 0.5D)));
        double timeout = Math.max(0.0D, Math.min(600.0D, Json.getDouble(spec, "timeout", 30.0D)));
        long started = System.currentTimeMillis();
        long end = started + (long) (timeout * 1000.0D);
        int polls = 0;
        JsonElement last;
        boolean met;
        while (true) {
            last = call(run, name, resolveObject(run, spec.get("args")), false, true);
            polls++;
            met = TestExpressions.test(until, last, run.vars);
            if (met || System.currentTimeMillis() >= end || System.currentTimeMillis() > run.deadline) {
                break;
            }
            sleep(run, every);
        }
        JsonObject saved = new JsonObject();
        saved.addProperty("met", met);
        saved.addProperty("seconds", Math.round((System.currentTimeMillis() - started) / 10.0D) / 100.0D);
        saved.addProperty("polls", polls);
        saved.add("result", last);
        return saved;
    }

    private static void sleep(Run run, double seconds) {
        long end = System.currentTimeMillis() + (long) (seconds * 1000.0D);
        try {
            while (System.currentTimeMillis() < end) {
                run.context.getCancellation().throwIfCancelled();
                Thread.sleep(Math.min(250L, Math.max(1L, end - System.currentTimeMillis())));
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Stop("interrupted");
        }
    }

    private static void holdItem(Run run, JsonObject spec) {
        final String playerName = Json.getString(spec, "player", "");
        final int slot = Json.getInt(spec, "slot", -1);
        final String itemId = Json.getString(spec, "item", "");
        Principal principal = run.context.getPrincipal();
        if (principal.isCompanion() && !playerName.equalsIgnoreCase(principal.getPlayerName())) {
            throw new Stop("holdItem through the companion fills only your own hotbar.");
        }
        String denied = run.context.refusal(Capability.WORLD_EDITS, McmcpConfig.isAllowWorldEdits(),
            "holdItem changes a player's inventory directly, which needs permissions.allowWorldEdits.");
        if (denied != null) {
            throw new Stop(denied);
        }
        if (slot < 0 || slot > 8) {
            throw new Stop("holdItem 'slot' is a hotbar slot, 0-8.");
        }
        final Item item = Item.getByNameOrId(itemId);
        if (item == null) {
            throw new Stop("holdItem: no item '" + itemId + "'.");
        }
        final int count = Math.max(1, Math.min(64, Json.getInt(spec, "count", 1)));
        final int meta = Math.max(0, Json.getInt(spec, "meta", 0));
        final String nbt = Json.getString(spec, "nbt", null);
        final String key = (playerName + "/" + slot).toLowerCase(java.util.Locale.ROOT);
        final boolean alreadyHeld = run.heldSlots.containsKey(key);
        // The slot's own item, as SNBT: no Minecraft object leaves the game thread.
        final String[] before = new String[1];
        run.context.onGameThread((Callable<Void>) () -> {
            EntityPlayerMP player = player(playerName);
            ItemStack stack = new ItemStack(item, count, meta);
            if (nbt != null && !nbt.isEmpty()) {
                stack.setTagCompound(JsonToNBT.getTagFromJson(nbt));
            }
            before[0] = player.inventory.getStackInSlot(slot).writeToNBT(new net.minecraft.nbt.NBTTagCompound()).toString();
            player.inventory.setInventorySlotContents(slot, stack);
            player.inventoryContainer.detectAndSendChanges();
            return null;
        });
        if (alreadyHeld) {
            // A second item in a held slot (the stick, then the config tool): the slot still goes back
            // to what it held before the first, once.
            return;
        }
        final String what = "slot " + slot + " of " + playerName;
        run.restoreNames.add(what);
        Runnable giveBack = new Runnable() {
            @Override
            public void run() {
                try {
                    cleanContext(run.context).onGameThread((Callable<Void>) () -> {
                        EntityPlayerMP player = player(playerName);
                        player.inventory.setInventorySlotContents(slot, new ItemStack(JsonToNBT.getTagFromJson(before[0])));
                        player.inventoryContainer.detectAndSendChanges();
                        return null;
                    });
                }
                catch (RuntimeException e) {
                    run.restoreFailures.add(what + ": " + e.getMessage());
                }
                run.heldSlots.remove(key);
            }
        };
        run.heldSlots.put(key, giveBack);
        run.restores.push(giveBack);
    }

    private static EntityPlayerMP player(String name) {
        MinecraftServer server = ServerThreadBridge.server();
        EntityPlayerMP player = server == null ? null : server.getPlayerList().getPlayerByUsername(name);
        if (player == null) {
            throw new IllegalStateException("No player named '" + name + "' is connected.");
        }
        return player;
    }

    private static void restoreAll(Run run) {
        while (!run.restores.isEmpty()) {
            run.restores.pop().run();
        }
    }

    // ------------------------------------------------------------------
    // Contexts
    // ------------------------------------------------------------------

    /**
     * A step's context: the script's session, caller and chunk holds, and its game-thread gate, so a
     * job's pause or cancel lands at the next step's next game-thread task.
     */
    private static ToolContext nested(final ToolContext whole, JsonObject args, McpTool tool) {
        ToolContext part = new ToolContext(whole.getSession(), args, whole.getGameThread(), whole.getCancellation(),
            McmcpConfig.getGameThreadTimeoutMillis(), null) {
            @Override
            public <T> T onGameThread(Callable<T> task) {
                return whole.onGameThread(task);
            }

            @Override
            public void reportProgress(double progress, double total, @Nullable String message) {
                // The script reports its own progress, one step at a time.
            }
        };
        part.setCaller(whole.getCaller());
        part.setHoldCaller(whole.getHoldCaller());
        part.setMutating(!tool.isReadOnly());
        return part;
    }

    /**
     * A context for putting things back after the script ended, cancelled or not: its own
     * cancellation, so a cancelled job still restores. An emergency stop still refuses it.
     */
    private static ToolContext cleanContext(ToolContext whole) {
        ToolContext clean = new ToolContext(whole.getSession(), new JsonObject(), whole.getGameThread(),
            new McpSession.CancellationToken(), McmcpConfig.getGameThreadTimeoutMillis(), null);
        clean.setCaller(whole.getCaller());
        clean.setHoldCaller(whole.getHoldCaller());
        clean.setMutating(true);
        return clean;
    }

    // ------------------------------------------------------------------
    // Checking before running
    // ------------------------------------------------------------------

    private static String verbOf(JsonObject step) {
        for (String verb : VERBS) {
            if (step.has(verb)) {
                return verb;
            }
        }
        return "";
    }

    private static void validate(ToolContext context, JsonArray steps, String where, List<String> problems,
                                 Set<String> defNames) {
        for (int i = 0; i < steps.size(); i++) {
            String at = where + "[" + i + "]";
            JsonElement element = steps.get(i);
            if (!element.isJsonObject()) {
                problems.add(at + " is not an object.");
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            String verb = verbOf(step);
            if (verb.isEmpty()) {
                problems.add(at + " has no verb; it is one of " + String.join(", ", VERBS) + ".");
                continue;
            }
            switch (verb) {
                case "call":
                    validateCall(context, step.get("call"), step.get("args"), at, problems);
                    break;
                case "waitUntil": {
                    JsonObject spec = Json.getObject(step, "waitUntil");
                    if (spec == null || !spec.has("until")) {
                        problems.add(at + ": waitUntil is {call, args, until, every?, timeout?}.");
                    }
                    else {
                        validateCall(context, spec.get("call"), spec.get("args"), at, problems);
                    }
                    break;
                }
                case "include": {
                    String name = Json.getString(step, "include", "");
                    if (!defNames.contains(name)) {
                        problems.add(at + ": no defs." + name + " to include; defined: " + defNames + ".");
                    }
                    break;
                }
                case "forEach":
                case "repeat":
                    if (Json.getArray(step, "steps") == null) {
                        problems.add(at + ": " + verb + " needs 'steps'.");
                    }
                    else {
                        validate(context, step.getAsJsonArray("steps"), at + ".steps", problems, defNames);
                    }
                    break;
                case "if":
                    if (Json.getArray(step, "then") != null) {
                        validate(context, step.getAsJsonArray("then"), at + ".then", problems, defNames);
                    }
                    if (Json.getArray(step, "else") != null) {
                        validate(context, step.getAsJsonArray("else"), at + ".else", problems, defNames);
                    }
                    break;
                case "set":
                    if (Json.getObject(step, "set") == null) {
                        problems.add(at + ": set is {name: value, ...}.");
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static void validateCall(ToolContext context, @Nullable JsonElement nameElement,
                                     @Nullable JsonElement args, String at, List<String> problems) {
        String name = nameElement == null || !nameElement.isJsonPrimitive() ? "" : nameElement.getAsString();
        if (NOT_STEPS.contains(name)) {
            problems.add(at + ": " + name + " cannot be a step.");
            return;
        }
        McpTool tool = McpRegistry.tool(name, McmcpSide.SERVER);
        if (tool == null) {
            problems.add(at + ": no server tool '" + name + "'.");
            return;
        }
        Principal principal = context.getPrincipal();
        if (principal.isCompanion()) {
            String callClass = CompanionPolicy.classOf(name);
            if (callClass == null || !principal.has(callClass)) {
                problems.add(at + ": your companion access does not include " + name + ".");
                return;
            }
        }
        if (args != null && !args.isJsonObject()) {
            problems.add(at + ": 'args' is an object.");
            return;
        }
        String unknown = ArgumentNames.describeUnknown(name, tool.getInputSchema(),
            args == null ? new JsonObject() : args.getAsJsonObject());
        if (unknown != null) {
            problems.add(at + ": " + unknown);
        }
    }
}
