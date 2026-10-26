package com.micatechnologies.minecraft.mcmcp.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Downscaling for screenshots that are about to be sent to a model.
 *
 * <h2>Why a screenshot needs a size argument at all</h2>
 *
 * A model is charged for an image by its <em>dimensions</em>, not by the size of the file: roughly
 * width × height / 750 tokens, after the provider downscales anything longer than
 * {@link #PROVIDER_CEILING} pixels on its long edge. Two things follow, and both are the reason this
 * class exists.
 *
 * <p>First, the cost of {@code client_screenshot inline=true} was set by whatever the player had
 * dragged the game window to. A 1920×1080 client cost about 1,850 tokens a frame; the same
 * screenshot at 854×480 costs about 550. Nothing about the question being asked changed — only the
 * window size — and a model driving a GUI takes a great many frames.
 *
 * <p>Second, resolution above the ceiling is not merely wasteful, it is <em>inert</em>: it is
 * discarded before the image is ever tokenised, having been paid for in transfer the whole way. So
 * the cap is enforced here whatever the caller asks for.
 *
 * <p>The file written to disk is untouched by any of this. It is a developer's artifact, opened in
 * an image viewer at full resolution; only the copy that travels in the response is shrunk.
 *
 * <h2>No Minecraft classes</h2>
 *
 * Deliberately, and it is why this sits in {@code tools/} rather than beside the screenshot tool in
 * {@code client/}: scaling arithmetic and aspect-ratio handling are exactly the kind of thing that
 * gets subtly wrong and is trivial to unit-test, and a class that names {@code Minecraft} cannot be
 * tested at all. It operates on a PNG that has already been written, so it never touches the
 * framebuffer and never runs on the game thread.
 */
public final class ScreenshotImages {

    /**
     * The long edge above which a model's provider downscales the image itself.
     *
     * <p>Sending more than this buys no detail whatsoever — it is resampled away before tokenising —
     * while costing the full transfer. Treated as a hard ceiling rather than a suggestion.
     */
    public static final int PROVIDER_CEILING = 1568;

    /**
     * Default long edge for an inline screenshot.
     *
     * <p>720p reads GUI labels and the F3 overlay comfortably at about 1,230 tokens. The frequent
     * case — "did the screen I expected open" — is answerable far below this, which is what the
     * lower suggested values in the tool's schema are for.
     */
    public static final int DEFAULT_MAX_DIMENSION = 1280;

    /** Smallest cap worth offering; below this a GUI screenshot stops being readable. */
    public static final int MIN_MAX_DIMENSION = 128;

    private ScreenshotImages() {
    }

    /**
     * Scales {@code image} so neither edge exceeds {@code maxDimension}, preserving aspect ratio.
     *
     * <p>Never enlarges. A cap above the image's own size is not an instruction to upscale — it
     * would cost tokens in exact proportion to the pixels invented.
     *
     * @return the scaled image, or {@code image} itself when it already fits
     */
    public static BufferedImage fitWithin(BufferedImage image, int maxDimension) {
        int cap = Math.min(maxDimension, PROVIDER_CEILING);
        int width = image.getWidth();
        int height = image.getHeight();
        int longest = Math.max(width, height);
        if (longest <= cap || longest == 0) {
            return image;
        }

        double factor = (double) cap / (double) longest;
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));
        return resample(image, targetWidth, targetHeight);
    }

    /**
     * Resamples in halving steps until the last one, then interpolates the remainder.
     *
     * <p>A single bilinear pass from 1080p to 480p samples four source pixels per destination pixel
     * out of twenty, which drops thin features entirely — and in a Minecraft screenshot the thin
     * features are the text: item names, the F3 overlay, a button's label. Since reading that text
     * is frequently the entire reason the frame was requested, halving repeatedly (each step
     * averaging every pixel it discards) is worth the extra passes.
     */
    private static BufferedImage resample(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage current = source;
        int width = source.getWidth();
        int height = source.getHeight();

        while (width / 2 > targetWidth && height / 2 > targetHeight) {
            width /= 2;
            height /= 2;
            current = draw(current, width, height, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return draw(current, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage draw(BufferedImage source, int width, int height,
                                      Object interpolation) {
        // TYPE_INT_RGB, not the source's type: a screenshot has no alpha to preserve, and an
        // indexed or custom type coming back from ImageIO would otherwise be carried through the
        // whole chain and quantise every intermediate step.
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /** Encodes {@code image} as PNG bytes. */
    public static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    /** Columns a contact sheet of {@code count} frames is laid out in: 1, 2, 2x2, then 3 across. */
    public static int sheetColumns(int count) {
        return count <= 2 ? Math.max(1, count) : count <= 4 ? 2 : 3;
    }

    /**
     * Several frames tiled into one image no wider or taller than {@code maxDimension}, numbered from 1
     * in reading order. One image costs its area once, and one call is one turn: the views an agent
     * wants side by side, without a round trip and a full-size image for each.
     */
    public static BufferedImage contactSheet(List<BufferedImage> frames, int maxDimension) {
        int count = frames.size();
        int columns = sheetColumns(count);
        int rows = (count + columns - 1) / columns;
        BufferedImage first = frames.get(0);
        double aspect = first.getHeight() / (double) Math.max(1, first.getWidth());
        int gap = 2;
        int tileWidth = Math.max(16, (maxDimension - gap * (columns - 1)) / columns);
        int tileHeight = Math.max(9, (int) Math.round(tileWidth * aspect));
        int sheetHeight = rows * tileHeight + gap * (rows - 1);
        if (sheetHeight > maxDimension) {
            // Taller than wide: fit the height instead.
            tileHeight = Math.max(9, (maxDimension - gap * (rows - 1)) / rows);
            tileWidth = Math.max(16, (int) Math.round(tileHeight / aspect));
        }
        int width = columns * tileWidth + gap * (columns - 1);
        int height = rows * tileHeight + gap * (rows - 1);
        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, tileHeight / 12)));
            for (int i = 0; i < count; i++) {
                int left = (i % columns) * (tileWidth + gap);
                int top = (i / columns) * (tileHeight + gap);
                g.drawImage(frames.get(i), left, top, tileWidth, tileHeight, null);
                String label = String.valueOf(i + 1);
                int labelHeight = g.getFontMetrics().getHeight();
                int labelWidth = g.getFontMetrics().stringWidth(label) + 8;
                g.setColor(Color.BLACK);
                g.fillRect(left, top, labelWidth, labelHeight);
                g.setColor(Color.WHITE);
                g.drawString(label, left + 4, top + g.getFontMetrics().getAscent());
            }
        }
        finally {
            g.dispose();
        }
        return sheet;
    }

    /**
     * Roughly what an image of this size costs a model, in tokens.
     *
     * <p>Reported back in the tool result so the caller can see the price of the size it chose and
     * pick a smaller cap next time. Approximate by nature — the divisor is a published rule of thumb,
     * not a guarantee — which is why the field it feeds is named as an estimate.
     */
    public static int approximateTokens(int width, int height) {
        return (int) Math.round(width * (double) height / 750.0D);
    }
    /**
     * Whether a frame is one dark colour: brightness spread below a few levels in 255 and dark on
     * average, over a grid of samples. A view the renderer had not built comes out exactly so (#115),
     * and a careless reader takes it for the scene. A night sky or a dark room still has stars,
     * torches or edges, so it spreads more than this.
     */
    public static boolean isBlankDark(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width == 0 || height == 0) {
            return true;
        }
        double sum = 0.0D;
        double squares = 0.0D;
        int samples = 0;
        for (int gy = 0; gy < 24; gy++) {
            for (int gx = 0; gx < 32; gx++) {
                int rgb = image.getRGB(Math.min(width - 1, gx * width / 32 + width / 64),
                    Math.min(height - 1, gy * height / 24 + height / 48));
                double luma = 0.299D * ((rgb >> 16) & 0xFF) + 0.587D * ((rgb >> 8) & 0xFF) + 0.114D * (rgb & 0xFF);
                sum += luma;
                squares += luma * luma;
                samples++;
            }
        }
        double mean = sum / samples;
        double spread = Math.sqrt(Math.max(0.0D, squares / samples - mean * mean));
        return mean < 40.0D && spread < 3.0D;
    }

    /**
     * A crop as pixels {x, y, width, height}, clamped to the frame: fractions when all four are 0-1,
     * pixels otherwise. Null when nothing of it is inside the frame.
     */
    @javax.annotation.Nullable
    public static int[] cropRect(double[] crop, int width, int height) {
        boolean fractions = crop[0] <= 1.0D && crop[1] <= 1.0D && crop[2] <= 1.0D && crop[3] <= 1.0D;
        double sx = fractions ? width : 1.0D;
        double sy = fractions ? height : 1.0D;
        int x = (int) Math.max(0, Math.round(crop[0] * sx));
        int y = (int) Math.max(0, Math.round(crop[1] * sy));
        int right = (int) Math.min(width, Math.round((crop[0] + crop[2]) * sx));
        int bottom = (int) Math.min(height, Math.round((crop[1] + crop[3]) * sy));
        if (right <= x || bottom <= y) {
            return null;
        }
        return new int[] {x, y, right - x, bottom - y};
    }

}
