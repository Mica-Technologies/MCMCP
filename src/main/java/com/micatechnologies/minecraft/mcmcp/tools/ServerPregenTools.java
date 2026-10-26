package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkPregen;
import com.micatechnologies.minecraft.mcmcp.chunkload.PregenPlan;
import com.micatechnologies.minecraft.mcmcp.game.ServerLoad;
import com.micatechnologies.minecraft.mcmcp.game.ServerTickRecorder;
import com.micatechnologies.minecraft.mcmcp.jobs.JobParts;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.WorldServer;

/**
 * Chunk pregeneration, throttled (#138).
 *
 * <p>The only pregenerator on a big modded server was Forge Essentials' {@code /pregen}, driven over
 * chat. Its tick saved the whole world and queued every chunk to unload before each chunk it
 * generated. That took the server to 1-7 TPS, and it crashed. Here the pace is a number of chunks per
 * tick, the server's health is waited on, the world's loaded chunk count has a ceiling, and the only
 * chunks given back are the ones the pass loaded. See {@link PregenPlan} for the order and why it
 * populates as it goes, and {@link ChunkPregen} for what touches the world.
 */
public final class ServerPregenTools {

    /** Chunks one call covers; more is a job. 32 x 32. */
    static final int MAX_CHUNKS = 1024;
    static final int MAX_PER_TICK = 8;
    /** The default ceiling sits this far above what was loaded when the call began. */
    private static final int DEFAULT_HEADROOM = 512;

