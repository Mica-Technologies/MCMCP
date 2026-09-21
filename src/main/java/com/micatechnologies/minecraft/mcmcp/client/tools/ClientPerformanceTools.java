package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.client.ClientFrameClock;
import com.micatechnologies.minecraft.mcmcp.client.ClientFrameRecorder;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.perf.DurationWindow;
import com.micatechnologies.minecraft.mcmcp.perf.GcPauseLog;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import com.micatechnologies.minecraft.mcmcp.tools.PerformanceTools;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * The client's half of {@link PerformanceTools}: what a frame costs, which phase of it, and which
 * block's or entity's renderer.
 *
 * <h2>What can and cannot be pinned on a block</h2>
 *
 * A block draws one of two ways, and only one of them can be timed per block without bytecode hooks.
 * A <em>tile entity renderer</em> is called once per block per frame, through a public map — so it can
 * be wrapped, timed and attributed to a position, which {@code client_profile_rendering} does. A
 * <em>baked model</em> is compiled into its chunk's vertex buffer once and drawn with everything
 * else in that chunk; it has no per-frame call to time. Its cost shows up as the {@code terrain} and
 * {@code updatechunks} sections of {@code client_profile_sections}, and the way to measure one is
 * the blunt one: {@code client_frame_stats} before and after placing a few hundred.
 */
@SideOnly(Side.CLIENT)
public final class ClientPerformanceTools {

    /** One windowed client profile at a time: two would fight over the same renderer map and flags. */
    private static final AtomicBoolean PROFILING = new AtomicBoolean();

    private ClientPerformanceTools() {
    }

    public static void register() {
        ClientFrameRecorder.register();
        registerFrameStats();
        registerProfileSections();
        registerProfileRendering();
    }

    // ------------------------------------------------------------------
    // Frame statistics
    // ------------------------------------------------------------------

