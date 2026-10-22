package com.micatechnologies.minecraft.mcmcp.game;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolActivity;
import com.micatechnologies.minecraft.mcmcp.perf.GameThreads;
import com.micatechnologies.minecraft.mcmcp.perf.StallDetector;
import com.micatechnologies.minecraft.mcmcp.perf.TickClock;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;

/**
 * Watches a dedicated server's thread from a thread of its own, and says when it stops finishing
 * ticks (#133).
 *
 * <h2>Why off the server thread</h2>
 *
 * Everything else MCMCP knows about the server's health it measures on the server thread, in ticks,
 * so it goes blind exactly when ticks stop. On AltoTEST the server thread stopped with nothing in the
 * log: vanilla's own watchdog fires only after a single tick of 60 s, and back-to-back ticks of a few
 * seconds each never reach that, while every player times out. This thread reads
 * {@link TickClock#SERVER}'s count once a second and hands it to a {@link StallDetector}. When no tick
 * has finished for {@code health.stallDumpSeconds}:
 *
 * <ul>
 *   <li>{@link ServerLoad} says {@link ServerLoad.Level#STALLED}, so calls that change the world are
 *       refused and jobs wait;</li>
 *   <li>the server thread's whole stack, every other thread's, and any deadlock the JVM can see are
 *       written to {@code mcmcp/stalls/<time>.txt} under the game folder, once per stall, and one WARN
 *       names the file — a stall that cannot be reproduced is diagnosed from that or not at all;</li>
 *   <li>and when ticks resume, one line says so and how long it lasted.</li>
 * </ul>
 *
 * <h2>Dedicated servers only</h2>
 *
 * An integrated server stops ticking whenever its player pauses the game, which from here is
 * indistinguishable from a stall. The client watchdog already dumps the integrated server's stack when
 * the client stalls.
 *
 * <p>This thread never schedules onto the server thread: {@code addScheduledTask} waits on the queue's
 * lock, which the server thread holds while it runs a task, and that is exactly what a stall can be.
 */
public final class ServerWatchdog {

    private static final long POLL_MILLIS = 1_000L;

    private static boolean started;

    /** The current server's detector; null between servers and on an integrated server. */
    @Nullable
    private static volatile StallDetector detector;

    /** Watchdog thread only: when the current stall's ticks stopped, or 0 when there is none. */
    private static long stalledSinceNanos;

    private ServerWatchdog() {
    }

