package com.micatechnologies.minecraft.mcmcp.game;

import com.micatechnologies.minecraft.mcmcp.perf.DurationWindow;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Times every server tick, so tick statistics can cover minutes rather than the hundred ticks
 * {@code MinecraftServer.tickTimeArray} keeps.
 *
 * <p>Five seconds of history cannot tell a server that hitches once a minute from one that never
 * does, and a mean cannot tell either from one that is uniformly slow. The ring here holds ten
 * minutes at 20 TPS, which is what makes a p95 and a max over a five-minute window meaningful.
 *
 * <p>Measured from the START phase to the END phase of {@code ServerTickEvent}. Those bracket
 * everything {@code MinecraftServer.tick()} does apart from the autosave bookkeeping and the tick
 * time array itself, so this reads a hair under vanilla's own figure and never over it.
 *
 * <p>Common code: a dedicated server and an integrated one both fire the event, each on its own
 * server thread.
 */
public final class ServerTickRecorder {

    /** Ten minutes at 20 TPS. */
    private static final DurationWindow TICKS = new DurationWindow(12_000);

    private static boolean registered;

    /** Written by the server thread only. */
    private static long tickStartedNanos;
    private static volatile long tickCount;

    private ServerTickRecorder() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new Events());
        registered = true;
    }

    public static DurationWindow ticks() {
        return TICKS;
    }

    /** Ticks completed since the game started, across every server this JVM has run. */
    public static long tickCount() {
        return tickCount;
    }

    /** Nested so that a second {@link #register()} cannot subscribe a second copy. */
    public static class Events {

        // HIGHEST at the start and LOWEST at the end, so other mods' tick handlers fall inside the
        // measurement. A mod doing its work in a ServerTickEvent handler is a common cause of a slow
        // tick, and one the per-tile-entity profile cannot see.
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onTickStart(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                tickStartedNanos = System.nanoTime();
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onTickEnd(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END && tickStartedNanos != 0L) {
                long now = System.nanoTime();
                TICKS.record(now, now - tickStartedNanos);
                tickCount++;
            }
        }
    }
}
