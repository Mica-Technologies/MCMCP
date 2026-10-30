package com.micatechnologies.minecraft.mcmcp.perf;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Tells a server that stopped ticking on purpose from one that is stuck (#158).
 *
 * <p>Server-pause mods (LDFX's pauser, SUM's older one) stop ticking the worlds while nobody is online:
 * the server thread sleeps in the main loop instead of running a tick. The watchdog saw a thread that
 * finished no tick for ten seconds and called it a stall, wrote every thread's stack and a WARN, and
 * refused world changes for as long as the server stayed empty, every time the last player left.
 *
 * <p>The difference is where the thread is. A stalled server is inside a tick, blocked or busy. A
 * paused one is asleep in {@code MinecraftServer.run}, outside any tick, which a working server is too
 * but only for the few milliseconds between ticks. A mod can also say so directly through
 * {@link #declare}, so nothing depends on recognising its frames.
 *
 * <p>Imports no Minecraft class: it reads stack frames as names, so it is tested as plain Java.
 */
public final class IdlePause {

    /** The server's main loop, which a pause sleeps in. */
    static final String SERVER_CLASS = "net.minecraft.server.MinecraftServer";

    /**
     * A tick's methods, by dev name and SRG name: {@code tick}, {@code updateTimeLightAndEntities}. A
     * thread asleep under one of these is inside a tick, which a pause never is.
     */
    private static final String[] TICK_METHODS = {"tick", "func_71217_p", "updateTimeLightAndEntities", "func_71190_q"};

    @Nullable
    private static volatile String declared;

    private IdlePause() {
    }

    /**
     * For a pause mod: says the server is paused on purpose, with who paused it, or null when it
     * runs again. Looked up reflectively, so a mod needs no compile-time dependency on MCMCP:
     * {@code Class.forName("com.micatechnologies.minecraft.mcmcp.perf.IdlePause")
     * .getMethod("declare", String.class).invoke(null, "LDFX pauser")}.
     */
    public static void declare(@Nullable String pausedBy) {
        declared = pausedBy;
    }

    @Nullable
    public static String declared() {
        return declared;
    }

    /**
     * Why the server thread is idle on purpose, or null when it is not: a declared pause, or the main
     * loop asleep outside any tick.
     *
     * @param stack the server thread's stack, innermost frame first
     */
    @Nullable
    public static String reason(@Nullable StackTraceElement[] stack) {
        String said = declared;
        if (said != null) {
            return "paused by " + said;
        }
        if (stack == null || stack.length == 0 || !isSleep(stack[0])) {
            return null;
        }
        boolean inMainLoop = false;
        String pauser = null;
        for (StackTraceElement frame : stack) {
            if (isTick(frame)) {
                return null;
            }
            if (SERVER_CLASS.equals(frame.getClassName()) && "run".equals(frame.getMethodName())) {
                inMainLoop = true;
            }
            // A mixin's handler keeps the target's class but carries the mod's name in its method.
            if (pauser == null && (frame.getClassName() + "." + frame.getMethodName()).toLowerCase(Locale.ROOT).contains("pause")) {
                pauser = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName();
            }
        }
        if (!inMainLoop) {
            return null;
        }
        return pauser != null ? "paused (" + pauser + ")" : "asleep in the main loop, outside any tick";
    }

    private static boolean isSleep(StackTraceElement frame) {
        return "java.lang.Thread".equals(frame.getClassName()) && frame.getMethodName().startsWith("sleep");
    }

    private static boolean isTick(StackTraceElement frame) {
        String method = frame.getMethodName();
        for (String tick : TICK_METHODS) {
            if (tick.equals(method) && frame.getClassName().startsWith("net.minecraft.server.")) {
                return true;
            }
        }
        return false;
    }
}
