package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.perf.DurationWindow;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Times every frame, two ways, because a frame rate alone cannot show what a change cost.
 *
 * <p><b>Frame interval</b> — END of one render tick to END of the next — is what the player sees, and
 * what FPS is the reciprocal of. But it includes the buffer swap and the frame limiter's wait, so on a
 * client capped at 60 it reads 16.7 ms whether the frame took two milliseconds of work or fifteen. A
 * block that doubles render cost is invisible in it until the cap is breached.
 *
 * <p><b>Render work</b> — START to END of one render tick — brackets
 * {@code EntityRenderer.updateCameraAndRender}: the world, the GUI, every tile entity renderer. It
 * excludes the swap and the wait, so it moves with the cost of the scene even under a cap, and it is
 * the number to compare before and after a change.
 *
 * <p>Vanilla's own {@code FrameTimer} keeps 240 frames, which is two seconds at 120 FPS. These rings
 * hold over a minute at 240.
 *
 * <p>Kept apart from {@link ClientFrameClock}, which counts frames so that tools can wait on them and
 * has no reason to know what any of them cost.
 */
@SideOnly(Side.CLIENT)
public final class ClientFrameRecorder {

    private static final DurationWindow INTERVALS = new DurationWindow(16_384);
    private static final DurationWindow RENDER_WORK = new DurationWindow(16_384);

    private static boolean registered;

    /** Written by the client thread only. */
    private static long renderStartedNanos;
    private static long lastFrameEndedNanos;

    private ClientFrameRecorder() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new Events());
        registered = true;
    }

    public static DurationWindow intervals() {
        return INTERVALS;
    }

    public static DurationWindow renderWork() {
        return RENDER_WORK;
    }

    /** Nested for the reason {@link ClientFrameClock.Events} is. */
    public static class Events {

        // HIGHEST then LOWEST, so other mods' render tick handlers — HUD overlays, mostly — are
        // inside the measurement rather than around it.
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onRenderStart(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                renderStartedNanos = System.nanoTime();
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onRenderEnd(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.END || renderStartedNanos == 0L) {
                return;
            }
            long now = System.nanoTime();
            RENDER_WORK.record(now, now - renderStartedNanos);
            if (lastFrameEndedNanos != 0L) {
                INTERVALS.record(now, now - lastFrameEndedNanos);
            }
            lastFrameEndedNanos = now;
        }
    }
}
