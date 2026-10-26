package com.intermarche.backoffice.domain.store;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StorePlanImageGenerator}.
 * The generator draws a fixed, realistic supermarket floor plan (it does NOT
 * render the circulation topology): tinted floor, walls with an entrance gap,
 * gondola rows, refrigerated wall units, freezer rows, checkout lanes, a
 * service desk, department labels (derived from the shelf location areas),
 * a frame, a title, a scale bar and a north arrow.
 * <p>
 * Layout reference (image pixels, building origin at (40, 40), 8 px/m,
 * building 200 m x 120 m, image 1680 x 1040, world origin (-840, -520)):
 * walls at x = 40 / 1640, y = 40 / 1000; entrance gap on the bottom wall at
 * x in [776, 904]; frigos x [104, 1576] y [72, 136]; épicerie gondolas
 * x [104, 744] and x [936, 1576], rows y [168, 200], [264, 296], [360, 392],
 * [456, 488], [552, 584]; freezers x [136, 712], rows y [712, 744],
 * [792, 824], [872, 904]; droguerie gondolas x [936, 1576], rows y [744, 776],
 * [840, 872]; caisses y [936, 1000] at x = 488, 552, 616, 680, 936, 1000,
 * 1064, 1128; accueil x [1256, 1416] y [936, 984].
 */
public class StorePlanImageGeneratorTest {

    private static final int W = 1680;
    private static final int H = 1040;

    private StoreShelfLocation loc(String area) {
        StoreShelfLocation l = new StoreShelfLocation();
        l.area = area;
        return l;
    }

    private BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    private boolean isDark(BufferedImage img, int x, int y) {
        int rgb = rgb(img, x, y);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return r + g + b < 384;
    }

    private int floorRgb() {
        return StorePlanImageGenerator.FLOOR.getRGB() & 0xFFFFFF;
    }

    private int countNonFloor(BufferedImage img) {
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if (rgb(img, x, y) != floorRgb()) count++;
        return count;
    }

