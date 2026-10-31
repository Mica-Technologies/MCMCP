package com.micatechnologies.minecraft.mcmcp.client;

/**
 * The arithmetic between a screen's own coordinates and LWJGL's display pixels, with no Minecraft
 * class, so it can be unit-tested (#163).
 *
 * <p>Vanilla reads a mouse event as {@code x = eventX * width / displayWidth} and
 * {@code y = height - eventY * height / displayHeight - 1}, with the open screen's own
 * {@code width}/{@code height}. {@link #toLwjglX}/{@link #toLwjglY} invert exactly that, so a point
 * converted here reads back as the point it was asked for, at any scale, including a screen laid
 * out at a size other than the {@code ScaledResolution}.
 */
public final class MousePoints {

    private MousePoints() {
    }

    public static int toLwjglX(int guiX, int width, int displayWidth) {
        int w = Math.max(1, width);
        // The first display pixel inside the GUI cell: integer division in the read-back then lands
        // on guiX rather than one short.
        int x = ceilDiv(Math.max(0, guiX) * displayWidth, w);
        return Math.min(x, Math.max(0, displayWidth - 1));
    }

    public static int toLwjglY(int guiY, int height, int displayHeight) {
        int h = Math.max(1, height);
        int flipped = Math.max(0, h - 1 - guiY);
        // The read-back subtracts from the top, so the last display pixel of the flipped cell is
        // the one that names guiY.
        int y = ceilDiv((flipped + 1) * displayHeight, h) - 1;
        return Math.max(0, Math.min(y, displayHeight - 1));
    }

    public static int toGuiX(int lwjglX, int width, int displayWidth) {
        return lwjglX * width / Math.max(1, displayWidth);
    }

    public static int toGuiY(int lwjglY, int height, int displayHeight) {
        return height - lwjglY * height / Math.max(1, displayHeight) - 1;
    }

    /** A screenshot row (top-left origin) as an LWJGL row (bottom-left origin). */
    public static int pixelToLwjglY(int pixelY, int displayHeight) {
        return Math.max(0, displayHeight - 1 - pixelY);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