    /** Starts watching a dedicated server, starting the thread the first time. */
    public static synchronized void serverStarting(MinecraftServer server) {
        if (!server.isDedicatedServer()) {
            return;
        }
        // A fresh detector: it arms on the first tick it sees, not on a count left from before.
        detector = new StallDetector(GameThreads.SERVER);
        if (started) {
            return;
        }
        started = true;
        Thread thread = new Thread(ServerWatchdog::watch, "MCMCP-server-watchdog");
        // Never the reason a server will not exit.
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void serverStopping() {
        // A server saving on its way out is not stalled.
        detector = null;
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(POLL_MILLIS);
                observe();
                // Announces a level the interval held back once it is over, even with no ticks to do it.
                ServerHealthNotices.poll(System.currentTimeMillis());
            }
            catch (InterruptedException e) {
                return;
            }
            catch (Throwable t) {
                // This thread is how a stalled server gets diagnosed. Losing it to one bad dump would
                // leave the next stall with none.
                Mcmcp.LOGGER.error("MCMCP's server watchdog hit an error and carries on", t);
            }
        }
    }

    private static void observe() {
        StallDetector current = detector;
        MinecraftServer server = ServerThreadBridge.server();
        if (current == null || server == null || !server.isServerRunning()) {
            if (stalledSinceNanos != 0L) {
                stalledSinceNanos = 0L;
                ServerLoad.stalled(false, 0L);
            }
            return;
        }
        // Read every time, like the other limits, so /mcmcp reload applies it.
        current.setThresholdMillis(McmcpConfig.getHealthStallDumpSeconds() * 1000L);
        long now = System.nanoTime();
        StallDetector.Transition transition = current.observe(TickClock.SERVER.count(), now);
        long silent = current.silentMillis(now);
        if (transition == StallDetector.Transition.STALLED) {
            stalledSinceNanos = now - silent * 1_000_000L;
            String file = writeDump(silent);
            ServerLoad.stallDumped(file);
            ServerLoad.stalled(true, silent);
            Mcmcp.LOGGER.warn("MCMCP: the server thread has finished no tick in " + silent / 1000L + " s. Calls that "
                + "change the world are refused until it does. "
                + (file == null ? "Its stack could not be written; see the error above." : "Every thread's stack is in " + file + "."));
        }
        else if (transition == StallDetector.Transition.RECOVERED) {
            long lasted = stalledSinceNanos == 0L ? 0L : (now - stalledSinceNanos) / 1_000_000L;
            stalledSinceNanos = 0L;
            ServerLoad.stalled(false, 0L);
            Mcmcp.LOGGER.info("MCMCP: the server thread is finishing ticks again, after about " + lasted / 1000L
                + " s without one.");
        }
        else if (current.isStalled()) {
            ServerLoad.stalled(true, silent);
        }
    }

    /**
     * Writes every thread's stack, the server thread's first, to {@code mcmcp/stalls/<time>.txt}.
     *
     * @return the file's path, or null if it could not be written
     */
    @Nullable
    private static String writeDump(long silentMillis) {
        StringBuilder out = new StringBuilder();
        out.append("MCMCP ").append(McmcpConstants.MOD_VERSION).append(": the server thread has finished no tick in ")
            .append(silentMillis / 1000L).append(" s, at ").append(new Date()).append(".\n");
        out.append("Before the stall the server was ").append(ServerLoad.describe()).append(".\n\n");
        List<ToolActivity.RunningCall> calls = ToolActivity.running();
        out.append("MCMCP calls running: ").append(calls.size()).append('\n');
        long nowMillis = System.currentTimeMillis();
        for (ToolActivity.RunningCall call : calls) {
            out.append("  ").append(call.getSide().id()).append(' ').append(call.getTool()).append(" for ")
                .append(call.getWho() == null ? "?" : call.getWho()).append(", running ")
                .append((nowMillis - call.getStartedMillis()) / 1000L).append(" s");
            if (call.getMessage() != null) {
                out.append(" (").append(call.getMessage()).append(')');
            }
            out.append('\n');
        }
        out.append('\n');

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long serverId = GameThreads.find(GameThreads.SERVER);
        ThreadInfo server = GameThreads.snapshot(serverId);
        out.append("== The server thread ==\n");
        if (server != null && server.getThreadName().equals(GameThreads.SERVER)) {
            out.append(GameThreads.dump(server));
        }
        else {
            out.append("(no thread called \"").append(GameThreads.SERVER).append("\")\n");
        }
        List<String> deadlocked = GameThreads.deadlocked();
        if (!deadlocked.isEmpty()) {
            out.append("\nDeadlocked threads: ").append(deadlocked).append('\n');
        }
        out.append("\n== Every thread ==\n");
        for (ThreadInfo info : threads.dumpAllThreads(threads.isObjectMonitorUsageSupported(),
            threads.isSynchronizerUsageSupported())) {
            if (info != null) {
                out.append('\n').append(GameThreads.dump(info));
            }
        }

        File directory = new File(McmcpPaths.gameDirectory(), "mcmcp/stalls");
        File file = new File(directory, new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
        try {
            Files.createDirectories(directory.toPath());
            Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            return file.getAbsolutePath();
        }
        catch (IOException | RuntimeException e) {
            Mcmcp.LOGGER.error("MCMCP could not write the stall dump " + file, e);
            return null;
        }
    }
}
