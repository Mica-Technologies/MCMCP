package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ScreenshotImagesTest {

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void a_frame_already_within_the_cap_is_handed_back_untouched() {
        // Not merely "the same size": the same object. Re-encoding a PNG that did not need scaling
        // would spend CPU and lose nothing but time, and the caller uses identity to decide whether
        // it can send the bytes already on disk.
        BufferedImage source = image(800, 450);

        assertSame(source, ScreenshotImages.fitWithin(source, 1280));
    }

    @Test
    void a_cap_larger_than_the_frame_never_enlarges_it() {
        // Upscaling would cost tokens in exact proportion to the pixels invented, which is the worst
        // trade available: strictly more expensive, strictly no more information.
        BufferedImage source = image(640, 360);

        BufferedImage result = ScreenshotImages.fitWithin(source, ScreenshotImages.PROVIDER_CEILING);

        assertEquals(640, result.getWidth());
        assertEquals(360, result.getHeight());
    }

    @Test
    void scaling_a_widescreen_frame_keeps_its_aspect_ratio() {
        BufferedImage source = image(1920, 1080);

        BufferedImage result = ScreenshotImages.fitWithin(source, 640);

        assertEquals(640, result.getWidth());
        assertEquals(360, result.getHeight());
    }

    @Test
    void the_cap_applies_to_the_long_edge_whichever_edge_that_is() {
        // A player with a tall window is not a case anyone tests by hand, and capping width alone
        // would leave such a frame more expensive than the cap implies.
        BufferedImage source = image(1080, 1920);

        BufferedImage result = ScreenshotImages.fitWithin(source, 640);

        assertEquals(360, result.getWidth());
        assertEquals(640, result.getHeight());
    }

    @Test
    void a_cap_above_the_providers_ceiling_is_clamped_to_it() {
        // Resolution past the ceiling is resampled away before the image is ever tokenised, so
        // honouring a larger request would charge the caller for transfer that buys nothing. The
        // schema bounds this too; this is the check that does not depend on the caller obeying them.
        BufferedImage source = image(3840, 2160);

        BufferedImage result = ScreenshotImages.fitWithin(source, 4000);

        assertEquals(ScreenshotImages.PROVIDER_CEILING, result.getWidth());
    }

    @Test
    void a_scaled_frame_still_encodes_as_a_readable_png() throws IOException {
        BufferedImage source = image(1920, 1080);

        byte[] png = ScreenshotImages.toPng(ScreenshotImages.fitWithin(source, 640));

        assertTrue(png.length > 0);
        // The PNG signature, so a caller sending these bytes as image/png is not lying about them.
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'},
            new byte[] {png[0], png[1], png[2], png[3]});
    }

    @Test
    void the_token_estimate_tracks_area_rather_than_the_longest_edge() {
        // The whole reason the cap is worth having: halving the long edge quarters the cost. A model
        // reading this field back should see that, or it has no basis for choosing a smaller one.
        double large = ScreenshotImages.approximateTokens(1280, 720);
        double small = ScreenshotImages.approximateTokens(640, 360);

        assertEquals(large, small * 4.0D, 4.0D);
    }
}
