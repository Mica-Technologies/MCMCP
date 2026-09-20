package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.ServerTickRecorder;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.perf.CallTree;
import com.micatechnologies.minecraft.mcmcp.perf.DurationWindow;
import com.micatechnologies.minecraft.mcmcp.perf.GameThreads;
import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.server.timings.ForgeTimings;
import net.minecraftforge.server.timings.TimeTracker;

/**
 * Performance tools: what the JVM is doing, how the tick is holding up, and which block is to blame.
 *
 * <p>They exist for one loop. An agent builds a block, places a few hundred, and needs to know what
 * that cost — and when it cost too much, which block, at which position, in which method. Each tool
 * is one rung of that ladder: {@code server_tick_stats} says whether there is a problem,
 * {@code server_profile_ticking} names the tile entity, {@code server_profile_sections} says which
 * phase of the tick, {@code game_cpu_sample} names the method, and {@code game_health} says whether
 * the real answer is garbage collection.
 *
 * <h2>No ASM, no mixin</h2>
 *
 * Everything here uses hooks that are already in the game. Forge patches {@code World.updateEntities}
 * to bracket every tile entity and entity update with a {@link TimeTracker} call, for its own
 * {@code /forge track}; vanilla brackets every phase of the tick with {@link Profiler} sections, for
 * {@code /debug}; and the JDK can dump one thread's stack on request. A profiler that added its own
 * bytecode hooks would be more precise, and would also be one more coremod in a pack whose problem
 * may well be a coremod.
 *
 * <h2>The windowed tools block a worker, never the game thread</h2>
 *
 * Each one touches the game thread twice — to switch the instrument on, and to read it — and sleeps
 * on its own HTTP worker in between. The instrument is switched off in a {@code finally}, because a
 * cancelled call that left Forge's tracker running would tax every tick until someone noticed.
 */
public final class PerformanceTools {

    /** Where {@code game_cpu_sample} writes its full tree; shared with {@code game_dump_registries}. */
    private static final String DUMP_DIRECTORY = "mcmcp/dumps";

    private PerformanceTools() {
    }

    public static void register() {
        ServerTickRecorder.register();
        registerHealth();
        registerCpuSample();
        registerTickStats();
        registerProfileTicking();
        registerProfileSections();
    }

