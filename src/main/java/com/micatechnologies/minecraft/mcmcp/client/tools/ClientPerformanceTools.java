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

    /** Nanoseconds and calls per object for the profile in progress. Client thread only. */
    private static Map<TileEntity, long[]> tileEntityTimes;
    private static Map<Entity, long[]> entityTimes;
    private static boolean finishGl;

    private static void registerProfileRendering() {
        McpRegistry.registerTool(McpTool.named("client_profile_rendering")
            .title("Profile block and entity renderers")
            .description("Time every tile entity renderer (TESR) and entity renderer call for a few "
                + "seconds and report the most expensive blocks and entities in view, with "
                + "position, plus totals by type with the renderer class. This is the tool that "
                + "names the block or entity behind a slow frame. Costs are microseconds per "
                + "frame; at 60 FPS a whole frame has 16,667.\n\n"
                + "Only what is currently being rendered is measured — face the scene under test. "
                + "Blocks drawn as baked models have no per-block cost to time; see "
                + "client_profile_sections. Players are not covered. Times are CPU time submitting "
                + "draw calls; set gl_finish to include GPU time, which is more truthful for heavy "
                + "geometry and lowers FPS while measuring. A FastTESR's time covers filling the "
                + "shared buffer, not its draw. Blocks for the duration.")
            .schema(JsonSchema.object()
                .integer("duration_seconds", "How long to measure. Default 5.", 1, 30)
                .integer("top", "Rows per list. Default 15.", 1, 50)
                .bool("gl_finish", "Wait for the GPU around each renderer call. Default false.")
                .build())
            .clientOnly()
            .readOnly()
            .closedWorld()
            .handler(context -> {
                final int durationSeconds = context.getBoundedInt("duration_seconds", 5, 1, 30);
                final int top = context.getBoundedInt("top", 15, 1, 50);
                final boolean glFinish = context.getBoolean("gl_finish", false);

                if (!PROFILING.compareAndSet(false, true)) {
                    return ToolResult.error("Another client profile is already running; wait for it.");
                }
                try {
                    final long startedNanos = System.nanoTime();
                    final long framesBefore = context.onGameThread(new Callable<Long>() {
                        @Override
                        public Long call() {
                            installTimingRenderers(glFinish);
                            return ClientFrameClock.frames();
                        }
                    });

                    PerformanceTools.sleepCancellable(context, durationSeconds * 1000L);

                    JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                        @Override
                        public JsonObject call() {
                            Map<TileEntity, long[]> tileEntities = tileEntityTimes;
                            Map<Entity, long[]> entities = entityTimes;
                            removeTimingRenderers();
                            long frames = Math.max(1L, ClientFrameClock.frames() - framesBefore);
                            JsonObject json = new JsonObject();
                            json.add("tileEntities", tileEntityReport(tileEntities, frames, top));
                            json.add("entities", entityReport(entities, frames, top));
                            json.addProperty("frames", frames);
                            return json;
                        }
                    });
                    result.addProperty("meanRenderWorkMs", DurationWindow.millis(
                        ClientFrameRecorder.renderWork().summariseSince(startedNanos).meanNanos()));
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
        tileEntityTimes = new IdentityHashMap<TileEntity, long[]>();
        entityTimes = new IdentityHashMap<Entity, long[]>();
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

    private static <T> void record(Map<T, long[]> times, T object, long nanos) {
        if (times == null || object == null) {
            return;
        }
        long[] entry = times.get(object);
        if (entry == null) {
            times.put(object, entry = new long[2]);
        }
        entry[0] += nanos;
        entry[1]++;
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
        JsonObject pos;
        double nanosPerFrame;
    }

    /** Client thread only, after the wrappers are out — so renderer lookups name the real class. */
    private static JsonObject tileEntityReport(Map<TileEntity, long[]> times, long frames, int top) {
        List<Rendered> rows = new ArrayList<Rendered>();
        if (times != null) {
            for (Map.Entry<TileEntity, long[]> entry : times.entrySet()) {
                TileEntity tileEntity = entry.getKey();
                Rendered row = new Rendered();
                row.nanosPerFrame = entry.getValue()[0] / (double) frames;
                ResourceLocation block = tileEntity.getBlockType() == null ? null
                    : tileEntity.getBlockType().getRegistryName();
                row.type = block == null ? tileEntity.getClass().getSimpleName() : block.toString();
                TileEntitySpecialRenderer<?> renderer =
                    TileEntityRendererDispatcher.instance.getRenderer(tileEntity);
                row.renderer = renderer == null ? null : renderer.getClass().getName();
                row.pos = GameJson.blockPos(tileEntity.getPos());
                rows.add(row);
            }
        }
        return renderedSection(rows, top, "block");
    }

    /** Client thread only, after the wrappers are out. */
    private static JsonObject entityReport(Map<Entity, long[]> times, long frames, int top) {
        List<Rendered> rows = new ArrayList<Rendered>();
        if (times != null) {
            for (Map.Entry<Entity, long[]> entry : times.entrySet()) {
                Entity entity = entry.getKey();
                Rendered row = new Rendered();
                row.nanosPerFrame = entry.getValue()[0] / (double) frames;
                ResourceLocation key = EntityList.getKey(entity);
                row.type = key == null ? entity.getName() : key.toString();
                Render<?> renderer = Minecraft.getMinecraft().getRenderManager().getEntityRenderObject(entity);
                row.renderer = renderer == null ? null : renderer.getClass().getName();
                row.pos = GameJson.blockPos(GameJson.blockPosOf(entity));
                rows.add(row);
            }
        }
        return renderedSection(rows, top, "entity");
    }

    private static JsonObject renderedSection(List<Rendered> rows, int top, String typeLabel) {
        Collections.sort(rows, new Comparator<Rendered>() {
            @Override
            public int compare(Rendered a, Rendered b) {
                return Double.compare(b.nanosPerFrame, a.nanosPerFrame);
            }
        });

        double totalNanos = 0.0D;
        // type -> {count, summed ns/frame, worst ns/frame}
        final Map<String, double[]> byType = new HashMap<String, double[]>();
        Map<String, String> rendererOfType = new HashMap<String, String>();
        for (Rendered row : rows) {
            totalNanos += row.nanosPerFrame;
            double[] sums = byType.get(row.type);
            if (sums == null) {
                byType.put(row.type, sums = new double[3]);
                rendererOfType.put(row.type, row.renderer);
            }
            sums[0]++;
            sums[1] += row.nanosPerFrame;
            sums[2] = Math.max(sums[2], row.nanosPerFrame);
        }

        JsonArray costliest = new JsonArray();
        for (int i = 0; i < rows.size() && i < top; i++) {
            JsonObject json = new JsonObject();
            json.addProperty(typeLabel, rows.get(i).type);
            json.add("pos", rows.get(i).pos);
            json.addProperty("microsPerFrame", DurationWindow.micros(rows.get(i).nanosPerFrame));
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
        for (int i = 0; i < types.size() && i < top; i++) {
            double[] sums = byType.get(types.get(i));
            JsonObject json = new JsonObject();
            json.addProperty(typeLabel, types.get(i));
            json.addProperty("renderer", rendererOfType.get(types.get(i)));
            json.addProperty("count", (int) sums[0]);
            json.addProperty("totalMicrosPerFrame", DurationWindow.micros(sums[1]));
            json.addProperty("worstMicrosPerFrame", DurationWindow.micros(sums[2]));
            typeRows.add(json);
        }

        JsonObject json = new JsonObject();
        json.addProperty("rendered", rows.size());
        json.addProperty("totalMicrosPerFrame", DurationWindow.micros(totalNanos));
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

        TimingRenderer(TileEntitySpecialRenderer<TileEntity> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void render(TileEntity te, double x, double y, double z, float partialTicks, int destroyStage,
                           float alpha) {
            long started = startTiming();
            try {
                delegate.render(te, x, y, z, partialTicks, destroyStage, alpha);
            } finally {
                record(tileEntityTimes, te, stopTiming(started));
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
                record(tileEntityTimes, te, System.nanoTime() - started);
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

        TimingEntityRenderer(Render<Entity> delegate) {
            super(delegate.getRenderManager());
            this.delegate = delegate;
        }

        @Override
        public void doRender(Entity entity, double x, double y, double z, float entityYaw, float partialTicks) {
            long started = startTiming();
            try {
                delegate.doRender(entity, x, y, z, entityYaw, partialTicks);
            } finally {
                record(entityTimes, entity, stopTiming(started));
            }
        }

        @Override
        public void renderMultipass(Entity entity, double x, double y, double z, float entityYaw,
                                    float partialTicks) {
            long started = startTiming();
            try {
                delegate.renderMultipass(entity, x, y, z, entityYaw, partialTicks);
            } finally {
                record(entityTimes, entity, stopTiming(started));
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
                record(entityTimes, entity, stopTiming(started));
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