    /**
     * Counts near-white pixels (the label pills) in a region.
     */
    private int countNearWhite(BufferedImage img, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int rgb = rgb(img, x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r >= 248 && g >= 248 && b >= 244) count++;
            }
        return count;
    }

    private int countDarkIn(BufferedImage img, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                if (isDark(img, x, y)) count++;
        return count;
    }

    // --------------------------------------------------
    // Result shape
    // --------------------------------------------------

    @Test
    void generate_shouldProduceValidPng() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), "LYO-042-SHOP");

        assertNotNull(r);
        // PNG magic number
        assertEquals((byte) 0x89, r.png()[0]);
        assertEquals((byte) 'P', r.png()[1]);
        assertEquals((byte) 'N', r.png()[2]);
        assertEquals((byte) 'G', r.png()[3]);
        assertNotNull(decode(r.png()));
    }

    @Test
    void generate_shouldHaveFixedDimensions() {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);

        // 200 m x 120 m at 8 px/m + 40 px padding on each side
        assertEquals(W, r.width());
        assertEquals(H, r.height());
    }

    @Test
    void generate_shouldCenterImageOnWorldOrigin() {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);

        assertEquals(-W / 2.0, r.originX(), 0.0001);
        assertEquals(-H / 2.0, r.originY(), 0.0001);
    }

    @Test
    void generate_shouldNeverReturnNull() {
        assertNotNull(StorePlanImageGenerator.generate(null, null));
        assertNotNull(StorePlanImageGenerator.generate(List.of(), null));
        assertNotNull(StorePlanImageGenerator.generate(List.of(), ""));
        assertNotNull(StorePlanImageGenerator.generate(List.of(loc("EPICERIE")), "X"));
    }

    // --------------------------------------------------
    // Floor, walls, entrance
    // --------------------------------------------------

    @Test
    void generate_shouldTintTheFloor() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        // Padding area and the central aisle between gondola blocks: tinted floor, not white
        assertEquals(floorRgb(), rgb(img, 5, 5));
        assertNotEquals(0xFFFFFF, rgb(img, 5, 5));
        assertEquals(floorRgb(), rgb(img, 880, 680));
    }

    @Test
    void generate_shouldDrawDarkWallsAndFrame() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        // Top, left, right walls
        assertTrue(isDark(img, 880, 40), "Top wall should be dark");
        assertTrue(isDark(img, 40, 560), "Left wall should be dark");
        assertTrue(isDark(img, 1640, 560), "Right wall should be dark");
        // Frame
        assertTrue(isDark(img, 1, 1), "Frame should be dark");
        assertTrue(isDark(img, W - 2, H - 2), "Frame should be dark");
    }

    @Test
    void generate_shouldDrawEntranceGapWithDoors() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        // Inside the gap (bottom wall, x in [776, 904]): floor, no wall
        assertEquals(floorRgb(), rgb(img, 840, 1000),
                "Expected a door gap (floor) in the bottom wall at the entrance");
        // Outside the gap: the wall is dark
        assertTrue(isDark(img, 760, 1000), "Expected the wall to be dark outside the gap");
        assertTrue(isDark(img, 960, 1000), "Expected the wall to be dark outside the gap");
        // Sliding door panels just inside the gap
        int door = new java.awt.Color(0xcb, 0xd5, 0xe1).getRGB() & 0xFFFFFF;
        assertEquals(door, rgb(img, 850, 997), "Expected a sliding door panel at the entrance");
    }

    // --------------------------------------------------
    // Fixtures
    // --------------------------------------------------

    @Test
    void generate_shouldDrawRefrigeratedWallUnits() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        int frigo = new java.awt.Color(0xdb, 0xea, 0xfe).getRGB() & 0xFFFFFF;
        assertEquals(frigo, rgb(img, 800, 100), "Frigo wall unit fill");
        assertEquals(frigo, rgb(img, 210, 120), "Frigo wall unit fill");
    }

    @Test
    void generate_shouldDrawGondolaRows() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        int gondola = new java.awt.Color(0x64, 0x74, 0x8b).getRGB() & 0xFFFFFF;
        // Épicerie, left block, first row
        assertEquals(gondola, rgb(img, 400, 176));
        // Épicerie, right block, first row
        assertEquals(gondola, rgb(img, 1200, 176));
        // Épicerie, left block, last row (centerline is at y = 568)
        assertEquals(gondola, rgb(img, 400, 560));
        // Droguerie, right block
        assertEquals(gondola, rgb(img, 1200, 752));
    }

    @Test
    void generate_shouldDrawFreezerRows() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        int freezer = new java.awt.Color(0xdb, 0xea, 0xfe).getRGB() & 0xFFFFFF;
        assertEquals(freezer, rgb(img, 400, 720), "Freezer row 1");
        assertEquals(freezer, rgb(img, 400, 800), "Freezer row 2");
        assertEquals(freezer, rgb(img, 400, 880), "Freezer row 3");
    }

    @Test
    void generate_shouldDrawCheckoutLanesAndServiceDesk() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        int caisse = new java.awt.Color(0x94, 0xa3, 0xb8).getRGB() & 0xFFFFFF;
        assertEquals(caisse, rgb(img, 500, 968), "Checkout lane (left bank)");
        assertEquals(caisse, rgb(img, 1140, 968), "Checkout lane (right bank)");

        int accueil = new java.awt.Color(0xe2, 0xe8, 0xf0).getRGB() & 0xFFFFFF;
        assertEquals(accueil, rgb(img, 1336, 960), "Service desk");
    }

    @Test
    void generate_shouldNotBeBlank() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        assertTrue(countNonFloor(decode(r.png())) > 200_000,
                "Image should contain a substantial amount of drawn elements");
    }

    // --------------------------------------------------
    // Labels, title, scale bar, north arrow
    // --------------------------------------------------

    @Test
    void generate_shouldDrawDepartmentLabels() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        // White pills at the label positions (épicerie x2, frais, surgelés,
        // droguerie, caisses)
        assertTrue(countNearWhite(img, 384, 396, 464, 420) > 200, "Épicerie label (left)");
        assertTrue(countNearWhite(img, 1216, 396, 1296, 420) > 200, "Épicerie label (right)");
        assertTrue(countNearWhite(img, 800, 92, 880, 116) > 200, "Frais label");
        assertTrue(countNearWhite(img, 384, 676, 464, 700) > 200, "Surgelés label");
        assertTrue(countNearWhite(img, 1216, 676, 1296, 700) > 200, "Droguerie label");
        assertTrue(countNearWhite(img, 800, 956, 880, 980) > 200, "Caisses label");
    }

    @Test
    void generate_shouldDrawTitleScaleBarAndNorthArrow() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), "LYO-042-SHOP");
        BufferedImage img = decode(r.png());

        // Title, top-left
        assertTrue(countDarkIn(img, 8, 8, 200, 26) > 20, "Title should be drawn top-left");
        // Scale bar, bottom-right (10 m = 80 px bar at y ~ 1026)
        assertTrue(countDarkIn(img, 1560, 1010, 1670, 1040) > 20, "Scale bar should be drawn bottom-right");
        // North arrow, top-right
        assertTrue(countDarkIn(img, 1640, 8, 1672, 40) > 10, "North arrow should be drawn top-right");
    }

    // --------------------------------------------------
    // Departments from the shelf locations
    // --------------------------------------------------

    @Test
    void generate_shouldUseAreasFromLocationsForLabels() throws Exception {
        StorePlanImageGenerator.Result def = StorePlanImageGenerator.generate(List.of(), null);
        StorePlanImageGenerator.Result custom = StorePlanImageGenerator.generate(
                List.of(loc("BOULANGERIE"), loc("FRAIS"), loc("SURGELES"), loc("DROGUERIE")), null);

        BufferedImage defImg = decode(def.png());
        BufferedImage customImg = decode(custom.png());

        // The main department label (épicerie position) changes with the areas:
        // "EPICERIE" (default) vs "BOULANGERIE" (from the locations)
        boolean differs = false;
        for (int y = 396; y < 420 && !differs; y++)
            for (int x = 384; x < 464 && !differs; x++)
                if (rgb(defImg, x, y) != rgb(customImg, x, y)) differs = true;
        assertTrue(differs, "Expected the main department label to reflect the location areas");

        // The custom image still draws all the labels
        assertTrue(countNearWhite(customImg, 384, 396, 464, 420) > 200, "Custom main department label");
        assertTrue(countNearWhite(customImg, 800, 92, 880, 116) > 200, "Frais label");
    }

    @Test
    void generate_shouldFallBackToDefaultLabelsWhenNoAreaMatches() throws Exception {
        // Only "specialized" areas: the main department falls back to EPICERIE
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(
                List.of(loc("FRAIS"), loc("SURGELES"), loc("DROGUERIE")), null);
        BufferedImage img = decode(r.png());

        assertTrue(countNearWhite(img, 384, 396, 464, 420) > 200, "Default épicerie label");
        assertTrue(countNearWhite(img, 800, 92, 880, 116) > 200, "Frais label");
        assertTrue(countNearWhite(img, 384, 676, 464, 700) > 200, "Surgelés label");
        assertTrue(countNearWhite(img, 1216, 676, 1296, 700) > 200, "Droguerie label");
    }

    @Test
    void generate_shouldMatchAreasCaseInsensitively() throws Exception {
        // Lowercase areas must still be recognized
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(
                List.of(loc("epicerie salee"), loc("frais"), loc("surgeles"), loc("droguerie")), null);
        BufferedImage img = decode(r.png());

        assertTrue(countNearWhite(img, 384, 396, 464, 420) > 200, "Main department label");
        assertTrue(countNearWhite(img, 800, 92, 880, 116) > 200, "Frais label");
    }

    // --------------------------------------------------
    // Robustness
    // --------------------------------------------------

    @Test
    void generate_shouldIgnoreNullAndBlankAreas() {
        List<StoreShelfLocation> locs = new ArrayList<>();
        locs.add(loc(null));
        locs.add(loc(""));
        locs.add(loc("   "));
        locs.add(null);
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(locs, null);

        assertNotNull(r);
        assertEquals(W, r.width());
        assertEquals(H, r.height());
    }

    @Test
    void generate_shouldHandleNullBuildingCode() throws Exception {
        StorePlanImageGenerator.Result r = StorePlanImageGenerator.generate(List.of(), null);
        BufferedImage img = decode(r.png());

        // Title without building code: still drawn
        assertTrue(countDarkIn(img, 8, 8, 200, 26) > 20, "Title should be drawn");
    }
}