    /** Sleeps on the calling worker in slices, so a cancelled request stops within a tenth of a second. */
    public static void sleepCancellable(ToolContext context, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (true) {
            context.getCancellation().throwIfCancelled();
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0L) {
                return;
            }
            Thread.sleep(Math.min(left, 100L));
        }
    }

    // ------------------------------------------------------------------
    // JVM health
    // ------------------------------------------------------------------

    private static void registerHealth() {
        McpRegistry.registerTool(McpTool.named("game_health")
            .title("JVM health")
            .description("Report the JVM's health: heap and memory pools, garbage collection per "
                + "collector, process and system CPU load, threads, and free disk. Look here when "
                + "ticks or frames hitch at intervals rather than staying uniformly slow — that "
                + "pattern is usually garbage collection, not a block.\n\n"
                + "With sample_seconds, also measures that window: GC pauses, and the game thread's "
                + "CPU share and allocation rate. A high allocation rate is how garbage-heavy code "
                + "shows up before it becomes a GC pause; compare it before and after a change.")
            .schema(JsonSchema.object()
                .integer("sample_seconds", "Measure a window of this many seconds as well. Default 0: "
                    + "totals since JVM start only.", 0, 30)
                .build())
            .readOnly()
            .closedWorld()
            .offGameThread()
            .handler(context -> {
                int sampleSeconds = context.getBoundedInt("sample_seconds", 0, 0, 30);
                JsonObject json = new JsonObject();

                if (sampleSeconds > 0) {
                    json.add("window", measureWindow(context, sampleSeconds));
                }

                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                JsonObject memory = new JsonObject();
                memory.addProperty("heapUsedMb", megabytes(heap.getUsed()));
                memory.addProperty("heapCommittedMb", megabytes(heap.getCommitted()));
                memory.addProperty("heapMaxMb", megabytes(heap.getMax()));
                JsonArray pools = new JsonArray();
                for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    MemoryUsage usage = pool.getUsage();
                    if (usage == null) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", pool.getName());
                    entry.addProperty("usedMb", megabytes(usage.getUsed()));
                    if (usage.getMax() > 0L) {
                        entry.addProperty("maxMb", megabytes(usage.getMax()));
                    }
                    pools.add(entry);
                }
                memory.add("pools", pools);
                json.add("memory", memory);

                long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
                long gcMillisTotal = 0L;
                JsonArray collectors = new JsonArray();
                for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    long count = Math.max(0L, gc.getCollectionCount());
                    long millis = Math.max(0L, gc.getCollectionTime());
                    gcMillisTotal += millis;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", gc.getName());
                    entry.addProperty("collections", count);
                    entry.addProperty("totalMs", millis);
                    entry.addProperty("meanMs", count == 0L ? 0.0D : round1(millis / (double) count));
                    collectors.add(entry);
                }
                JsonObject gc = new JsonObject();
                gc.add("collectors", collectors);
                gc.addProperty("percentOfUptime",
                    uptimeMillis <= 0L ? 0.0D : round1(gcMillisTotal * 100.0D / uptimeMillis));
                json.add("gc", gc);

                JsonObject cpu = new JsonObject();
                cpu.addProperty("processors", Runtime.getRuntime().availableProcessors());
                addCpuLoad(cpu);
                json.add("cpu", cpu);

                ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
                JsonObject threads = new JsonObject();
                threads.addProperty("live", threadBean.getThreadCount());
                threads.addProperty("daemon", threadBean.getDaemonThreadCount());
                threads.addProperty("peak", threadBean.getPeakThreadCount());
                json.add("threads", threads);

                File gameDirectory = McmcpPaths.gameDirectory();
                JsonObject disk = new JsonObject();
                disk.addProperty("freeGb", round1(gameDirectory.getUsableSpace() / 1.073741824e9D));
                disk.addProperty("totalGb", round1(gameDirectory.getTotalSpace() / 1.073741824e9D));
                json.add("disk", disk);

                json.addProperty("uptimeSeconds", uptimeMillis / 1000L);
                return ToolResult.structured(json);
            })
            .build());
    }

    /**
     * GC, CPU and allocation over a window, as deltas of counters the JVM keeps anyway.
     *
     * <p>The per-thread figures come from HotSpot's extension of {@link ThreadMXBean}. It is there on
     * every JVM 1.12.2 realistically runs on, but it is an extension, so its absence is reported as
     * an absent field rather than allowed to fail the call.
     */
    private static JsonObject measureWindow(ToolContext context, int seconds) throws InterruptedException {
        String threadName = context.getSide().isClient() ? GameThreads.CLIENT : GameThreads.SERVER;
        long threadId = GameThreads.find(threadName);
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        long gcCountBefore = 0L;
        long gcMillisBefore = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCountBefore += Math.max(0L, gc.getCollectionCount());
            gcMillisBefore += Math.max(0L, gc.getCollectionTime());
        }
        long cpuBefore = threadCpuNanos(threadBean, threadId);
        long allocatedBefore = threadAllocatedBytes(threadBean, threadId);
        long startedNanos = System.nanoTime();

        sleepCancellable(context, seconds * 1000L);

        double wallNanos = System.nanoTime() - startedNanos;
        long gcCount = -gcCountBefore;
        long gcMillis = -gcMillisBefore;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0L, gc.getCollectionCount());
            gcMillis += Math.max(0L, gc.getCollectionTime());
        }

        JsonObject window = new JsonObject();
        window.addProperty("seconds", seconds);
        window.addProperty("gcCollections", gcCount);
        window.addProperty("gcMs", gcMillis);
        window.addProperty("gcPercentOfWindow", round1(gcMillis * 1.0e8D / wallNanos));
        if (threadId >= 0L) {
            JsonObject thread = new JsonObject();
            thread.addProperty("name", threadName);
            long cpuAfter = threadCpuNanos(threadBean, threadId);
            if (cpuBefore >= 0L && cpuAfter >= 0L) {
                thread.addProperty("cpuPercent", round1((cpuAfter - cpuBefore) * 100.0D / wallNanos));
            }
            long allocatedAfter = threadAllocatedBytes(threadBean, threadId);
            if (allocatedBefore >= 0L && allocatedAfter >= 0L) {
                thread.addProperty("allocatedMbPerSecond",
                    round1((allocatedAfter - allocatedBefore) / 1_048_576.0D / (wallNanos / 1.0e9D)));
            }
            window.add("gameThread", thread);
        }
        return window;
    }

    private static long threadCpuNanos(ThreadMXBean bean, long threadId) {
        try {
            return threadId >= 0L && bean.isThreadCpuTimeSupported() ? bean.getThreadCpuTime(threadId) : -1L;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private static long threadAllocatedBytes(ThreadMXBean bean, long threadId) {
        try {
            if (threadId >= 0L && bean instanceof com.sun.management.ThreadMXBean) {
                return ((com.sun.management.ThreadMXBean) bean).getThreadAllocatedBytes(threadId);
            }
        } catch (RuntimeException | LinkageError e) {
            // Not HotSpot, or the feature is switched off. Absent, not fatal.
        }
        return -1L;
    }

    private static void addCpuLoad(JsonObject cpu) {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sun = (com.sun.management.OperatingSystemMXBean) os;
                // Negative means "not available yet", which is what the first read on a fresh JVM
                // returns on some platforms.
                if (sun.getProcessCpuLoad() >= 0.0D) {
                    cpu.addProperty("processPercent", round1(sun.getProcessCpuLoad() * 100.0D));
                }
                if (sun.getSystemCpuLoad() >= 0.0D) {
                    cpu.addProperty("systemPercent", round1(sun.getSystemCpuLoad() * 100.0D));
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // As above.
        }
    }

    // ------------------------------------------------------------------
    // Sampling profiler
    // ------------------------------------------------------------------

    private static void registerCpuSample() {
        McpRegistry.registerTool(McpTool.named("game_cpu_sample")
            .title("Sample CPU profile")
            .description("Profile one thread by sampling its stack, and report where its time went: "
                + "the hottest methods, the share attributable to each mod package, and the call "
                + "tree. This is the tool that names a method. Use it once server_profile_ticking "
                + "or the section profile has said which block or phase is slow and the question "
                + "is why.\n\n"
                + "Samples the game thread of this endpoint's side by default. Percentages are "
                + "shares of samples, not measured durations. Samples in which the thread was "
                + "parked (sleeping out the tick, frame limiter) are dropped unless include_idle "
                + "is set, and reported as idlePercent. 'byPackage' credits each sample to the "
                + "nearest non-vanilla, non-library package on the stack, so vanilla work done on "
                + "a mod's behalf counts towards that mod.\n\n"
                + "The full unpruned tree is written to a JSON file whose path is returned. "
                + "Blocks for the duration; reproduce the load during the window.")
            .schema(JsonSchema.object()
                .integer("duration_seconds", "How long to sample. Default 10.", 1, 30)
                .integer("interval_ms", "Milliseconds between samples. Default 4.", 1, 100)
                .string("thread", "Thread name, exact or substring. Default: the game thread.")
                .bool("include_idle", "Keep samples where the thread was parked. Default false.")
                .number("min_percent", "Prune tree branches below this share. Default 2.", 0.1D, 50.0D)
                .integer("max_lines", "Cap on tree lines returned. Default 60.", 10, 300)
                .integer("top", "Rows in hottestFrames and byPackage. Default 15.", 1, 50)
                .build())
            .readOnly()
            .closedWorld()
            .offGameThread()
            .handler(context -> {
                int durationSeconds = context.getBoundedInt("duration_seconds", 10, 1, 30);
                int intervalMillis = context.getBoundedInt("interval_ms", 4, 1, 100);
                boolean includeIdle = context.getBoolean("include_idle", false);
                double minPercent = Math.max(0.1D, Math.min(50.0D, context.getDouble("min_percent", 2.0D)));
                int maxLines = context.getBoundedInt("max_lines", 60, 10, 300);
                int top = context.getBoundedInt("top", 15, 1, 50);
                String threadName = context.getString("thread",
                    context.getSide().isClient() ? GameThreads.CLIENT : GameThreads.SERVER);

                long threadId = GameThreads.find(threadName);
                if (threadId < 0L) {
                    return ToolResult.error("No live thread matches '" + threadName + "'. Live threads: "
                        + GameThreads.names());
                }

                ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
                CallTree tree = new CallTree();
                String resolvedName = threadName;
                long attempts = 0L;
                long deadline = System.nanoTime() + durationSeconds * 1_000_000_000L;
                while (System.nanoTime() < deadline) {
                    context.getCancellation().throwIfCancelled();
                    ThreadInfo info = threadBean.getThreadInfo(threadId, Integer.MAX_VALUE);
                    if (info == null) {
                        break;
                    }
                    resolvedName = info.getThreadName();
                    attempts++;
                    tree.add(info.getStackTrace(), !includeIdle);
                    Thread.sleep(intervalMillis);
                }
                if (attempts == 0L) {
                    return ToolResult.error("Thread '" + threadName + "' ended before it could be sampled.");
                }

                JsonObject result = new JsonObject();
                result.addProperty("thread", resolvedName);
                result.addProperty("durationSeconds", durationSeconds);
                result.addProperty("samples", tree.samples());
                result.addProperty("idlePercent", Math.round(tree.idleSamples() * 1000.0D / attempts) / 10.0D);
                result.add("hottestFrames", tree.hottestFrames(top));
                result.add("byPackage", tree.byOwner(top));

                File directory = new File(McmcpPaths.gameDirectory(), DUMP_DIRECTORY);
                File target = new File(directory, "cpu-sample-" + context.getSide().id() + "-"
                    + System.currentTimeMillis() + ".json");
                try {
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IOException("could not create " + directory.getAbsolutePath());
                    }
                    JsonObject dump = new JsonObject();
                    dump.addProperty("thread", resolvedName);
                    dump.addProperty("samples", tree.samples());
                    dump.addProperty("intervalMs", intervalMillis);
                    dump.add("tree", tree.toJson());
                    Files.write(target.toPath(), Json.writePretty(dump).getBytes(StandardCharsets.UTF_8));
                    result.addProperty("file", target.getAbsolutePath());
                } catch (IOException e) {
                    // The inline summary is the answer; the file is the appendix. Losing the
                    // appendix is worth a field, not a failed call.
                    result.addProperty("fileError", e.getMessage());
                }

                // The tree goes out as text beside the structured summary, not inside it: nesting is
                // most of what a call tree is, and JSON charges for every level of it.
                return ToolResult.text(Json.write(result) + "\n\n" + tree.render(minPercent, maxLines));
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Tick statistics
    // ------------------------------------------------------------------

    private static void registerTickStats() {
        McpRegistry.registerTool(McpTool.named("server_tick_stats")
            .title("Tick statistics")
            .description("Report TPS and tick duration (MSPT) over the last 5 seconds, 1 minute and 5 "
                + "minutes: mean, median, p95, p99 and max. A tick has a 50 ms budget. A high mean is "
                + "steady load; a low median with a high max is a hitch, and the mean in "
                + "server_world_info hides that. Measure before and after a change to see its cost.")
            .schema(JsonSchema.noArguments())
            .serverOnly()
            .readOnly()
            .closedWorld()
            .offGameThread()
            .handler(context -> {
                long now = System.nanoTime();
                JsonObject json = new JsonObject();
                String[] names = {"last5s", "last1m", "last5m"};
                long[] seconds = {5L, 60L, 300L};
                int previousSamples = -1;
                for (int i = 0; i < names.length; i++) {
                    JsonObject window = tickWindow(now, seconds[i]);
                    // A longer window holding no more ticks than a shorter one is the same window:
                    // the server has not been up long enough to tell them apart.
                    int samples = window.get("samples").getAsInt();
                    if (samples > previousSamples) {
                        json.add(names[i], window);
                    }
                    previousSamples = samples;
                }
                return ToolResult.structured(json);
            })
            .build());
    }

    private static JsonObject tickWindow(long nowNanos, long seconds) {
        DurationWindow.Summary summary = ServerTickRecorder.ticks().summarise(nowNanos, seconds * 1_000_000_000L);
        JsonObject json = summary.toJson();
        // Capped at 20: the server sleeps out the rest of a fast tick, and an interval a hair under
        // 50 ms is scheduler jitter, not a server running fast.
        json.addProperty("tps", Math.min(20.0D, Math.round(summary.ratePerSecond() * 100.0D) / 100.0D));
        json.addProperty("ticksOver50Ms", summary.countOver(50_000_000L));
        return json;
    }

    // ------------------------------------------------------------------
    // Per-block and per-entity tick cost
    // ------------------------------------------------------------------

    private static void registerProfileTicking() {
        McpRegistry.registerTool(McpTool.named("server_profile_ticking")
            .title("Profile ticking blocks and entities")
            .description("Time every ticking tile entity and entity for a few seconds and report the "
                + "most expensive, with position, plus totals by type and by chunk. This is the "
                + "tool that names the block behind a slow tick. Costs are microseconds per tick; "
                + "the whole tick has 50,000.\n\n"
                + "Covers update() time only. Work a block does elsewhere — neighbour updates, "
                + "scheduled and random ticks, event handlers — is not attributed here; use "
                + "server_profile_sections or game_cpu_sample for that. Blocks for the duration. "
                + "Uses Forge's own tracker, so it resets any '/forge track' in progress.")
            .schema(JsonSchema.object()
                .integer("duration_seconds", "How long to measure. Default 5, which already fills "
                    + "the 99-update history kept per object.", 1, 30)
                .integer("top", "Rows per list. Default 15.", 1, 50)
                .integer("dimension", "Only this dimension id. Default: all loaded.")
                .build())
            .serverOnly()
            .readOnly()
            .closedWorld()
            .handler(context -> {
                final int durationSeconds = context.getBoundedInt("duration_seconds", 5, 1, 30);
                final int top = context.getBoundedInt("top", 15, 1, 50);
                final Integer dimension = context.has("dimension") ? context.getInt("dimension", 0) : null;

                final long ticksBefore = ServerTickRecorder.tickCount();
                final long startedNanos = System.nanoTime();
                context.onGameThread(new Callable<Void>() {
                    @Override
                    public Void call() {
                        TimeTracker.TILE_ENTITY_UPDATE.reset();
                        TimeTracker.ENTITY_UPDATE.reset();
                        // The tracker switches itself off after this long. Longer than the window,
                        // so that it is still recording when the window is read; the reset below is
                        // what actually ends it.
                        TimeTracker.TILE_ENTITY_UPDATE.enable(durationSeconds + 10);
                        TimeTracker.ENTITY_UPDATE.enable(durationSeconds + 10);
                        return null;
                    }
                });

                try {
                    sleepCancellable(context, durationSeconds * 1000L);
                    JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                        @Override
                        public JsonObject call() {
                            return buildTickingReport(top, dimension);
                        }
                    });
                    result.addProperty("ticksObserved", ServerTickRecorder.tickCount() - ticksBefore);
                    result.addProperty("meanTickMs", DurationWindow.millis(
                        ServerTickRecorder.ticks().summariseSince(startedNanos).meanNanos()));
                    return ToolResult.structured(result);
                } finally {
                    context.onGameThreadAsync(new Runnable() {
                        @Override
                        public void run() {
                            TimeTracker.TILE_ENTITY_UPDATE.reset();
                            TimeTracker.ENTITY_UPDATE.reset();
                        }
                    });
                }
            })
            .build());
    }

    /** One timed object, reduced to primitives and strings so nothing of the game outlives the call. */
    private static final class Timed {
        String type;
        String className;
        int dimension;
        BlockPos pos;
        double meanNanos;
        long maxNanos;
    }

    /** Game thread only. */
    private static JsonObject buildTickingReport(int top, Integer dimension) {
        List<Timed> tileEntities = new ArrayList<Timed>();
        for (ForgeTimings<TileEntity> timing : TimeTracker.TILE_ENTITY_UPDATE.getTimingData()) {
            TileEntity tileEntity = timing.getObject().get();
            if (tileEntity == null) {
                continue;
            }
            Timed timed = timed(timing.getRawTimingData(), tileEntity.getWorld(), dimension);
            if (timed == null) {
                continue;
            }
            timed.pos = tileEntity.getPos();
            timed.className = tileEntity.getClass().getName();
            // The block, not the tile entity's registry key: the block id is what the caller placed
            // and what every other tool here speaks in.
            ResourceLocation block = tileEntity.getBlockType() == null ? null
                : tileEntity.getBlockType().getRegistryName();
            timed.type = block == null ? tileEntity.getClass().getSimpleName() : block.toString();
            tileEntities.add(timed);
        }

        List<Timed> entities = new ArrayList<Timed>();
        for (ForgeTimings<Entity> timing : TimeTracker.ENTITY_UPDATE.getTimingData()) {
            Entity entity = timing.getObject().get();
            if (entity == null) {
                continue;
            }
            Timed timed = timed(timing.getRawTimingData(), entity.world, dimension);
            if (timed == null) {
                continue;
            }
            timed.pos = GameJson.blockPosOf(entity);
            timed.className = entity.getClass().getName();
            ResourceLocation key = EntityList.getKey(entity);
            timed.type = key == null ? entity.getName() : key.toString();
            entities.add(timed);
        }

        JsonObject json = new JsonObject();
        json.add("tileEntities", section(tileEntities, top, "block"));
        json.add("entities", section(entities, top, "entity"));
        json.add("chunks", chunks(tileEntities, entities, top));
        return json;
    }

    /**
     * Reduces Forge's raw ring to a mean and a max, or returns null if the object should be skipped.
     *
     * <p>Forge's own {@code getAverageTimings} divides by the ring's length whether or not the ring
     * is full, so anything that updated fewer than 99 times in the window reads as cheaper than it
     * is — and a tile entity placed two seconds ago reads as less than half its real cost. Only the
     * slots that were written are averaged here. A slot is never legitimately zero: the clock read
     * alone takes longer than a nanosecond.
     */
    private static Timed timed(int[] raw, World world, Integer dimension) {
        // In singleplayer the client world's updates run through the same static tracker from the
        // client thread. Those are not this endpoint's objects.
        if (world == null || world.isRemote) {
            return null;
        }
        int objectDimension = world.provider.getDimension();
        if (dimension != null && dimension != objectDimension) {
            return null;
        }
        long total = 0L;
        long max = 0L;
        int samples = 0;
        for (int sample : raw) {
            if (sample > 0) {
                total += sample;
                max = Math.max(max, sample);
                samples++;
            }
        }
        if (samples == 0) {
            return null;
        }
        Timed timed = new Timed();
        timed.dimension = objectDimension;
        timed.meanNanos = total / (double) samples;
        timed.maxNanos = max;
        return timed;
    }

    private static final Comparator<Timed> COSTLIEST_FIRST = new Comparator<Timed>() {
        @Override
        public int compare(Timed a, Timed b) {
            return Double.compare(b.meanNanos, a.meanNanos);
        }
    };

    private static JsonObject section(List<Timed> timings, int top, String typeLabel) {
        Collections.sort(timings, COSTLIEST_FIRST);
        double totalNanos = 0.0D;
        // type -> {count, summed mean, worst mean}; the class rides along for the first of its type.
        Map<String, double[]> byType = new HashMap<String, double[]>();
        Map<String, String> classOfType = new HashMap<String, String>();
        for (Timed timed : timings) {
            totalNanos += timed.meanNanos;
            double[] sums = byType.get(timed.type);
            if (sums == null) {
                byType.put(timed.type, sums = new double[3]);
                classOfType.put(timed.type, timed.className);
            }
            sums[0]++;
            sums[1] += timed.meanNanos;
            sums[2] = Math.max(sums[2], timed.meanNanos);
        }

        JsonArray costliest = new JsonArray();
        for (int i = 0; i < timings.size() && i < top; i++) {
            Timed timed = timings.get(i);
            JsonObject row = new JsonObject();
            row.addProperty(typeLabel, timed.type);
            row.add("pos", GameJson.blockPos(timed.pos));
            row.addProperty("dim", timed.dimension);
            row.addProperty("meanMicros", DurationWindow.micros(timed.meanNanos));
            row.addProperty("maxMicros", DurationWindow.micros(timed.maxNanos));
            costliest.add(row);
        }

        List<Map.Entry<String, double[]>> types = new ArrayList<Map.Entry<String, double[]>>(byType.entrySet());
        Collections.sort(types, new Comparator<Map.Entry<String, double[]>>() {
            @Override
            public int compare(Map.Entry<String, double[]> a, Map.Entry<String, double[]> b) {
                return Double.compare(b.getValue()[1], a.getValue()[1]);
            }
        });
        JsonArray typeRows = new JsonArray();
        for (int i = 0; i < types.size() && i < top; i++) {
            double[] sums = types.get(i).getValue();
            JsonObject row = new JsonObject();
            row.addProperty(typeLabel, types.get(i).getKey());
            row.addProperty("class", classOfType.get(types.get(i).getKey()));
            row.addProperty("count", (int) sums[0]);
            row.addProperty("totalMicros", DurationWindow.micros(sums[1]));
            row.addProperty("worstMicros", DurationWindow.micros(sums[2]));
            typeRows.add(row);
        }

        JsonObject json = new JsonObject();
        json.addProperty("tracked", timings.size());
        json.addProperty("totalMicrosPerTick", DurationWindow.micros(totalNanos));
        json.add("costliest", costliest);
        json.add("byType", typeRows);
        return json;
    }

    private static JsonArray chunks(List<Timed> tileEntities, List<Timed> entities, int top) {
        // "dim:chunkX:chunkZ" -> {summed mean, tile entities, entities}
        final Map<String, double[]> byChunk = new HashMap<String, double[]>();
        for (int pass = 0; pass < 2; pass++) {
            for (Timed timed : pass == 0 ? tileEntities : entities) {
                String key = timed.dimension + ":" + (timed.pos.getX() >> 4) + ":" + (timed.pos.getZ() >> 4);
                double[] sums = byChunk.get(key);
                if (sums == null) {
                    byChunk.put(key, sums = new double[3]);
                }
                sums[0] += timed.meanNanos;
                sums[1 + pass]++;
            }
        }
        List<String> keys = new ArrayList<String>(byChunk.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Double.compare(byChunk.get(b)[0], byChunk.get(a)[0]);
            }
        });
        JsonArray rows = new JsonArray();
        for (int i = 0; i < keys.size() && i < top; i++) {
            String[] parts = keys.get(i).split(":");
            double[] sums = byChunk.get(keys.get(i));
            JsonObject row = new JsonObject();
            row.addProperty("dim", Integer.parseInt(parts[0]));
            row.addProperty("chunkX", Integer.parseInt(parts[1]));
            row.addProperty("chunkZ", Integer.parseInt(parts[2]));
            row.addProperty("totalMicros", DurationWindow.micros(sums[0]));
            row.addProperty("tileEntities", (int) sums[1]);
            row.addProperty("entities", (int) sums[2]);
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Vanilla profiler sections
    // ------------------------------------------------------------------

    private static void registerProfileSections() {
        McpRegistry.registerTool(McpTool.named("server_profile_sections")
            .title("Profile tick phases")
            .description("Run the vanilla section profiler (what '/debug' records) for a few seconds "
                + "and return the tick broken down by phase, as an indented tree of percent-of-tick "
                + "and approximate ms per tick: each dimension, its entities, block entities by "
                + "type, chunk ticking, scheduled block updates, and so on. Use it to find which "
                + "phase is slow when server_profile_ticking does not explain the tick — "
                + "scheduled and random block ticks only show up here. Blocks for the duration.")
            .schema(sectionSchema())
            .serverOnly()
            .readOnly()
            .closedWorld()
            .handler(context -> {
                final int durationSeconds = context.getBoundedInt("duration_seconds", 5, 1, 30);
                final double minPercent = Math.max(0.1D, Math.min(50.0D, context.getDouble("min_percent", 1.0D)));
                final int maxDepth = context.getBoundedInt("max_depth", 8, 1, 12);

                final long startedNanos = System.nanoTime();
                final boolean alreadyRunning = context.onGameThread(new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        MinecraftServer server = requireServer();
                        if (server.profiler.profilingEnabled) {
                            return true;
                        }
                        // Not profilingEnabled = true. The server clears and enables the profiler
                        // itself at the top of the next tick when asked this way; switching it on
                        // from inside a tick would have the first endSection pop a stack that no
                        // startSection pushed, which throws on the server thread.
                        server.enableProfiling();
                        return false;
                    }
                });

                try {
                    sleepCancellable(context, durationSeconds * 1000L);
                    String tree = context.onGameThread(new Callable<String>() {
                        @Override
                        public String call() {
                            double meanTickMs = DurationWindow.millis(
                                ServerTickRecorder.ticks().summariseSince(startedNanos).meanNanos());
                            return "# percent of tick, ~ms per tick (mean tick " + meanTickMs + " ms)\n"
                                + renderSections(requireServer().profiler, meanTickMs, minPercent, maxDepth);
                        }
                    });
                    return ToolResult.text(tree);
                } finally {
                    if (!alreadyRunning) {
                        context.onGameThreadAsync(new Runnable() {
                            @Override
                            public void run() {
                                MinecraftServer server = ServerThreadBridge.server();
                                if (server != null) {
                                    // Safe mid-tick, unlike enabling: a disabled profiler ignores
                                    // the endSection calls still to come. '/debug stop' does this.
                                    server.profiler.profilingEnabled = false;
                                }
                            }
                        });
                    }
                }
            })
            .build());
    }

    /** Shared by the client's section profile, which takes the same three arguments. */
    public static JsonObject sectionSchema() {
        return JsonSchema.object()
            .integer("duration_seconds", "How long to profile. Default 5.", 1, 30)
            .number("min_percent", "Hide sections below this share of the whole. Default 1.", 0.1D, 50.0D)
            .integer("max_depth", "Tree depth to descend. Default 8.", 1, 12)
            .build();
    }

    private static MinecraftServer requireServer() {
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null) {
            throw new IllegalStateException("No Minecraft server is running");
        }
        return server;
    }

    /**
     * Walks an enabled {@link Profiler} from {@code root} and renders it as an indented tree.
     *
     * <p>The profiler only answers in percentages, so {@code wholeMs} — the measured mean tick or
     * frame — is what turns a share into a duration. Its accumulators decay a little on every read;
     * that is harmless here because the decay is uniform and only ratios are taken.
     *
     * <p>Must run on the thread that owns the profiler, while it is enabled.
     */
    public static String renderSections(Profiler profiler, double wholeMs, double minPercent, int maxDepth) {
        if (!profiler.profilingEnabled) {
            return "(the profiler was switched off during the window — something else, such as "
                + "'/debug stop' or the F3 pie chart being closed, turned it off)\n";
        }
        StringBuilder out = new StringBuilder();
        int[] lines = {0};
        renderSection(profiler, "root", 0, wholeMs, minPercent, maxDepth, lines, out);
        return lines[0] == 0 ? "(no sections recorded yet)\n" : out.toString();
    }

    private static void renderSection(Profiler profiler, String path, int depth, double wholeMs,
                                      double minPercent, int maxDepth, int[] lines, StringBuilder out) {
        List<Profiler.Result> results = profiler.getProfilingData(path);
        // Index 0 is the section itself; the rest are its children, largest first.
        for (int i = 1; i < results.size(); i++) {
            Profiler.Result child = results.get(i);
            if (child.totalUsePercentage < minPercent || lines[0] >= 400) {
                continue;
            }
            // A leaf reports itself as one child called "unspecified" holding 100% of it.
            if ("unspecified".equals(child.profilerName) && results.size() == 2) {
                continue;
            }
            for (int indent = 0; indent < depth; indent++) {
                out.append(' ');
            }
            out.append(child.profilerName).append(' ')
                .append(Math.round(child.totalUsePercentage * 10.0D) / 10.0D).append("% ")
                .append(Math.round(child.totalUsePercentage * wholeMs) / 100.0D).append("ms\n");
            lines[0]++;
            if (depth + 1 < maxDepth && !"unspecified".equals(child.profilerName)) {
                renderSection(profiler, path + "." + child.profilerName, depth + 1, wholeMs, minPercent,
                    maxDepth, lines, out);
            }
        }
    }

    private static double megabytes(long bytes) {
        return bytes < 0L ? -1.0D : round1(bytes / 1_048_576.0D);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }
}
