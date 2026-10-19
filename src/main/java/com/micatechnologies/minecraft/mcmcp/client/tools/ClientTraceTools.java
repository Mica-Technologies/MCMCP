package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.perf.PoseTrace;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * {@code client_trace}: an entity's or the camera's pose on every tick or frame over a span (#109).
 *
 * <p>Verifying a rider-camera fix needed the riding player's position and yaw on each of 1,000
 * ticks of a coaster lap. A loop of {@code client_player_state} calls is about 1.5 KB a sample, 20
 * calls and 1.5 MB for the lap, and misses samples; the session that needed it wrote a script
 * against the game's HTTP endpoint instead. This records in-process, from the tick and camera
 * events, so no sample is missed, and returns what is odd about the run rather than the run.
 *
 * <p>Yaw is the raw {@code rotationYaw}, not the wrapped value snapshots report: wrapping hides the
 * 360° jump that vanilla interpolates into a spin, and makes every crossing of ±180 look like one.
 */
@SideOnly(Side.CLIENT)
public final class ClientTraceTools {

    static final int MAX_TICK_SAMPLES = 2400;
    static final int MAX_FRAME_SAMPLES = 7200;
    /** As server_run_commands: a call past two minutes is one a client may give up on. */
    static final long MAX_MILLIS = 125_000L;
    static final int MAX_INLINE = 2000;

    private static final AtomicBoolean TRACING = new AtomicBoolean();
    /** The trace being recorded, or null. Written by the handler, read on the client thread. */
    @Nullable
    private static volatile Recorder active;

    private ClientTraceTools() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Events());
        registerTrace();
    }

    private static final class Recorder {

        final PoseTrace trace;
        final String target;
        final boolean frames;
        final boolean velocity;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String stopReason;

        Recorder(PoseTrace trace, String target, boolean frames, boolean velocity) {
            this.trace = trace;
            this.target = target;
            this.frames = frames;
            this.velocity = velocity;
        }

        void stop(String reason) {
            if (stopReason == null) {
                stopReason = reason;
            }
            done.countDown();
        }
    }

    private static void registerTrace() {
        McpRegistry.registerTool(McpTool.named("client_trace")
            .title("Trace a pose every tick or frame")
            .description("Record something's position and rotation on every client tick (or every "
                + "frame) over a span, in the game, so no sample is missed, and return what is odd about "
                + "it: per column the largest step and the samples past a threshold, with the interval "
                + "they recur at when they do. The full trace goes to a CSV under mcmcp/dumps, or inline "
                + "with output 'columns'. Use it to catch a camera spin (a 360° yaw jump in one tick) or "
                + "a position correction every few ticks; one call instead of a loop of "
                + "client_player_state.\n\n"
                + "Yaw is raw rotationYaw, unwrapped. per 'frame' samples the camera as drawn, from "
                + "Forge's CameraSetup after every mod has changed it: yaw (Forge's, the entity's + 180), "
                + "pitch and roll, with the target's interpolated position. Positions are judged on change of velocity, angles "
                + "on their step. Blocks for the span, at most two minutes.")
            .schema(JsonSchema.object()
                .string("target", "'player' (default), 'riding' (what the player rides), or an entity id.")
                .enumeration("per", "'tick' (default) or 'frame'.", "tick", "frame")
                .integer("samples", "How many to record. Default 200; at most " + MAX_TICK_SAMPLES
                    + " ticks or " + MAX_FRAME_SAMPLES + " frames.", 1, MAX_FRAME_SAMPLES)
                .bool("velocity", "Also record motionX/Y/Z (tick only). Default false.")
                .number("angleThreshold", "Degrees an angle's step must pass to be listed. Default 30.", 0.0, 3600.0)
                .number("positionThreshold", "Blocks per sample² a change of velocity must pass to be "
                    + "listed. Default 0.25.", 0.0, 1000.0)
                .enumeration("output", "'summary' (default): the summary and the CSV's path. 'columns': "
                    + "also every sample inline, as arrays, up to " + MAX_INLINE + " samples.",
                    "summary", "columns")
                .build())
            .clientOnly()
            .readOnly()
            .closedWorld()
            .handler(context -> {
                final String target = context.getString("target", "player").trim().toLowerCase(java.util.Locale.ROOT);
                final boolean frames = "frame".equals(context.getString("per", "tick"));
                final boolean velocity = !frames && context.getBoolean("velocity", false);
                final int samples = context.getBoundedInt("samples", 200, 1,
                    frames ? MAX_FRAME_SAMPLES : MAX_TICK_SAMPLES);
                final double angleThreshold = context.getDouble("angleThreshold", 30.0D);
                final double positionThreshold = context.getDouble("positionThreshold", 0.25D);
                final boolean inline = "columns".equals(context.getString("output", "summary"));
                if (inline && samples > MAX_INLINE) {
                    return ToolResult.error("output 'columns' returns at most " + MAX_INLINE + " samples "
                        + "inline; ask for fewer, or take the summary and read the CSV.");
                }
                if (!"player".equals(target) && !"riding".equals(target) && !target.matches("-?\\d+")) {
                    return ToolResult.error("target is 'player', 'riding' or an entity id; got '" + target + "'.");
                }
                if (!TRACING.compareAndSet(false, true)) {
                    return ToolResult.error("A trace is already recording; wait for it to finish.");
                }
                try {
                    String missing = context.onGameThread(() -> resolve(Minecraft.getMinecraft(), target) == null
                        ? describeMissing(Minecraft.getMinecraft(), target) : null);
                    if (missing != null) {
                        return ToolResult.error(missing);
                    }
                    PoseTrace trace = frames
                        ? new PoseTrace(samples, "x", "y", "z", "cameraYaw", "cameraPitch", "cameraRoll",
                            "entityYaw", "partialTicks")
                        : velocity
                            ? new PoseTrace(samples, "x", "y", "z", "yaw", "pitch", "prevYaw", "vx", "vy", "vz")
                            : new PoseTrace(samples, "x", "y", "z", "yaw", "pitch", "prevYaw");
                    Recorder recorder = new Recorder(trace, target, frames, velocity);
                    active = recorder;
                    long started = System.currentTimeMillis();
                    while (!recorder.done.await(250, TimeUnit.MILLISECONDS)) {
                        if (context.getCancellation().isCancelled()) {
                            recorder.stop("cancelled");
                        }
                        else if (System.currentTimeMillis() - started > MAX_MILLIS) {
                            recorder.stop("time limit of two minutes");
                        }
                        else {
                            context.reportProgress(trace.size(), samples, "Recorded " + trace.size() + " of " + samples);
                        }
                    }
                    active = null;
                    // The client thread may be inside add() for one last sample; wait a frame for it.
                    context.onGameThread(() -> null);

                    JsonObject json = trace.summary(angleThreshold, positionThreshold);
                    json.addProperty("target", target);
                    json.addProperty("per", frames ? "frame" : "tick");
                    if (recorder.stopReason != null && !"done".equals(recorder.stopReason)) {
                        json.addProperty("stoppedEarly", recorder.stopReason);
                    }
                    File directory = new File(McmcpPaths.gameDirectory(), "mcmcp/dumps");
                    File file = new File(directory, "trace-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                        + ".csv");
                    try {
                        if (directory.isDirectory() || directory.mkdirs()) {
                            Files.write(file.toPath(), trace.csv().getBytes(StandardCharsets.UTF_8));
                            json.addProperty("file", file.getAbsolutePath());
                        }
                    } catch (java.io.IOException e) {
                        json.addProperty("fileError", e.getMessage());
                    }
                    if (inline) {
                        json.add("data", trace.columnsJson());
                    }
                    return ToolResult.structured(json);
                } finally {
                    active = null;
                    TRACING.set(false);
                }
            })
            .build());
    }

    @Nullable
    private static Entity resolve(Minecraft mc, String target) {
        if (mc.world == null || mc.player == null) {
            return null;
        }
        if ("player".equals(target)) {
            return mc.player;
        }
        if ("riding".equals(target)) {
            return mc.player.getRidingEntity();
        }
        return mc.world.getEntityByID(Integer.parseInt(target));
    }

    private static String describeMissing(Minecraft mc, String target) {
        if (mc.world == null || mc.player == null) {
            return "Not in a world: there is nothing to trace.";
        }
        if ("riding".equals(target)) {
            return "The player is not riding anything. Board it first, or trace it by its entity id "
                + "(client_nearby_entities lists ids).";
        }
        return "No entity " + target + " in the client's world. client_nearby_entities lists ids.";
    }

    /** On the client thread, inside an event: nothing may escape, or the game crashes. */
    private static void sampleSafely(Recorder recorder, @Nullable EntityViewRenderEvent.CameraSetup camera) {
        try {
            sample(recorder, camera);
        } catch (RuntimeException e) {
            recorder.stop("sampling failed: " + e);
        }
    }

    private static void sample(Recorder recorder, @Nullable EntityViewRenderEvent.CameraSetup camera) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity entity = resolve(mc, recorder.target);
        if (entity == null) {
            recorder.stop(mc.world == null ? "the world was left" : "the target is gone ("
                + ("riding".equals(recorder.target) ? "the player dismounted" : "entity unloaded or removed") + ")");
            return;
        }
        long tick = mc.world.getTotalWorldTime();
        if (camera != null) {
            double partial = camera.getRenderPartialTicks();
            recorder.trace.add(tick,
                entity.prevPosX + (entity.posX - entity.prevPosX) * partial,
                entity.prevPosY + (entity.posY - entity.prevPosY) * partial,
                entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partial,
                camera.getYaw(), camera.getPitch(), camera.getRoll(), entity.rotationYaw, partial);
        }
        else if (recorder.velocity) {
            recorder.trace.add(tick, entity.posX, entity.posY, entity.posZ, entity.rotationYaw,
                entity.rotationPitch, entity.prevRotationYaw, entity.motionX, entity.motionY, entity.motionZ);
        }
        else {
            recorder.trace.add(tick, entity.posX, entity.posY, entity.posZ, entity.rotationYaw,
                entity.rotationPitch, entity.prevRotationYaw);
        }
        if (recorder.trace.isFull()) {
            recorder.stop("done");
        }
    }

    /** Nested so the listener has no statics of its own for the bus to register twice. */
    public static class Events {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            Recorder recorder = active;
            if (event.phase == TickEvent.Phase.END && recorder != null && !recorder.frames
                && recorder.done.getCount() > 0) {
                sampleSafely(recorder, null);
            }
        }

        /** Lowest priority: the camera as drawn, after every mod that turns or rolls it. */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
            Recorder recorder = active;
            if (recorder != null && recorder.frames && recorder.done.getCount() > 0) {
                sampleSafely(recorder, event);
            }
        }
    }
}
