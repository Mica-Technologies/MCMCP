package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Runs work on the client thread from the client <em>tick</em> rather than from Minecraft's
 * scheduled-task queue.
 *
 * <h2>Why this exists</h2>
 *
 * {@link net.minecraft.client.Minecraft#addScheduledTask} is the usual way onto the client thread,
 * and {@link ClientThreadBridge} uses it for nearly everything. But Minecraft drains that queue
 * inside {@code synchronized (this.scheduledTasks)} in {@code runGameLoop}, so a scheduled task
 * runs <em>while holding the queue's lock</em>. Any task that blocks the client thread waiting on
 * another thread therefore deadlocks if that other thread needs to schedule a task of its own.
 *
 * <p>Leaving a world is exactly that shape. {@code sendQuittingDisconnectingPacket} ends in
 * {@code NetworkManager.closeChannel}, which blocks on {@code awaitUninterruptibly} until Netty has
 * closed the channel; Netty's close fires Forge's {@code ClientDisconnectionFromServerEvent}, and a
 * mod handling that event commonly calls {@code addScheduledTask} to get its cleanup onto the
 * client thread. The client thread holds the lock and waits for Netty; the Netty thread waits for
 * the lock. Neither moves, and the game hangs at the moment of disconnect.
 *
 * <p>Found against Minecraft City Super Mod, whose disconnect handler clears client-side sound,
 * strobe and display-list caches. The mod is doing an ordinary thing — the fault was in driving a
 * blocking world-leave from inside the task drain.
 *
 * <p>{@link net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent} fires from
 * {@code Minecraft.runTick}, outside that synchronized block, which is also where the vanilla
 * "Save and Quit to Title" button runs. Work posted here therefore executes in the same context a
 * human's quit does, and the disconnect event's handlers can schedule freely.
 *
 * @author Mica Technologies
 */
@SideOnly(Side.CLIENT)
public final class ClientDeferredTasks {

    private static final List<PendingTask> PENDING = new ArrayList<>();

    private static boolean registered;

    private ClientDeferredTasks() {
    }

    /** Subscribes to the client tick. Safe to call more than once. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ClientDeferredTasks.Events());
        registered = true;
    }

    /**
     * Queues {@code task} to run on the client thread at the end of the next client tick.
     *
     * <p>Callable from any thread, including the client thread itself — a caller already on the
     * client thread still gets deferral, which is the entire point when the caller is running
     * inside the scheduled-task drain.</p>
     *
     * @param task what to run
     *
     * @return a future completing after the task has run, or completing exceptionally if it threw
     */
    public static CompletableFuture<Void> runNextTick(Runnable task) {
        register();
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (PENDING) {
            PENDING.add(new PendingTask(task, future));
        }
        return future;
    }

    /**
     * Returns how many tasks are waiting to run. Diagnostics only.
     *
     * @return the pending task count
     */
    public static int pendingCount() {
        synchronized (PENDING) {
            return PENDING.size();
        }
    }

    /**
     * Event subscriber, kept as a nested class so the static holder is never itself registered on
     * the event bus twice by an accidental second {@code register()}.
     */
    public static class Events {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            List<PendingTask> due;
            synchronized (PENDING) {
                if (PENDING.isEmpty()) {
                    return;
                }
                due = new ArrayList<>(PENDING);
                PENDING.clear();
            }

            // Run outside the lock: these tasks are arbitrary, may take a while (a world leave
            // saves and shuts down the integrated server), and may queue further work.
            for (PendingTask pending : due) {
                try {
                    pending.task.run();
                    pending.future.complete(null);
                }
                catch (Throwable t) {
                    // Never rethrow onto the client thread: an escaping exception here is a crash
                    // report and a lost session, for what is usually a tool argument problem.
                    Mcmcp.LOGGER.error("MCMCP deferred client task failed", t);
                    pending.future.completeExceptionally(t);
                }
            }
        }
    }

    private static final class PendingTask {

        final Runnable task;
        final CompletableFuture<Void> future;

        PendingTask(Runnable task, CompletableFuture<Void> future) {
            this.task = task;
            this.future = future;
        }
    }
}
