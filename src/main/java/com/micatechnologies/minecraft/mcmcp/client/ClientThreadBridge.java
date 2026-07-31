package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * {@link GameThreadBridge} for the client main thread.
 *
 * <p>{@code @SideOnly(Side.CLIENT)} and a client-only package: this class references
 * {@link Minecraft}, so a dedicated server that so much as class-loads it dies at startup with
 * Forge's "Attempted to load class ... for invalid side SERVER". Nothing in common code may name
 * this type — it is reached only through {@code McmcpClientProxy}.
 *
 * <p>The client main thread is also the render thread, which is what makes the debug tools
 * possible: work scheduled here runs with a live OpenGL context, so a screenshot can read the
 * framebuffer and a synthetic keypress lands in the same input pipeline the player's own keyboard
 * feeds. It is equally why the timeouts here matter — every millisecond spent in a scheduled task
 * is a millisecond the frame is not being drawn, and a tool that blocks for a second produces a
 * visible hitch.
 */
@SideOnly(Side.CLIENT)
public class ClientThreadBridge implements GameThreadBridge {

    @Override
    public McmcpSide getSide() {
        return McmcpSide.CLIENT;
    }

    @Override
    public boolean isAvailable() {
        return isClientUp(Minecraft.getMinecraft());
    }

    /**
     * Whether the client has finished starting and can accept scheduled work.
     *
     * <p>Deliberately not "is a world loaded" — plenty of useful tools (screenshot, GUI state, log
     * tail) work at the main menu, and gating those on a world would make the mod useless for
     * exactly the navigate-the-menus automation it is meant to support.
     *
     * <p>The framebuffer is the signal because 1.12.2 offers no public one. {@code Minecraft.running}
     * is package-private and there is no {@code isRunning()} accessor, so it cannot be read from
     * here. {@code getFramebuffer()} is public and returns the framebuffer created during
     * {@code Minecraft.init()}, so a non-null value means graphics initialisation completed — which
     * is both a real liveness test and precisely the precondition the screenshot tools need.
     *
     * <p>It cannot detect a client that has begun shutting down; nothing public can. That case is
     * handled by the timeout in {@link #callOnGameThread}, which is the honest backstop: a task
     * scheduled onto a stopped client simply never runs.
     */
    private static boolean isClientUp(Minecraft mc) {
        return mc != null && mc.getFramebuffer() != null;
    }

    @Override
    public boolean isGameThread() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.isCallingFromMinecraftThread();
    }

    @Override
    public <T> T callOnGameThread(Callable<T> task, long timeoutMillis) throws Exception {
        if (isGameThread()) {
            return task.call();
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (!isClientUp(mc)) {
            throw new IllegalStateException("The Minecraft client is not running");
        }

        final CompletableFuture<T> future = new CompletableFuture<>();
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    future.complete(task.call());
                }
                catch (Throwable t) {
                    // Never rethrow onto the client thread: an escaping exception here is a crash
                    // report and a lost session, for what is usually a tool argument problem.
                    future.completeExceptionally(t);
                }
            }
        });

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            throw new TimeoutException("Client thread did not run the task within " + timeoutMillis + " ms");
        }
    }

    @Override
    public void runOnGameThread(final Runnable task) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!isClientUp(mc)) {
            Mcmcp.LOGGER.warn("Dropped a scheduled MCMCP task: the client is not running");
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                }
                catch (Throwable t) {
                    Mcmcp.LOGGER.error("MCMCP task failed on the client thread", t);
                }
            }
        });
    }
}
