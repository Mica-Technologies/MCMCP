package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.perf.CallTree;
import com.micatechnologies.minecraft.mcmcp.perf.CommandProfile;
import com.micatechnologies.minecraft.mcmcp.perf.CommandSampler;
import com.micatechnologies.minecraft.mcmcp.perf.GameThreads;
import com.micatechnologies.minecraft.mcmcp.perf.TickClock;
import javax.annotation.Nullable;

/**
 * The {@code profile} argument of {@code server_run_command} and {@code server_run_commands} (#128):
 * the time each command took, the tick it ran in, and a stack sample of the server thread taken
 * while the commands ran.
 *
 * <p>{@code game_cpu_sample} could not answer "what did this command cost": it blocks for its whole
 * window, an agent's calls run one at a time, and so its sample was over before the command began.
 * Here the sampler runs on its own thread for the length of the call, and the game thread opens and
 * closes its window around the commands, so nothing else the server did is in the tree.
 *
 * <p>Absent {@code profile}, {@link #start} returns null and the tools pay one null check per
 * command. Everything here except {@link #begin}, {@link #end} and {@link #record} runs on the
 * caller's worker; those three run on the server thread and touch no game state.
 */
final class CommandProfiling {

    /** The wait for one game-thread task while profiling: a command being measured is the likely slow one. */
    static final long GAME_THREAD_TIMEOUT_MILLIS = 60_000L;

    /** How long to wait for the commands' last tick to finish before reporting its duration as unknown. */
    private static final long TICK_END_WAIT_MILLIS = 30_000L;

    static JsonSchema schema() {
        return JsonSchema.object()
            .integer("interval_ms", "Milliseconds between stack samples. Default 2.", 1, 20)
            .enumeration("scope", "'commands' (default): sample only while the commands run. 'tick': "
                + "also the rest of the tick they ran in, for work they set off that runs after them.",
                "commands", "tick")
            .integer("top", "Rows in hottestFrames, byPackage and slowestCommands. Default 15.", 1, 50)
            .number("min_percent", "Prune tree branches below this share. Default 2.", 0.1D, 50.0D)
            .integer("max_lines", "Cap on tree lines returned. Default 40.", 10, 300);
    }

    static final String DESCRIPTION = "Measure the commands. Adds tickMs (the tick they ran in), "
        + "commandMs (time inside each command), and 'sample': a stack sample of the server thread taken "
        + "only while they ran, shaped like game_cpu_sample's, its full tree written to 'file'. Pass {} "
        + "for the defaults.";

    private final CommandProfile.Options options;
    private final CommandProfile profile = new CommandProfile();
    @Nullable
    private final CommandSampler sampler;
    private boolean stopped;

    private CommandProfiling(CommandProfile.Options options, @Nullable CommandSampler sampler) {
        this.options = options;
        this.sampler = sampler;
    }

    /** Starts profiling when the call asked for it, or returns null when it did not. */
    @Nullable
    static CommandProfiling start(ToolContext context) {
        CommandProfile.Options options = CommandProfile.parse(context.getArguments().get("profile"));
        if (options == null) {
            return null;
        }
        long threadId = GameThreads.find(GameThreads.SERVER);
        CommandSampler sampler = threadId < 0L ? null
            : new CommandSampler(threadId, options.intervalMillis, options.wholeTick, TickClock.SERVER::count);
        if (sampler != null) {
            sampler.start();
        }
        return new CommandProfiling(options, sampler);
    }

    /** Server thread: the commands are about to run. */
    void begin() {
        if (sampler != null) {
            sampler.begin();
        }
    }

    /** Server thread: they have run. */
    void end() {
        if (sampler != null) {
            sampler.end();
        }
    }

    /** Server thread: one command took {@code nanos}, in the tick running now. */
    void record(int index, String command, long nanos) {
        profile.record(index, command, TickClock.SERVER.count(), nanos);
    }

    /** Stops the sampler without reporting; safe to call after {@link #finish}. For a finally. */
    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (sampler != null) {
            try {
                sampler.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * One line for a result whose text is the command's output: the figures, and the frame most of
     * the samples were in. The structured result is the whole answer; this is the glance.
     */
    static String summaryLine(JsonObject result) {
        StringBuilder line = new StringBuilder("Profile:");
        if (result.has("tickMs")) {
            line.append(" tick ").append(result.get("tickMs").getAsDouble()).append(" ms,");
        }
        line.append(" command ").append(Json.getDouble(result, "commandMs", 0.0D)).append(" ms");
        JsonObject sample = Json.getObject(result, "sample");
        if (sample != null) {
            line.append(", ").append(Json.getLong(sample, "samples", 0L)).append(" samples");
            JsonArray hottest = Json.getArray(sample, "hottestFrames");
            if (hottest != null && hottest.size() > 0 && hottest.get(0).isJsonObject()) {
                line.append("; hottest ").append(Json.getString(hottest.get(0).getAsJsonObject(), "frame", "?"));
            }
        }
        return line.append(". Full figures in the structured result.").toString();
    }

    /**
     * Waits for the commands' last tick to end, then adds the measurements to {@code into}. Worker
     * thread, after the commands' game-thread tasks have returned.
     */
    void finish(ToolContext context, JsonObject into) throws InterruptedException {
        long last = profile.lastTick();
        long deadline = System.nanoTime() + TICK_END_WAIT_MILLIS * 1_000_000L;
        while (last >= 0L && TickClock.SERVER.count() <= last && System.nanoTime() < deadline) {
            context.getCancellation().throwIfCancelled();
            Thread.sleep(2L);
        }
        stop();
        profile.addTo(into, TickClock.SERVER::durationOf, options.top);

        JsonObject sample = new JsonObject();
        if (sampler == null) {
            sample.addProperty("error", "No thread named '" + GameThreads.SERVER + "' to sample.");
            into.add("sample", sample);
            return;
        }
        CallTree tree = sampler.tree();
        sample.addProperty("scope", options.wholeTick ? "tick" : "commands");
        sample.addProperty("intervalMs", options.intervalMillis);
        sample.addProperty("samples", tree.samples());
        if (tree.samples() == 0L) {
            sample.addProperty("note", "No sample landed: the commands ran in less than one sampling interval. "
                + "commandMs is the measurement.");
            into.add("sample", sample);
            return;
        }
        if (tree.idleSamples() > 0L) {
            // Kept, not dropped as game_cpu_sample does: a command parked on a lock or on disk is
            // time that command took.
            sample.addProperty("idlePercent", Math.round(tree.idleSamples() * 1000.0D / tree.samples()) / 10.0D);
        }
        sample.add("hottestFrames", tree.hottestFrames(options.top));
        sample.add("byPackage", tree.byOwner(options.top));
        sample.addProperty("tree", tree.render(options.minPercent, options.maxLines));
        PerformanceTools.writeSampleFile(context, "command-sample", GameThreads.SERVER, tree,
            options.intervalMillis, sample);
        into.add("sample", sample);
    }
}
