package com.micatechnologies.minecraft.mcmcp.client;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Counts the frames and client ticks the game has finished, so a worker can wait for the game to have
 * actually run rather than for a guess at how long that takes.
 *
 * <p>The two are counted separately because the game does different things on each. The camera is
 * turned by the mouse per <em>frame</em>; a mount clamps its rider's rotation, and a server's
 * correction is applied, per <em>tick</em>. A tool that wants to know whether something it wrote
 * survived contact with the game has to let both happen, and a fixed sleep does neither reliably: at
 * 120 frames a second it waits several times longer than it needs to, and on a client struggling at
 * eight it can return before a single frame has been drawn.
 *
 * <p>Both are counted at phase END, so an increment seen after a write means a whole frame or tick
 * ran after it, not one that was already under way.
 */
@SideOnly(Side.CLIENT)
public final class ClientFrameClock {

    private static boolean registered;

    /** Written by the client thread only; read from workers. */
    private static volatile long frames;
    private static volatile long ticks;

    private ClientFrameClock() {
    }

    /** Subscribes to the client and render ticks. Called once from the client proxy's tool setup. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ClientFrameClock.Events());
        registered = true;
    }

    public static long frames() {
        return frames;
    }

    public static long ticks() {
        return ticks;
    }

    /**
     * Blocks until {@code minFrames} frames and {@code minTicks} ticks have completed, or
     * {@code timeoutMillis} passes. Must not be called on the client thread, which is the thread
     * that would have to run for the wait to end.
     *
     * @return whether both were reached; false means the game is not running freely — hitching,
     *         loading, or not rendering — and the caller is reading early
     */
    public static boolean await(int minFrames, int minTicks, long timeoutMillis)
        throws InterruptedException {

        long startFrames = frames;
        long startTicks = ticks;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (frames - startFrames < minFrames || ticks - startTicks < minTicks) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(2L);
        }
        return true;
    }

    /** Nested for the reason {@link ClientInputLock.Events} is: a second register() cannot double it. */
    public static class Events {

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                frames++;
            }
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                ticks++;
            }
        }
    }
}
