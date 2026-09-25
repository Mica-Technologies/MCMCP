package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.perf.GameThreads;
import com.micatechnologies.minecraft.mcmcp.perf.StallDetector;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Notices when the client thread stops, and gives the human their mouse back when it does.
 *
 * <h2>The problem</h2>
 *
 * A grabbed mouse on Windows is a {@code ClipCursor} rectangle around the game window. LWJGL lifts
 * it when the window loses focus — from its message pump, which runs on the client thread. When that
 * thread hangs, nothing lifts it: alt-tab changes the foreground window and the cursor stays locked
 * inside a frozen one. Everything MCMCP offers for getting out, the input lock's double Escape
 * included, also needs the client thread. The only exit left was killing the process.
 *
 * <h2>What this does</h2>
 *
 * A daemon thread reads {@link ClientFrameClock}'s counters once a second and hands them to
 * {@link StallDetector#CLIENT_THREAD}. When they have not moved for the configured time it:
 *
 * <ul>
 *   <li>lifts the cursor clip, from this thread — the clip is system-wide state, not tied to the
 *       thread that set it;</li>
 *   <li>if the stall reaches a minute, logs the client thread's full stack, the integrated server's
 *       if there is one, and any deadlock the JVM can see, because a hang that cannot be reproduced
 *       is diagnosed from that dump or not at all;</li>
 *   <li>and leaves the detector saying so, which is what {@code game_health}, tool timeouts and the
 *       orchestrator's roster read.</li>
 * </ul>
 *
 * <h2>Why lifting the clip is safe when the thread was only slow</h2>
 *
 * A world load stops the counters for as long as it takes, but a loading screen is a GUI and the
 * mouse is not grabbed under one, so there is no clip to lift — LWJGL's own reset is a no-op when it
 * did not clip. A long hitch in game ends with LWJGL clipping again the next time the window is
 * activated, which is the same thing it does after any alt-tab.
 *
 * <p>The cursor is not un-hidden. {@code ShowCursor} keeps its count per thread, so calling it here
 * would change this thread's count and nothing else; and a hidden cursor comes back by itself as soon
 * as it is over any window other than the frozen one, whose {@code WM_SETCURSOR} is what hid it.
 */
@SideOnly(Side.CLIENT)
public final class ClientWatchdog {

    private static final long POLL_MILLIS = 1_000L;

    /**
     * How long a stall runs before the stacks are logged.
     *
     * <p>Later than the stall itself, because starting an integrated server stops frames for as long
     * as it takes, and on a large pack that is routinely past the threshold. Dumping every thread at
     * WARN on every world load would teach people to skip MCMCP's warnings. A hang lasts forever, so
     * waiting costs nothing when it is one.
     */
    private static final long DUMP_AFTER_MILLIS = 60_000L;

    private static boolean started;

    /** Whether this stall's stacks have been logged. Watchdog thread only. */
    private static boolean dumped;

    private ClientWatchdog() {
    }

    /** Starts the watchdog thread. Called once from the client proxy; later calls change nothing. */
    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                watch();
            }
        }, "MCMCP-client-watchdog");
        // Never the reason a game will not exit.
        thread.setDaemon(true);
        thread.start();
    }

    private static void watch() {
        StallDetector detector = StallDetector.CLIENT_THREAD;
        while (true) {
            try {
                Thread.sleep(POLL_MILLIS);
                // Read every time, like the other limits, so /mcmcp reload applies it.
                detector.setThresholdMillis(McmcpConfig.getClientStallSeconds() * 1000L);
                // Frames or ticks: a client that renders nothing but still ticks is not hung.
                StallDetector.Transition transition =
                    detector.observe(ClientFrameClock.frames() + ClientFrameClock.ticks(), System.nanoTime());
                if (transition == StallDetector.Transition.STALLED) {
                    onStalled(detector);
                }
                else if (transition == StallDetector.Transition.RECOVERED) {
                    dumped = false;
                    Mcmcp.LOGGER.info("MCMCP: the client thread is finishing frames again.");
                }
                if (detector.isStalled() && !dumped
                    && detector.silentMillis(System.nanoTime()) >= Math.max(DUMP_AFTER_MILLIS, detector.thresholdMillis())) {
                    dumped = true;
                    logStacks(detector);
                }
            }
            catch (InterruptedException e) {
                return;
            }
            catch (Throwable t) {
                // This thread is the way out of a hung game. Losing it to one bad dump would leave the
                // next hang with none.
                Mcmcp.LOGGER.error("MCMCP's client watchdog hit an error and carries on", t);
            }
        }
    }

    private static void onStalled(StallDetector detector) {
        long seconds = detector.silentMillis(System.nanoTime()) / 1000L;
        Mcmcp.LOGGER.info("MCMCP: the client thread has not finished a frame or tick in " + seconds
            + " s, so it is loading, blocked or hung. " + CursorClip.release());
    }

    private static void logStacks(StallDetector detector) {
        long seconds = detector.silentMillis(System.nanoTime()) / 1000L;
        StringBuilder message = new StringBuilder();
        message.append("MCMCP: the client thread has still not finished a frame or tick after ").append(seconds)
            .append(" s. Its stack, and the integrated server's if there is one:\n");
        appendDump(message, GameThreads.CLIENT);
        appendDump(message, GameThreads.SERVER);
        List<String> deadlocked = GameThreads.deadlocked();
        if (!deadlocked.isEmpty()) {
            message.append("Deadlocked threads: ").append(deadlocked).append('\n');
        }
        Mcmcp.LOGGER.warn(message.toString());
    }

    private static void appendDump(StringBuilder message, String threadName) {
        long id = GameThreads.find(threadName);
        // find() falls back to a substring match; for a dump, only the thread itself will do.
        ThreadInfo info = GameThreads.snapshot(id);
        if (info != null && info.getThreadName().equals(threadName)) {
            message.append(GameThreads.dump(info));
        }
    }

    /**
     * Lifts the OS cursor clip from a thread other than the one that set it.
     *
     * <p>LWJGL 2's own {@code WindowsDisplay.resetCursorClipping} first: it is static, touches no
     * window state, and only unclips if LWJGL clipped, so it can never undo a clip some other program
     * set. Where that class is absent — an LWJGL 3 launch — {@code user32!ClipCursor(NULL)} through the
     * JNA that Minecraft ships for its narrator.
     */
    static final class CursorClip {

        private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

        private CursorClip() {
        }

        /** Returns a sentence for the log saying what happened. */
        static String release() {
            if (!WINDOWS) {
                return "The mouse is not released automatically on this platform.";
            }
            Method reset = lwjglReset();
            try {
                if (reset != null) {
                    reset.invoke(null);
                    return "Released the mouse cursor, if the game was holding it.";
                }
                com.sun.jna.Function.getFunction("user32", "ClipCursor", com.sun.jna.Function.ALT_CONVENTION)
                    .invokeInt(new Object[] { null });
                return "Released the mouse cursor, if the game was holding it.";
            }
            catch (Throwable t) {
                return "Could not release the mouse cursor (" + t + ").";
            }
        }

        @Nullable
        private static Method lwjglReset() {
            try {
                Method method = Class.forName("org.lwjgl.opengl.WindowsDisplay")
                    .getDeclaredMethod("resetCursorClipping");
                method.setAccessible(true);
                return method;
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }
    }
}
