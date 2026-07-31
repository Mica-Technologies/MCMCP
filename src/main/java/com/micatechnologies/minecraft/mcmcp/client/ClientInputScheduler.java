package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Holds synthetic key presses down for a requested number of ticks, then releases them.
 *
 * <h2>Why not just press and release</h2>
 *
 * Minecraft's input is sampled once per tick, not event-driven. A key set down and released inside
 * one scheduled task is never observed by the game at all — the movement input is read on the next
 * tick boundary and by then the key is already up. Anything that needs to last (walking forward,
 * holding to break a block, sprinting) has to stay down across real ticks, which means something has
 * to own the release. That is this class.
 *
 * <p>Presses are injected through {@link KeyBinding#setKeyBindState} and {@link KeyBinding#onTick},
 * the same two entry points the real keyboard handler uses. That matters: it means synthetic input
 * goes through the identical path as a human's keystroke — the same movement handling, the same
 * click cooldowns, the same anti-cheat surface on whatever server the player is on. There is no
 * separate "bot mode" that behaves differently from a person playing.
 *
 * <h2>Safety on disconnect</h2>
 *
 * {@link #releaseAll} runs whenever the player leaves a world. Without it, a hold in flight when the
 * connection drops leaves the key latched down: the player rejoins already walking forward, with no
 * physical key to let go of.
 */
@SideOnly(Side.CLIENT)
public final class ClientInputScheduler {

    private static final List<PendingRelease> PENDING = new ArrayList<>();

    private static boolean registered;

    private ClientInputScheduler() {
    }

    /** Subscribes to the client tick. Called once from the client proxy. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ClientInputScheduler.Events());
        registered = true;
    }

    /**
     * Presses {@code binding} and releases it {@code ticks} later.
     *
     * <p>Must be called on the client thread. The returned future completes on the client thread at
     * release time, so a tool can wait for the input to have actually finished before reporting
     * success — which is the difference between "I pressed forward" and "I walked forward for a
     * second".
     *
     * @param ticks how long to hold, in game ticks (20 per second); values below 1 are treated as 1
     */
    public static CompletableFuture<Void> hold(KeyBinding binding, int ticks) {
        int keyCode = binding.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, true);
        // onTick increments the binding's press counter, which is what isPressed() consumes. Without
        // it, click-style bindings (attack, use) register as "held" but never as "clicked", so
        // Minecraft.runTick never calls clickMouse and nothing happens.
        KeyBinding.onTick(keyCode);

        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (PENDING) {
            PENDING.add(new PendingRelease(binding, Math.max(1, ticks), future));
        }
        return future;
    }

    /**
     * Releases every held key immediately and completes the waiting futures.
     *
     * <p>Completes rather than cancels: the caller asked for input to be applied and it was, just
     * cut short. Failing those futures would surface as a tool error on an action that did happen.
     */
    public static void releaseAll() {
        List<PendingRelease> releasing;
        synchronized (PENDING) {
            releasing = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (PendingRelease pending : releasing) {
            try {
                KeyBinding.setKeyBindState(pending.binding.getKeyCode(), false);
            }
            catch (Exception e) {
                Mcmcp.LOGGER.error("MCMCP failed to release a synthetic key press", e);
            }
            pending.future.complete(null);
        }
    }

    public static int pendingCount() {
        synchronized (PENDING) {
            return PENDING.size();
        }
    }

    /**
     * Event subscriber, kept as a nested class so the static scheduler itself is never registered on
     * the event bus twice by an accidental second {@code register()}.
     */
    public static class Events {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world == null || mc.player == null) {
                // Left the world with input still held. Latched keys would carry into the next world.
                if (pendingCount() > 0) {
                    releaseAll();
                }
                return;
            }

            List<PendingRelease> completed = null;
            synchronized (PENDING) {
                Iterator<PendingRelease> iterator = PENDING.iterator();
                while (iterator.hasNext()) {
                    PendingRelease pending = iterator.next();
                    if (--pending.remainingTicks > 0) {
                        continue;
                    }
                    KeyBinding.setKeyBindState(pending.binding.getKeyCode(), false);
                    iterator.remove();
                    if (completed == null) {
                        completed = new ArrayList<>(2);
                    }
                    completed.add(pending);
                }
            }

            // Completing outside the lock: a future's completion runs the waiting tool's continuation
            // inline, and holding the scheduler lock through arbitrary tool code invites a deadlock
            // against another thread trying to start a hold.
            if (completed != null) {
                for (PendingRelease pending : completed) {
                    pending.future.complete(null);
                }
            }
        }
    }

    private static final class PendingRelease {

        final KeyBinding binding;
        final CompletableFuture<Void> future;

        int remainingTicks;

        PendingRelease(KeyBinding binding, int remainingTicks, CompletableFuture<Void> future) {
            this.binding = binding;
            this.remainingTicks = remainingTicks;
            this.future = future;
        }
    }
}
