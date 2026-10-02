package com.micatechnologies.minecraft.mcmcp.tools;

import java.awt.image.BufferedImage;

/**
 * Draws a top-down map from sampled columns: map colours, height shading, highlights, unloaded
 * hatching, a coordinate grid and labels.
 *
 * <p>No Minecraft class here, on purpose. The sampling has to touch the world and so runs on the
 * client thread; everything after it is arithmetic that can be wrong without anything failing, and
 * this is where it can be tested.
 *
 * <p>Shading follows vanilla maps, because that is the convention a reader already knows: a column
 * higher than the one north of it is drawn at full brightness, level at 86%, lower at 71%. Water is
 * shaded by depth instead, so a river reads as a river rather than as stripes of its bed.
 *
 * <p>Labels use a built-in 3x5 digit font rather than {@code java.awt.Font}. AWT text rendering
 * initialises the font system, which inside an LWJGL 2 game on macOS is a known way to hang the
 * process, and a map is not worth that risk.
 */
public final class MapImage {

    /** A column whose chunk the client does not hold. */
    public static final int UNLOADED = -2;
    /** A column with nothing at or below the height cut. */
    public static final int EMPTY = -1;

    public static final int HIGHLIGHT_RGB = 0xFF00FF;
    private static final int HATCH_LIGHT = 0x505050;
    private static final int HATCH_DARK = 0x2A2A2A;
    private static final int EMPTY_RGB = 0x000000;
    private static final int PLAYER_RGB = 0xFF2020;
    private static final int LABEL_SCALE = 2;

    /** 3x5 glyphs for 0-9 and '-', one row per int, three low bits per row, leftmost first. */
    private static final int[][] GLYPHS = {
        {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
        {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 1, 1, 1}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7},
        {0, 0, 7, 0, 0},
    };

    private MapImage() {
    }

    /**
     * The sampled columns of one map, row-major from the north-west corner.
     *
     * <p>{@code rgb} is the top block's map colour, {@code heights} its y or {@link #UNLOADED} /
     * {@link #EMPTY}, {@code waterDepth} how deep the water there is (0 for dry land), and
     * {@code highlighted} whether a highlight pattern matched.
     */
    public static final class Columns {

        final int width;
        final int height;
        final int[] rgb;
        final int[] heights;
        final int[] waterDepth;
        final boolean[] highlighted;

        public Columns(int width, int height) {
            this.width = width;
            this.height = height;
            int size = width * height;
            this.rgb = new int[size];
            this.heights = new int[size];
            this.waterDepth = new int[size];
            this.highlighted = new boolean[size];
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public void set(int px, int pz, int rgb, int y, int waterDepth, boolean highlighted) {
            int index = pz * width + px;
            this.rgb[index] = rgb;
            this.heights[index] = y;
            this.waterDepth[index] = waterDepth;
            this.highlighted[index] = highlighted;
        }

        public void setUnloaded(int px, int pz) {
            heights[pz * width + px] = UNLOADED;
        }

        public void setEmpty(int px, int pz) {
            heights[pz * width + px] = EMPTY;
        }
    }

    /**
     * Draws the map. Where it sits in the world is needed for the grid and the player marker.
     *
     * @param minX          block X of the map's west edge
     * @param minZ          block Z of the map's north edge
     * @param scale         blocks per sampled column
     * @param pixelsPerCell pixels each sampled column is drawn as. Above 1 for a small area: a
     *                      129-pixel map is too small to read and leaves no room for its labels
     * @param grid          blocks between grid lines; 0 for none
     */
    public static BufferedImage render(Columns columns, int minX, int minZ, int scale, int pixelsPerCell,
                                       int grid, Integer playerX, Integer playerZ) {
        int cell = Math.max(1, pixelsPerCell);
        BufferedImage image = new BufferedImage(columns.width * cell, columns.height * cell,
            BufferedImage.TYPE_INT_RGB);
        for (int pz = 0; pz < columns.height; pz++) {
            for (int px = 0; px < columns.width; px++) {
                int rgb = columnColour(columns, px, pz);
                for (int dy = 0; dy < cell; dy++) {
                    for (int dx = 0; dx < cell; dx++) {
                        image.setRGB(px * cell + dx, pz * cell + dy, rgb);
                    }
                }
            }
        }
        if (grid > 0) {
            drawGrid(image, minX, minZ, scale, cell, grid);
        }
        if (playerX != null && playerZ != null) {
            drawPlayer(image, toPixel(playerX, minX, scale, cell) + cell / 2,
                toPixel(playerZ, minZ, scale, cell) + cell / 2, cell);
        }
        return image;
    }

    /** The image pixel at which block coordinate {@code block} starts. */
    static int toPixel(int block, int min, int scale, int cell) {
        return (block - min) / scale * cell;
    }

