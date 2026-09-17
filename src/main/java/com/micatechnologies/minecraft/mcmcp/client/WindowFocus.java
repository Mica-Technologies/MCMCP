package com.micatechnologies.minecraft.mcmcp.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.Display;

/**
 * Whether the game window is really the one a person is using.
 *
 * <h2>Why not just {@code Display.isActive()}</h2>
 *
 * <p>On Windows, LWJGL 2's {@code isActive()} is a flag set by focus messages. A window that is
 * launched behind another application asks to be activated, Windows refuses to bring it to the
 * front, but the window still receives keyboard focus within its own thread — so the flag goes true
 * and, never having been in front, the window never receives the message that would clear it. The
 * game then believes it is focused for as long as nobody clicks into it and back out. Vanilla turns
 * the camera for any mouse passing over it, and never pauses on lost focus.
 *
 * <p>That is the ordinary state of a client started by a build tool or an agent. LWJGL knows about
 * it: its own mouse grabbing checks the foreground window as well as the flag. This does the same,
 * reaching the two private methods it uses. Anywhere they are not available — another platform, or
 * a different LWJGL — it falls back to {@code isActive()}, which is correct there.
 */
@SideOnly(Side.CLIENT)
public final class WindowFocus {

    @Nullable
    private static final Object WINDOWS_DISPLAY;
    @Nullable
    private static final Method GET_HWND;
    @Nullable
    private static final Method GET_FOREGROUND_WINDOW;

    static {
        Object display = null;
        Method hwnd = null;
        Method foreground = null;
        try {
            Field impl = Display.class.getDeclaredField("display_impl");
            impl.setAccessible(true);
            Object candidate = impl.get(null);
            if (candidate != null && "org.lwjgl.opengl.WindowsDisplay".equals(candidate.getClass().getName())) {
                hwnd = candidate.getClass().getDeclaredMethod("getHwnd");
                hwnd.setAccessible(true);
                foreground = candidate.getClass().getDeclaredMethod("getForegroundWindow");
                foreground.setAccessible(true);
                display = candidate;
            }
        }
        catch (ReflectiveOperationException | RuntimeException unavailable) {
            // Not LWJGL 2 on Windows. isActive() is the whole answer there.
            display = null;
            hwnd = null;
            foreground = null;
        }
        WINDOWS_DISPLAY = display;
        GET_HWND = hwnd;
        GET_FOREGROUND_WINDOW = foreground;
    }

    private WindowFocus() {
    }

    /** Client thread only. */
    public static boolean isFocused() {
        if (!Display.isCreated() || !Display.isActive()) {
            return false;
        }
        if (WINDOWS_DISPLAY == null) {
            return true;
        }
        try {
            long window = (Long) GET_HWND.invoke(WINDOWS_DISPLAY);
            long foreground = (Long) GET_FOREGROUND_WINDOW.invoke(null);
            return window == foreground;
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            // Trust LWJGL rather than treat the window as unfocused forever, which would swallow
            // every click a person makes.
            return true;
        }
    }
}