    private static void registerFrameStats() {
        McpRegistry.registerTool(McpTool.named("client_frame_stats")
            .title("Frame statistics")
            .description("Report FPS and frame-time distribution: mean, median, p95, p99, max, and the "
                + "1% low. Reports the last 5 seconds and last minute, or with sample_seconds, "
                + "measures exactly that window from now — hold the camera still on the scene "
                + "under test.\n\n"
                + "To measure what a change costs, compare 'renderWork' before and after, not fps. "
                + "'frame' is the interval the player sees and includes the frame limiter's wait, "
                + "so under an FPS cap or vsync it does not move until the cap is breached. "
                + "'renderWork' is the CPU time spent drawing each frame and moves regardless.")
            .schema(JsonSchema.object()
                .integer("sample_seconds", "Measure a fresh window of this length instead of "
                    + "reporting history. Default 0.", 0, 30)
                .build())
            .clientOnly()
            .readOnly()
            .closedWorld()
            .handler(context -> {
                int sampleSeconds = context.getBoundedInt("sample_seconds", 0, 0, 30);
                JsonObject json = new JsonObject();
                if (sampleSeconds > 0) {
                    long startedNanos = System.nanoTime();
                    PerformanceTools.sleepCancellable(context, sampleSeconds * 1000L);
                    json.add("sampled", frameWindow(startedNanos));
                } else {
                    long now = System.nanoTime();
                    JsonObject recent = frameWindow(now - 5_000_000_000L);
                    JsonObject minute = frameWindow(now - 60_000_000_000L);
                    json.add("last5s", recent);
                    // A minute holding no more frames than five seconds is the same window.
                    if (minute.getAsJsonObject("frame").get("samples").getAsInt()
                        > recent.getAsJsonObject("frame").get("samples").getAsInt()) {
                        json.add("last1m", minute);
                    }
                }

                JsonObject settings = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        GameSettings game = Minecraft.getMinecraft().gameSettings;
                        JsonObject result = new JsonObject();
                        result.addProperty("fpsLimit", game.limitFramerate);
                        result.addProperty("vsync", game.enableVsync);
                        result.addProperty("renderDistanceChunks", game.renderDistanceChunks);
                        return result;
                    }
                });
                json.add("settings", settings);
                return ToolResult.structured(json);
            })
            .build());
    }

    private static JsonObject frameWindow(long sinceNanos) {
        DurationWindow.Summary frames = ClientFrameRecorder.intervals().summariseSince(sinceNanos);
        JsonObject json = new JsonObject();
        json.addProperty("fps", Math.round(frames.ratePerSecond() * 10.0D) / 10.0D);
        // The mean of the slowest 1% of frames, as a rate. This is the figure that tracks how a
        // stutter feels: an average of 140 with a 1% low of 20 is a game that hitches.
        double slowest = frames.slowestMeanNanos(1.0D);
        json.addProperty("low1PercentFps", slowest <= 0.0D ? 0.0D : Math.round(1.0e10D / slowest) / 10.0D);
        int slowFrames = frames.countOver(50_000_000L);
        json.addProperty("framesOver50Ms", slowFrames);
        if (slowFrames > 0) {
            // As server_tick_stats does: a hitch that coincides with a collection is a heap
            // question, not a renderer one.
            json.addProperty("ofThoseDuringGc", ClientFrameRecorder.intervals().countOverlapping(
                sinceNanos, 50_000_000L, GcPauseLog.intervalsSince(sinceNanos - 1_000_000_000L),
                PerformanceTools.GC_SLACK_NANOS));
        }
        json.add("frame", frames.toJson());
        json.add("renderWork", ClientFrameRecorder.renderWork().summariseSince(sinceNanos).toJson());
        return json;
    }

    // ------------------------------------------------------------------
    // Vanilla profiler sections
    // ------------------------------------------------------------------

    /** The debug flags as they were before a section profile forced them on. Client thread only. */
    private static boolean flagsOverridden;
    private static boolean savedShowDebugInfo;
    private static boolean savedShowProfilerChart;
    private static boolean savedHideGui;

    private static void registerProfileSections() {
        McpRegistry.registerTool(McpTool.named("client_profile_sections")
            .title("Profile frame phases")
            .description("Run the vanilla section profiler (the F3 pie chart's data) for a few seconds "
                + "and return the frame broken down by phase, as an indented tree of "
                + "percent-of-frame and approximate ms per frame: client tick, terrain, chunk "
                + "rebuilds (updatechunks), entities, blockentities, particles, gui, and so on. "
                + "Use it to see which phase a slow frame is spent in; baked-model blocks show up "
                + "as terrain and updatechunks.\n\n"
                + "The client only profiles while the F3 pie chart is on screen, so this shows F3 "
                + "and the chart for the duration and then restores them — they will appear in "
                + "screenshots taken meanwhile, and drawing F3 inflates the 'gui' section. Blocks "
                + "for the duration.")
            .schema(PerformanceTools.sectionSchema())
            .clientOnly()
            .readOnly()
            .closedWorld()
            .handler(context -> {
                final int durationSeconds = context.getBoundedInt("duration_seconds", 5, 1, 30);
                final double minPercent = Math.max(0.1D, Math.min(50.0D, context.getDouble("min_percent", 1.0D)));
                final int maxDepth = context.getBoundedInt("max_depth", 8, 1, 12);

                if (!PROFILING.compareAndSet(false, true)) {
                    return ToolResult.error("Another client profile is already running; wait for it.");
                }
                try {
                    final long startedNanos = System.nanoTime();
                    context.onGameThread(new Callable<Void>() {
                        @Override
                        public Void call() {
                            // Through the settings, not profiler.profilingEnabled. runGameLoop
                            // rewrites that flag every frame from these three settings, and does
                            // so at the one point where no section is open; setting it anywhere a
                            // mod can run would have the next endSection pop an empty stack and
                            // crash the client.
                            GameSettings game = Minecraft.getMinecraft().gameSettings;
                            savedShowDebugInfo = game.showDebugInfo;
                            savedShowProfilerChart = game.showDebugProfilerChart;
                            savedHideGui = game.hideGUI;
                            flagsOverridden = true;
                            game.showDebugInfo = true;
                            game.showDebugProfilerChart = true;
                            game.hideGUI = false;
                            return null;
                        }
                    });

                    PerformanceTools.sleepCancellable(context, durationSeconds * 1000L);

                    String tree = context.onGameThread(new Callable<String>() {
                        @Override
                        public String call() {
                            double meanFrameMs = DurationWindow.millis(
                                ClientFrameRecorder.intervals().summariseSince(startedNanos).meanNanos());
                            String rendered = PerformanceTools.renderSections(
                                Minecraft.getMinecraft().profiler, meanFrameMs, minPercent, maxDepth);
                            restoreDebugFlags();
                            return "# percent of frame, ~ms per frame (mean frame " + meanFrameMs + " ms)\n"
                                + rendered;
                        }
                    });
                    return ToolResult.text(tree);
                } finally {
                    // Released on the client thread, after the restore, so that a profile started
                    // the moment this one fails cannot have its own flags restored from under it.
                    context.onGameThreadAsync(new Runnable() {
                        @Override
                        public void run() {
                            restoreDebugFlags();
                            PROFILING.set(false);
                        }
                    });
                }
            })
            .build());
    }

    /** Client thread only. Safe to call twice. */
    private static void restoreDebugFlags() {
        if (!flagsOverridden) {
            return;
        }
        GameSettings game = Minecraft.getMinecraft().gameSettings;
        game.showDebugInfo = savedShowDebugInfo;
        game.showDebugProfilerChart = savedShowProfilerChart;
        game.hideGUI = savedHideGui;
        flagsOverridden = false;
    }

    // ------------------------------------------------------------------
    // Per-block and per-entity render cost
    // ------------------------------------------------------------------

    /** Per-object timings for the profile in progress. Client thread only. */
    private static Map<TileEntity, Timed> tileEntityTimes;
    private static Map<Entity, Timed> entityTimes;
    private static boolean finishGl;

    /** Most rows one list may return. Bounded because every row is paid for in a context window. */
    private static final int MAX_ROWS = 1000;

    private static void registerProfileRendering() {
        McpRegistry.registerTool(McpTool.named("client_profile_rendering")
            .title("Profile block and entity renderers")
            .description("Time every tile entity renderer (TESR) and entity renderer call for a few "
                + "seconds and report the most expensive blocks and entities in view, with "
                + "position, plus totals by type with the renderer class. This is the tool that "
                + "names the block or entity behind a slow frame. Costs are microseconds per "
                + "frame; at 60 FPS a whole frame has 16,667.\n\n"
                + "microsPerFrame averages over every profiled frame, so a block culled for part of "
                + "the window reads low. framesDrawn (out of 'frames') and microsPerCall tell 'cheap' "
                + "from 'not drawn'. Renderers that have barely run read high until the JIT has "
                + "compiled them — set warmup_seconds when the scene was just built. "
                + "timerOverheadMicrosPerCall is the wrapper's own cost per call, included in every "
                + "figure.\n\n"
                + "Only what is currently being rendered is measured — face the scene under test. "
                + "Blocks drawn as baked models have no per-block cost to time; see "
                + "client_profile_sections. Players are not covered. Times are CPU time submitting "
                + "draw calls; set gl_finish to include GPU time, which is more truthful for heavy "
                + "geometry and lowers FPS while measuring. A FastTESR's time covers filling the "
                + "shared buffer, not its draw. Blocks for warmup plus duration.")
            .schema(JsonSchema.object()
                .integer("duration_seconds", "How long to measure. Default 5.", 1, 30)
                .integer("warmup_seconds", "Run the timers this long first and discard it, so "
                    + "renderers are JIT-compiled before the measured window. Default 0.", 0, 15)
                .integer("top", "Rows per list. Default 15. Every row is roughly 100 bytes of "
                    + "response.", 1, MAX_ROWS)
                .integer("offset", "Skip this many of the costliest rows, to page past 'top'. "
                    + "Default 0.", 0, 100000)
                .string("type", "Only rows for this block or entity id, e.g. 'minecraft:chest'.")
                .integer("x", "With radius: only rows within radius blocks of this X.")
                .integer("y", "With radius: only rows within radius blocks of this Y.")
                .integer("z", "With radius: only rows within radius blocks of this Z.")
                .integer("radius", "Distance on each axis from x, y and z, a box. Needs x, y and z.",
                    0, 4096)
                .number("min_micros", "Only rows costing at least this many microseconds per "
                    + "frame. Default 0.", 0.0D, 1000000.0D)
                .bool("gl_finish", "Wait for the GPU around each renderer call. Default false.")
                .build())
            .clientOnly()
            .readOnly()
            .closedWorld()
            .handler(context -> {
                final int durationSeconds = context.getBoundedInt("duration_seconds", 5, 1, 30);
                final int warmupSeconds = context.getBoundedInt("warmup_seconds", 0, 0, 15);
                final boolean glFinish = context.getBoolean("gl_finish", false);
                final RowFilter filter = RowFilter.from(context);
                if (filter.error != null) {
                    return ToolResult.error(filter.error);
                }

                if (!PROFILING.compareAndSet(false, true)) {
                    return ToolResult.error("Another client profile is already running; wait for it.");
                }
                try {
                    context.onGameThread(new Callable<Void>() {
                        @Override
                        public Void call() {
                            installTimingRenderers(glFinish);
                            return null;
                        }
                    });

                    if (warmupSeconds > 0) {
                        // Timed and thrown away rather than simply waited out: the wrappers are part
                        // of what gets compiled, and a warm-up without them would leave the first
                        // measured frames paying for their compilation instead.
                        PerformanceTools.sleepCancellable(context, warmupSeconds * 1000L);
                    }

                    final long startedNanos = System.nanoTime();
                    final long framesBefore = context.onGameThread(new Callable<Long>() {
                        @Override
                        public Long call() {
                            tileEntityTimes = new IdentityHashMap<TileEntity, Timed>();
                            entityTimes = new IdentityHashMap<Entity, Timed>();
                            return ClientFrameClock.frames();
                        }
                    });

                    PerformanceTools.sleepCancellable(context, durationSeconds * 1000L);

                    JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                        @Override
                        public JsonObject call() {
                            Map<TileEntity, Timed> tileEntities = tileEntityTimes;
                            Map<Entity, Timed> entities = entityTimes;
                            removeTimingRenderers();
                            long frames = Math.max(1L, ClientFrameClock.frames() - framesBefore);
                            JsonObject json = new JsonObject();
                            json.add("tileEntities", tileEntityReport(tileEntities, frames, filter));
                            json.add("entities", entityReport(entities, frames, filter));
                            json.addProperty("frames", frames);
                            json.addProperty("timerOverheadMicrosPerCall",
                                DurationWindow.micros(measureTimerOverheadNanos()));
                            return json;
                        }
                    });
                    result.addProperty("meanRenderWorkMs", DurationWindow.millis(
                        ClientFrameRecorder.renderWork().summariseSince(startedNanos).meanNanos()));
                    if (warmupSeconds > 0) {
                        result.addProperty("warmupSeconds", warmupSeconds);
                    }
                    result.addProperty("glFinish", glFinish);
                    return ToolResult.structured(result);
                } finally {
                    // Released on the client thread for the reason client_profile_sections does it.
                    context.onGameThreadAsync(new Runnable() {
                        @Override
                        public void run() {
                            removeTimingRenderers();
                            PROFILING.set(false);
                        }
                    });
                }
            })
            .build());
    }

    /**
     * Swaps every registered renderer for a timing wrapper around it. Client thread only.
     *
     * <p>For the length of one profile and no longer. While the wrappers are in, a mod that fetches
     * its own renderer back out of either map and casts it to its own class would fail the cast.
     * Vanilla never does — its only such cast is for players, whose renderers live in a separate
     * map this leaves alone — and being in the maps for seconds rather than for the session is what
     * keeps the exposure to mods small.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void installTimingRenderers(boolean glFinish) {
        tileEntityTimes = new IdentityHashMap<TileEntity, Timed>();
        entityTimes = new IdentityHashMap<Entity, Timed>();
        finishGl = glFinish;
        Map renderers = TileEntityRendererDispatcher.instance.renderers;
        for (Object object : renderers.entrySet()) {
            Map.Entry entry = (Map.Entry) object;
            if (entry.getValue() != null && !(entry.getValue() instanceof TimingRenderer)) {
                entry.setValue(new TimingRenderer((TileEntitySpecialRenderer) entry.getValue()));
            }
        }
        Map entityRenderers = Minecraft.getMinecraft().getRenderManager().entityRenderMap;
        for (Object object : entityRenderers.entrySet()) {
            Map.Entry entry = (Map.Entry) object;
            if (entry.getValue() != null && !(entry.getValue() instanceof TimingEntityRenderer)) {
                entry.setValue(new TimingEntityRenderer((Render) entry.getValue()));
            }
        }
    }

    /**
     * Puts the real renderers back. Client thread only, and safe to call when nothing is installed.
     *
     * <p>Walks the maps rather than a list saved at install time: both dispatchers cache a renderer
     * they found through a superclass under the subclass's key, so a map can have gained entries —
     * holding a wrapper — since then.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeTimingRenderers() {
        Map renderers = TileEntityRendererDispatcher.instance.renderers;
        for (Object object : renderers.entrySet()) {
            Map.Entry entry = (Map.Entry) object;
            if (entry.getValue() instanceof TimingRenderer) {
                entry.setValue(((TimingRenderer) entry.getValue()).delegate);
            }
        }
        Map entityRenderers = Minecraft.getMinecraft().getRenderManager().entityRenderMap;
        for (Object object : entityRenderers.entrySet()) {
            Map.Entry entry = (Map.Entry) object;
            if (entry.getValue() instanceof TimingEntityRenderer) {
                entry.setValue(((TimingEntityRenderer) entry.getValue()).delegate);
            }
        }
        tileEntityTimes = null;
        entityTimes = null;
    }

    /**
     * What one tile entity or entity cost while the profile ran. Client thread only.
     *
     * <p>{@code framesDrawn} is what makes a low average readable. {@code nanos / frames} alone
     * cannot tell a block that is cheap on every frame from one that was culled for most of the
     * window; counting the frames in which it was called at all can.
     */
    private static final class Timed {
        final String renderer;
        long nanos;
        long calls;
        double sumSquaredNanos;
        long framesDrawn;
        long lastFrame = -1L;

        Timed(String renderer) {
            this.renderer = renderer;
        }
    }

    /**
     * Records one call. {@code renderer} is the wrapped renderer's class, taken at the call rather
     * than looked up afterwards: a lookup through the dispatcher once the wrappers are out found
     * nothing for some tile entities, and the row went out with no renderer at all.
     */
    private static <T> void record(Map<T, Timed> times, T object, long nanos, String renderer) {
        if (times == null || object == null) {
            return;
        }
        Timed timed = times.get(object);
        if (timed == null) {
            times.put(object, timed = new Timed(renderer));
        }
        timed.nanos += nanos;
        timed.calls++;
        timed.sumSquaredNanos += (double) nanos * nanos;
        long frame = ClientFrameClock.frames();
        if (frame != timed.lastFrame) {
            timed.lastFrame = frame;
            timed.framesDrawn++;
        }
    }

    /**
     * What one timed call costs with nothing inside it: the clock reads and the bookkeeping.
     *
     * <p>Every figure the profile reports includes this once per call. For a renderer costing two
     * microseconds it is noise; for a hundred cheap calls a frame it is not, and it is only
     * subtractable if it is reported.
     */
    private static double measureTimerOverheadNanos() {
        Map<Object, Timed> scratch = new IdentityHashMap<Object, Timed>();
        Object subject = new Object();
        int iterations = 20000;
        long started = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long callStarted = System.nanoTime();
            record(scratch, subject, System.nanoTime() - callStarted, "");
        }
        return (System.nanoTime() - started) / (double) iterations;
    }

    private static long startTiming() {
        if (finishGl) {
            // Drain what is already queued, so it is not billed to this object.
            GL11.glFinish();
        }
        return System.nanoTime();
    }

    private static long stopTiming(long started) {
        if (finishGl) {
            GL11.glFinish();
        }
        return System.nanoTime() - started;
    }

    /** One rendered object, reduced to what the report prints. */
    private static final class Rendered {
        String type;
        String renderer;
        BlockPos blockPos;
        Timed timed;
        double nanosPerFrame;
    }

    /**
     * The row filters {@code client_profile_rendering} takes. Applied before anything is summed, so
     * the type totals describe the same rows the costliest list does.
     */
    private static final class RowFilter {
        String type;
        BlockPos centre;
        int radius;
        double minNanosPerFrame;
        int top;
        int offset;
        String error;

        static RowFilter from(com.micatechnologies.minecraft.mcmcp.mcp.ToolContext context) {
            RowFilter filter = new RowFilter();
            filter.top = context.getBoundedInt("top", 15, 1, MAX_ROWS);
            filter.offset = context.getBoundedInt("offset", 0, 0, 100000);
            filter.type = context.getString("type", null);
            filter.minNanosPerFrame = Math.max(0.0D, context.getDouble("min_micros", 0.0D)) * 1000.0D;
            boolean anyCentre = context.has("x") || context.has("y") || context.has("z");
            if (context.has("radius") || anyCentre) {
                if (!context.has("radius") || !context.has("x") || !context.has("y") || !context.has("z")) {
                    filter.error = "A region filter needs all of x, y, z and radius.";
                    return filter;
                }
                filter.centre = new BlockPos(context.requireInt("x"), context.requireInt("y"),
                    context.requireInt("z"));
                filter.radius = context.getBoundedInt("radius", 0, 0, 4096);
            }
            return filter;
        }

        boolean isFiltering() {
            return type != null || centre != null || minNanosPerFrame > 0.0D;
        }

        boolean accepts(Rendered row) {
            if (type != null && !type.equals(row.type)) {
                return false;
            }
            if (centre != null && (Math.abs(row.blockPos.getX() - centre.getX()) > radius
                || Math.abs(row.blockPos.getY() - centre.getY()) > radius
                || Math.abs(row.blockPos.getZ() - centre.getZ()) > radius)) {
                return false;
            }
            return row.nanosPerFrame >= minNanosPerFrame;
        }
    }

    /** Client thread only, after the wrappers are out. */
    private static JsonObject tileEntityReport(Map<TileEntity, Timed> times, long frames, RowFilter filter) {
        List<Rendered> rows = new ArrayList<Rendered>();
        if (times != null) {
            for (Map.Entry<TileEntity, Timed> entry : times.entrySet()) {
                TileEntity tileEntity = entry.getKey();
                Rendered row = new Rendered();
                row.timed = entry.getValue();
                row.nanosPerFrame = row.timed.nanos / (double) frames;
                ResourceLocation block = tileEntity.getBlockType() == null ? null
                    : tileEntity.getBlockType().getRegistryName();
                row.type = block == null ? tileEntity.getClass().getSimpleName() : block.toString();
                row.renderer = row.timed.renderer;
                row.blockPos = tileEntity.getPos();
                rows.add(row);
            }
        }
        return renderedSection(rows, frames, filter, "block");
    }

    /** Client thread only, after the wrappers are out. */
    private static JsonObject entityReport(Map<Entity, Timed> times, long frames, RowFilter filter) {
        List<Rendered> rows = new ArrayList<Rendered>();
        if (times != null) {
            for (Map.Entry<Entity, Timed> entry : times.entrySet()) {
                Entity entity = entry.getKey();
                Rendered row = new Rendered();
                row.timed = entry.getValue();
                row.nanosPerFrame = row.timed.nanos / (double) frames;
                ResourceLocation key = EntityList.getKey(entity);
                row.type = key == null ? entity.getName() : key.toString();
                row.renderer = row.timed.renderer;
                row.blockPos = GameJson.blockPosOf(entity);
                rows.add(row);
            }
        }
        return renderedSection(rows, frames, filter, "entity");
    }

    private static JsonObject renderedSection(List<Rendered> all, long frames, RowFilter filter,
                                              String typeLabel) {
        double totalNanos = 0.0D;
        List<Rendered> rows = new ArrayList<Rendered>();
        for (Rendered row : all) {
            totalNanos += row.nanosPerFrame;
            if (filter.accepts(row)) {
                rows.add(row);
            }
        }
        Collections.sort(rows, new Comparator<Rendered>() {
            @Override
            public int compare(Rendered a, Rendered b) {
                return Double.compare(b.nanosPerFrame, a.nanosPerFrame);
            }
        });

        // type -> {count, summed ns/frame, worst ns/frame, calls, summed ns, summed squared ns}
        final Map<String, double[]> byType = new HashMap<String, double[]>();
        Map<String, String> rendererOfType = new HashMap<String, String>();
        for (Rendered row : rows) {
            double[] sums = byType.get(row.type);
            if (sums == null) {
                byType.put(row.type, sums = new double[6]);
                rendererOfType.put(row.type, row.renderer);
            }
            sums[0]++;
            sums[1] += row.nanosPerFrame;
            sums[2] = Math.max(sums[2], row.nanosPerFrame);
            sums[3] += row.timed.calls;
            sums[4] += row.timed.nanos;
            sums[5] += row.timed.sumSquaredNanos;
        }

        JsonArray costliest = new JsonArray();
        for (int i = filter.offset; i < rows.size() && i < filter.offset + filter.top; i++) {
            Rendered row = rows.get(i);
            JsonObject json = new JsonObject();
            json.addProperty(typeLabel, row.type);
            json.add("pos", GameJson.blockPos(row.blockPos));
            json.addProperty("microsPerFrame", DurationWindow.micros(row.nanosPerFrame));
            json.addProperty("microsPerCall", DurationWindow.micros(row.timed.nanos / (double) row.timed.calls));
            json.addProperty("framesDrawn", row.timed.framesDrawn);
            costliest.add(json);
        }

        List<String> types = new ArrayList<String>(byType.keySet());
        Collections.sort(types, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Double.compare(byType.get(b)[1], byType.get(a)[1]);
            }
        });
        JsonArray typeRows = new JsonArray();
        for (int i = 0; i < types.size() && i < filter.top; i++) {
            double[] sums = byType.get(types.get(i));
            double meanNanosPerCall = sums[4] / sums[3];
            double variance = Math.max(0.0D, sums[5] / sums[3] - meanNanosPerCall * meanNanosPerCall);
            JsonObject json = new JsonObject();
            json.addProperty(typeLabel, types.get(i));
            // Always present, never null: Gson drops a null member outright, and a consumer reading
            // entry["renderer"] then fails on one row in five hundred.
            String renderer = rendererOfType.get(types.get(i));
            json.addProperty("renderer", renderer == null ? "unknown" : renderer);
            json.addProperty("count", (int) sums[0]);
            json.addProperty("totalMicrosPerFrame", DurationWindow.micros(sums[1]));
            json.addProperty("worstMicrosPerFrame", DurationWindow.micros(sums[2]));
            json.addProperty("microsPerCall", DurationWindow.micros(meanNanosPerCall));
            json.addProperty("callStdDevMicros", DurationWindow.micros(Math.sqrt(variance)));
            typeRows.add(json);
        }

        JsonObject json = new JsonObject();
        json.addProperty("rendered", all.size());
        json.addProperty("totalMicrosPerFrame", DurationWindow.micros(totalNanos));
        if (filter.isFiltering()) {
            json.addProperty("matched", rows.size());
        }
        if (rows.size() > filter.offset + filter.top) {
            json.addProperty("moreRows", rows.size() - filter.offset - filter.top);
        }
        json.add("costliest", costliest);
        json.add("byType", typeRows);
        return json;
    }

    /**
     * Times a tile entity renderer and otherwise stays out of its way.
     *
     * <p>Every public method is forwarded. The protected helpers ({@code bindTexture},
     * {@code getWorld}) are not, and do not need to be: they are only ever called by a renderer on
     * itself, and the delegate still holds the dispatcher it was given at registration.
     */
    private static final class TimingRenderer extends TileEntitySpecialRenderer<TileEntity> {

        final TileEntitySpecialRenderer<TileEntity> delegate;
        final String rendererName;

        TimingRenderer(TileEntitySpecialRenderer<TileEntity> delegate) {
            this.delegate = delegate;
            this.rendererName = delegate.getClass().getName();
        }

        @Override
        public void render(TileEntity te, double x, double y, double z, float partialTicks, int destroyStage,
                           float alpha) {
            long started = startTiming();
            try {
                delegate.render(te, x, y, z, partialTicks, destroyStage, alpha);
            } finally {
                record(tileEntityTimes, te, stopTiming(started), rendererName);
            }
        }

        @Override
        public void renderTileEntityFast(TileEntity te, double x, double y, double z, float partialTicks,
                                         int destroyStage, float partial, BufferBuilder buffer) {
            // No glFinish: nothing has been drawn. A fast renderer only appends vertices.
            long started = System.nanoTime();
            try {
                delegate.renderTileEntityFast(te, x, y, z, partialTicks, destroyStage, partial, buffer);
            } finally {
                record(tileEntityTimes, te, System.nanoTime() - started, rendererName);
            }
        }

        @Override
        public boolean isGlobalRenderer(TileEntity te) {
            return delegate.isGlobalRenderer(te);
        }

        @Override
        public void setRendererDispatcher(TileEntityRendererDispatcher dispatcher) {
            delegate.setRendererDispatcher(dispatcher);
        }

        @Override
        public FontRenderer getFontRenderer() {
            return delegate.getFontRenderer();
        }
    }

    /**
     * The entity twin of {@link TimingRenderer}.
     *
     * <p>{@code RenderManager} reaches a renderer through exactly the public methods forwarded here.
     * {@code getEntityTexture} is abstract and so has to exist, but nothing can call it: it is
     * protected, and only a renderer's own {@code doRender} ever asks — which here is the delegate's,
     * asking itself.
     */
    private static final class TimingEntityRenderer extends Render<Entity> {

        final Render<Entity> delegate;
        final String rendererName;

        TimingEntityRenderer(Render<Entity> delegate) {
            super(delegate.getRenderManager());
            this.delegate = delegate;
            this.rendererName = delegate.getClass().getName();
        }

        @Override
        public void doRender(Entity entity, double x, double y, double z, float entityYaw, float partialTicks) {
            long started = startTiming();
            try {
                delegate.doRender(entity, x, y, z, entityYaw, partialTicks);
            } finally {
                record(entityTimes, entity, stopTiming(started), rendererName);
            }
        }

        @Override
        public void renderMultipass(Entity entity, double x, double y, double z, float entityYaw,
                                    float partialTicks) {
            long started = startTiming();
            try {
                delegate.renderMultipass(entity, x, y, z, entityYaw, partialTicks);
            } finally {
                record(entityTimes, entity, stopTiming(started), rendererName);
            }
        }

        @Override
        public void doRenderShadowAndFire(Entity entity, double x, double y, double z, float yaw,
                                          float partialTicks) {
            // Part of what the entity costs to draw, and on a field of mobs not a small part.
            long started = startTiming();
            try {
                delegate.doRenderShadowAndFire(entity, x, y, z, yaw, partialTicks);
            } finally {
                record(entityTimes, entity, stopTiming(started), rendererName);
            }
        }

        @Override
        public boolean shouldRender(Entity entity, ICamera camera, double camX, double camY, double camZ) {
            return delegate.shouldRender(entity, camera, camX, camY, camZ);
        }

        @Override
        public boolean isMultipass() {
            return delegate.isMultipass();
        }

        @Override
        public void setRenderOutlines(boolean renderOutlines) {
            delegate.setRenderOutlines(renderOutlines);
        }

        @Override
        public void bindTexture(ResourceLocation location) {
            delegate.bindTexture(location);
        }

        @Override
        protected ResourceLocation getEntityTexture(Entity entity) {
            return null;
        }
    }
}