    static int columnColour(Columns columns, int px, int pz) {
        int index = pz * columns.width + px;
        int y = columns.heights[index];
        if (y == UNLOADED) {
            return ((px + pz) % 6) < 2 ? HATCH_LIGHT : HATCH_DARK;
        }
        if (y == EMPTY) {
            return EMPTY_RGB;
        }
        if (columns.highlighted[index]) {
            return HIGHLIGHT_RGB;
        }
        int depth = columns.waterDepth[index];
        double shade;
        if (depth > 0) {
            shade = depth <= 2 ? 1.0D : depth <= 4 ? 0.86D : depth <= 9 ? 0.71D : 0.53D;
        }
        else {
            int north = pz == 0 ? y : columns.heights[index - columns.width];
            if (north < 0) {
                north = y;
            }
            shade = y > north ? 1.0D : y == north ? 0.86D : 0.71D;
        }
        return shaded(columns.rgb[index], shade);
    }

    static int shaded(int rgb, double factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    /** Blends towards white by half, so a line is visible on any colour without hiding it. */
    private static int lightened(int rgb) {
        int r = (((rgb >> 16) & 0xFF) + 255) / 2;
        int g = (((rgb >> 8) & 0xFF) + 255) / 2;
        int b = ((rgb & 0xFF) + 255) / 2;
        return (r << 16) | (g << 8) | b;
    }

    /** The first block coordinate at or after {@code from} that is a multiple of {@code grid}. */
    static int firstMultiple(int from, int grid) {
        return Math.floorDiv(from + grid - 1, grid) * grid;
    }

    private static void drawGrid(BufferedImage image, int minX, int minZ, int scale, int cell, int grid) {
        int width = image.getWidth();
        int height = image.getHeight();
        // Lines first, then labels, so no line is drawn through a label.
        for (int x = firstMultiple(minX, grid); toPixel(x, minX, scale, cell) < width; x += grid) {
            int px = toPixel(x, minX, scale, cell);
            for (int pz = 0; pz < height; pz++) {
                image.setRGB(px, pz, lightened(image.getRGB(px, pz)));
            }
        }
        for (int z = firstMultiple(minZ, grid); toPixel(z, minZ, scale, cell) < height; z += grid) {
            int pz = toPixel(z, minZ, scale, cell);
            for (int px = 0; px < width; px++) {
                image.setRGB(px, pz, lightened(image.getRGB(px, pz)));
            }
        }
        // A label that would run into the one before it is skipped rather than overprinted: an
        // unreadable smear of digits is worse than every other coordinate.
        int nextFreeX = 0;
        for (int x = firstMultiple(minX, grid); toPixel(x, minX, scale, cell) < width; x += grid) {
            int px = toPixel(x, minX, scale, cell) + 2;
            if (px >= nextFreeX) {
                nextFreeX = px + labelWidth(x) + LABEL_SCALE * 2;
                drawNumber(image, x, px, 2);
            }
        }
        int labelHeight = 7 * LABEL_SCALE;
        int nextFreeZ = labelHeight + 2;
        for (int z = firstMultiple(minZ, grid); toPixel(z, minZ, scale, cell) < height; z += grid) {
            int pz = toPixel(z, minZ, scale, cell) + 2;
            if (pz >= nextFreeZ) {
                nextFreeZ = pz + labelHeight + LABEL_SCALE;
                drawNumber(image, z, 2, pz);
            }
        }
    }

    /** Width in pixels of the label box for {@code value}. */
    static int labelWidth(int value) {
        return Integer.toString(value).length() * 4 * LABEL_SCALE + LABEL_SCALE;
    }

    /** A cross sized to the zoom: a 7-pixel cross is lost on a map drawn 8 pixels to a block. */
    private static void drawPlayer(BufferedImage image, int px, int pz, int cell) {
        int arm = Math.max(3, cell * 2);
        int half = Math.max(0, cell / 4);
        for (int d = -arm; d <= arm; d++) {
            for (int t = -half; t <= half; t++) {
                plot(image, px + d, pz + t, PLAYER_RGB);
                plot(image, px + t, pz + d, PLAYER_RGB);
            }
        }
    }

    /** Draws {@code value} with its top-left at (left, top), on a dark box so it reads on any colour. */
    static void drawNumber(BufferedImage image, int value, int left, int top) {
        String text = Integer.toString(value);
        int glyphWidth = 3 * LABEL_SCALE;
        int advance = glyphWidth + LABEL_SCALE;
        int boxWidth = labelWidth(value);
        int boxHeight = 5 * LABEL_SCALE + 2 * LABEL_SCALE;
        for (int dy = 0; dy < boxHeight; dy++) {
            for (int dx = 0; dx < boxWidth; dx++) {
                plot(image, left + dx, top + dy, 0x000000);
            }
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int[] glyph = GLYPHS[c == '-' ? 10 : c - '0'];
            int originX = left + LABEL_SCALE + i * advance;
            int originY = top + LABEL_SCALE;
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 3; column++) {
                    if ((glyph[row] & (4 >> column)) == 0) {
                        continue;
                    }
                    for (int sy = 0; sy < LABEL_SCALE; sy++) {
                        for (int sx = 0; sx < LABEL_SCALE; sx++) {
                            plot(image, originX + column * LABEL_SCALE + sx, originY + row * LABEL_SCALE + sy,
                                0xFFFFFF);
                        }
                    }
                }
            }
        }
    }

    private static void plot(BufferedImage image, int px, int pz, int rgb) {
        if (px >= 0 && pz >= 0 && px < image.getWidth() && pz < image.getHeight()) {
            image.setRGB(px, pz, rgb);
        }
    }
}
