package com.micatechnologies.minecraft.mcmcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The coordinate conversion behind synthetic clicks (#163). */
class MousePointsTest {

    /** {screen width, screen height, display width, display height}. */
    private static final int[][] SIZES = {
        {854, 480, 854, 480},     // GUI scale 1
        {427, 240, 854, 480},     // scale 2, or LDIC's scaled screen at scale 1
        {284, 160, 854, 480},     // scale 3, which does not divide evenly
        {640, 360, 1920, 1080},
        {480, 270, 1920, 1080},
        {213, 120, 854, 480},
    };

    @Test
    void everyPointInAScreenReadsBackAsItselfAtAnySize() {
        for (int[] size : SIZES) {
            for (int x = 0; x < size[0]; x++) {
                int lwjgl = MousePoints.toLwjglX(x, size[0], size[2]);
                assertTrue(lwjgl >= 0 && lwjgl < size[2]);
                assertEquals(x, MousePoints.toGuiX(lwjgl, size[0], size[2]), "x " + x + " at " + size[0]);
            }
            for (int y = 0; y < size[1]; y++) {
                int lwjgl = MousePoints.toLwjglY(y, size[1], size[3]);
                assertTrue(lwjgl >= 0 && lwjgl < size[3]);
                assertEquals(y, MousePoints.toGuiY(lwjgl, size[1], size[3]), "y " + y + " at " + size[1]);
            }
        }
    }

    @Test
    void aScreenshotPixelLandsWhereTheScreenItselfWouldReadIt() {
        // LDIC: a 427x240 screen on an 854x480 display. Pixel (400, 200) is gui (200, 100) to that
        // screen, whatever the ScaledResolution says.
        int y = MousePoints.pixelToLwjglY(200, 480);
        assertEquals(200, MousePoints.toGuiX(400, 427, 854));
        assertEquals(100, MousePoints.toGuiY(y, 240, 480));
    }

    @Test
    void theTopScreenshotRowIsTheTopLwjglRow() {
        assertEquals(479, MousePoints.pixelToLwjglY(0, 480));
        assertEquals(0, MousePoints.pixelToLwjglY(479, 480));
    }
}
