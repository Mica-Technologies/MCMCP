package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** The map's colouring, shading and overlays, which can be wrong without anything failing. */
class MapImageTest {

    private static final int GRASS = 0x7FB238;

    @Test
    void aColumnHigherThanTheOneNorthOfItIsDrawnBrighterThanALevelOne() {
        MapImage.Columns columns = new MapImage.Columns(1, 3);
        columns.set(0, 0, GRASS, 64, 0, false);
        columns.set(0, 1, GRASS, 65, 0, false);
        columns.set(0, 2, GRASS, 65, 0, false);

        assertEquals(GRASS, MapImage.columnColour(columns, 0, 1), "higher: full brightness");
        assertEquals(MapImage.shaded(GRASS, 0.86D), MapImage.columnColour(columns, 0, 2), "level");
    }

    @Test
    void aColumnLowerThanTheOneNorthOfItIsDrawnDarkest() {
        MapImage.Columns columns = new MapImage.Columns(1, 2);
        columns.set(0, 0, GRASS, 70, 0, false);
        columns.set(0, 1, GRASS, 60, 0, false);
        assertEquals(MapImage.shaded(GRASS, 0.71D), MapImage.columnColour(columns, 0, 1));
    }

    @Test
    void waterIsShadedByDepthRatherThanByItsNeighbour() {
        MapImage.Columns columns = new MapImage.Columns(2, 1);
        columns.set(0, 0, 0x4040FF, 62, 1, false);
        columns.set(1, 0, 0x4040FF, 62, 12, false);
        assertNotEquals(MapImage.columnColour(columns, 0, 0), MapImage.columnColour(columns, 1, 0),
            "deep water must read darker than shallow");
    }

    @Test
    void highlightedColumnsAreMagentaWhateverTheirShading() {
        MapImage.Columns columns = new MapImage.Columns(1, 2);
        columns.set(0, 0, GRASS, 90, 0, false);
        columns.set(0, 1, MapImage.HIGHLIGHT_RGB, 64, 0, true);
        assertEquals(MapImage.HIGHLIGHT_RGB, MapImage.columnColour(columns, 0, 1));
    }

    @Test
    void anUnloadedColumnIsHatchedAndNeverLooksLikeTerrainOrVoid() {
        MapImage.Columns columns = new MapImage.Columns(6, 1);
        for (int px = 0; px < 6; px++) {
            columns.setUnloaded(px, 0);
        }
        int first = MapImage.columnColour(columns, 0, 0);
        int third = MapImage.columnColour(columns, 3, 0);
        assertNotEquals(first, third, "a hatch alternates");
        assertNotEquals(0x000000, first, "unloaded must not look like an empty column");
    }

    @Test
    void anUnloadedColumnToTheNorthDoesNotShadeTheColumnBelowIt() {
        MapImage.Columns columns = new MapImage.Columns(1, 2);
        columns.setUnloaded(0, 0);
        columns.set(0, 1, GRASS, 64, 0, false);
        assertEquals(MapImage.shaded(GRASS, 0.86D), MapImage.columnColour(columns, 0, 1));
    }

    @Test
    void gridLinesFallOnMultiplesOfTheSpacingIncludingNegativeCoordinates() {
        assertEquals(-96, MapImage.firstMultiple(-100, 32));
        assertEquals(0, MapImage.firstMultiple(-31, 32));
        assertEquals(128, MapImage.firstMultiple(100, 64));
        assertEquals(64, MapImage.firstMultiple(64, 64));
    }

    @Test
    void aSmallAreaIsDrawnSeveralPixelsToABlock() {
        MapImage.Columns columns = new MapImage.Columns(10, 5);
        for (int pz = 0; pz < 5; pz++) {
            for (int px = 0; px < 10; px++) {
                columns.set(px, pz, px == 3 ? 0xFF0000 : GRASS, 64, 0, false);
            }
        }
        BufferedImage image = MapImage.render(columns, 0, 0, 1, 4, 0, null, null);
        assertEquals(40, image.getWidth());
        assertEquals(20, image.getHeight());
        // Block x=3 fills pixels 12-15, and only those.
        assertEquals(image.getRGB(12, 0), image.getRGB(15, 3));
        assertNotEquals(image.getRGB(11, 0), image.getRGB(12, 0));
        assertNotEquals(image.getRGB(15, 0), image.getRGB(16, 0));
    }

    @Test
    void gridLabelsThatWouldOverlapAreSkippedRatherThanOverprinted() {
        // Lines every 16 pixels, four-digit labels about 34 pixels wide: only every third fits.
        MapImage.Columns columns = new MapImage.Columns(129, 40);
        for (int pz = 0; pz < 40; pz++) {
            for (int px = 0; px < 129; px++) {
                columns.set(px, pz, GRASS, 64, 0, false);
            }
        }
        BufferedImage image = MapImage.render(columns, 1000, 0, 1, 1, 16, null, null);
        int labelWidth = MapImage.labelWidth(1008);
        // The first label's box starts at x=2 past its line; the next drawn one must clear it.
        int firstLabelEnd = MapImage.toPixel(1008, 1000, 1, 1) + 2 + labelWidth;
        int secondLine = MapImage.toPixel(1024, 1000, 1, 1);
        assertTrue(secondLine + 2 < firstLabelEnd, "the second line's label would collide");
        // Pixel 50 of the label row is past the first label's box and before the fourth line's, so
        // only a skipped label (the second at 26, or the third at 42) could have covered it.
        assertEquals(MapImage.shaded(GRASS, 0.86D), image.getRGB(50, 2) & 0xFFFFFF);
    }

    @Test
    void aRenderedMapHasOnePixelPerSampledColumnAndTheGridDrawsOverIt() {
        MapImage.Columns columns = new MapImage.Columns(40, 30);
        for (int pz = 0; pz < 30; pz++) {
            for (int px = 0; px < 40; px++) {
                columns.set(px, pz, GRASS, 64, 0, false);
            }
        }
        BufferedImage plain = MapImage.render(columns, -20, -15, 1, 1, 0, null, null);
        BufferedImage gridded = MapImage.render(columns, -20, -15, 1, 1, 16, 0, 0);
        assertEquals(40, plain.getWidth());
        assertEquals(30, plain.getHeight());
        // x = 0 is pixel 20; below the label boxes, the line is lighter than the terrain beside it.
        assertNotEquals(plain.getRGB(20, 25) & 0xFFFFFF, gridded.getRGB(20, 25) & 0xFFFFFF);
        assertEquals(plain.getRGB(25, 25), gridded.getRGB(25, 25));
    }
}