    private ServerPregenTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_pregen_chunks")
            .title("Pregenerate chunks")
            .description("Generate the chunks of an area that do not exist yet, at a set pace, so players and "
                + "builds there do not pay for world generation later. Give x, z, toX, toZ (blocks), or "
                + "centerX, centerZ and radius (a square). Chunks already on disk are skipped, so running it "
                + "again resumes. It also populates them (trees, ores, mod world generation), loading a "
                + "neighbour on disk where population needs it and one chunk past the +x and +z edges. It "
                + "gives back only the chunks it loaded, a row at a time, and never saves or unloads the "
                + "world as a whole. It waits while the server is struggling or the world holds more than "
                + "maxLoadedChunks. At most " + MAX_CHUNKS + " chunks a call: for more, use server_job_submit "
                + "kind 'pregen' with these arguments, which runs in the background and survives restarts "
                + "(submit it again to resume).")
            .schema(JsonSchema.object()
                .integer("x", "X of one corner, in blocks.")
                .integer("z", "Z of one corner, in blocks.")
                .integer("toX", "X of the opposite corner. Defaults to x.")
                .integer("toZ", "Z of the opposite corner. Defaults to z.")
                .integer("centerX", "Instead of a box: the centre's X, in blocks.")
                .integer("centerZ", "Instead of a box: the centre's Z, in blocks.")
                .integer("radius", "Instead of a box: blocks from the centre to each edge of the square.", 0,
                    JobParts.MAX_PREGEN_RADIUS)
                .integer("dimension", "Dimension id. Defaults to 0.")
                .integer("chunksPerTick", "Chunks touched per server tick. Default 1.", 1, MAX_PER_TICK)
                .integer("maxLoadedChunks", "Wait while the dimension has more chunks loaded than this. Default: "
                    + "what was loaded when the call began, plus " + DEFAULT_HEADROOM + ".", 64, 1_000_000)
                .build())
            .serverOnly()
            .idempotent()
            .handler(ServerPregenTools::pregen)
            .build());
    }

    private static ToolResult pregen(final ToolContext context) throws Exception {
        final int[] box;
        try {
            box = JobParts.pregenBox(context.getArguments());
        }
        catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        final PregenPlan plan = PregenPlan.ofBlocks(box[0], box[1], box[2], box[3]);
        if (plan.chunks() > MAX_CHUNKS) {
            return ToolResult.error("That area is " + plan.chunks() + " chunks; one call covers at most " + MAX_CHUNKS
                + ". Submit it as a background job instead: server_job_submit with kind 'pregen' and these "
                + "arguments as 'args'.");
        }
        final int dimension = context.getInt("dimension", 0);
        final int perTick = context.getBoundedInt("chunksPerTick", 1, 1, MAX_PER_TICK);

        final Object[] setup = context.onGameThread(() -> {
            WorldServer world = ServerWorldTools.requireWorld(dimension);
            if (world.disableLevelSaving) {
                return new Object[] {"Saving is off in dimension " + dimension + " (save-off), so no chunk the pass "
                    + "loads could be unloaded again. Turn saving on (save-on) and try again."};
            }
            List<PregenPlan.Step> steps = plan.steps((x, z) -> ChunkPregen.exists(world, x, z));
            return new Object[] {null, steps, world.getChunkProvider().getLoadedChunkCount()};
        });
        if (setup[0] != null) {
            return ToolResult.error((String) setup[0]);
        }
        @SuppressWarnings("unchecked")
        final List<PregenPlan.Step> steps = (List<PregenPlan.Step>) setup[1];
        final int maxLoaded = context.has("maxLoadedChunks")
            ? context.getBoundedInt("maxLoadedChunks", 0, 64, 1_000_000)
            : (Integer) setup[2] + DEFAULT_HEADROOM;

        // generated, loaded to populate, already loaded, released, waits for health, waits for the ceiling
        final long[] totals = new long[6];
        final int[] cursor = {0};
        final Set<Long> ours = new HashSet<>();
        String lastWait = null;
        long startedAt = System.nanoTime();
        long lastTick = -1L;
        try {
            while (cursor[0] < steps.size()) {
                context.getCancellation().throwIfCancelled();
                if (ServerLoad.level() != ServerLoad.Level.OK) {
                    totals[4]++;
                    lastWait = "the server is " + ServerLoad.describe();
                    context.awaitBudget(1000L, "Waiting: " + lastWait);
                    continue;
                }
                // At most one step a tick, so chunksPerTick means what it says.
                long tick = ServerTickRecorder.tickCount();
                if (tick == lastTick) {
                    Thread.sleep(5L);
                    continue;
                }
                Integer over = context.onGameThread(() -> {
                    WorldServer world = ServerWorldTools.requireWorld(dimension);
                    int loaded = world.getChunkProvider().getLoadedChunkCount();
                    if (loaded > maxLoaded) {
                        return loaded;
                    }
                    for (int n = 0; n < perTick && cursor[0] < steps.size(); n++) {
                        PregenPlan.Step step = steps.get(cursor[0]);
                        totals[3] += ChunkPregen.release(world, ours, step.z);
                        switch (ChunkPregen.touch(world, step, ours)) {
                            case GENERATED:
                                totals[0]++;
                                break;
                            case LOADED:
                                totals[1]++;
                                break;
                            default:
                                totals[2]++;
                        }
                        cursor[0]++;
                    }
                    return null;
                });
                lastTick = ServerTickRecorder.tickCount();
                if (over != null) {
                    totals[5]++;
                    lastWait = "the dimension has " + over + " chunks loaded, over maxLoadedChunks " + maxLoaded;
                    context.awaitBudget(1000L, "Waiting: " + lastWait);
                    continue;
                }
                if (cursor[0] % 16 == 0 || cursor[0] == steps.size()) {
                    context.reportProgress(cursor[0], steps.size(), "Generated " + totals[0] + ", touched " + cursor[0]
                        + " of " + steps.size() + " chunks");
                }
            }
        }
        finally {
            if (!ours.isEmpty()) {
                // Finishes what the steps started: queued first, and not dropped for a cancel.
                totals[3] += context.onGameThreadFollowUp(
                    () -> ChunkPregen.release(ServerWorldTools.requireWorld(dimension), ours, null));
            }
        }

        long millis = (System.nanoTime() - startedAt) / 1_000_000L;
        JsonObject json = new JsonObject();
        json.addProperty("dimension", dimension);
        json.addProperty("chunks", plan.chunks());
        json.addProperty("generated", totals[0]);
        json.addProperty("loadedToPopulate", totals[1]);
        json.addProperty("alreadyLoaded", totals[2]);
        json.addProperty("existing", countExistingInBox(steps, plan));
        json.addProperty("released", totals[3]);
        json.addProperty("millis", millis);
        if (totals[0] > 0 && millis > 0) {
            json.addProperty("chunksPerSecond", Math.round(totals[0] * 10_000.0D / millis) / 10.0D);
        }
        if (totals[4] + totals[5] > 0) {
            json.addProperty("waitsForHealth", totals[4]);
            json.addProperty("waitsForLoadedChunks", totals[5]);
            json.addProperty("lastWait", lastWait);
        }
        return ToolResult.structured(json);
    }

    /** Chunks of the box the pass did not need to generate. */
    private static long countExistingInBox(List<PregenPlan.Step> steps, PregenPlan plan) {
        long generatedInBox = 0L;
        for (PregenPlan.Step step : steps) {
            if (step.generate && step.inBox) {
                generatedInBox++;
            }
        }
        return plan.chunks() - generatedInBox;
    }
}
