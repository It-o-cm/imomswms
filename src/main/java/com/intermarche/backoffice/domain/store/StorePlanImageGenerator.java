package com.intermarche.backoffice.domain.store;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Generates the store plan background image (PNG): a realistic supermarket
 * floor plan — rectangular building with walls, parallel gondola rows with
 * aisles between them, refrigerated wall units along the back wall, freezer
 * rows, checkout lanes and an entrance. It is deliberately NOT a rendering of
 * the circulation topology (nodes/aisles): the layout is a fixed, realistic
 * store layout. Only the department labels are derived from the data (the
 * distinct areas of the shelf locations).
 * <p>
 * Used by the demo data seeder to produce the plan background image without
 * any static file: the image is drawn in memory ({@link BufferedImage}) and
 * encoded as PNG. The frontend only loads this image and displays it.
 * <p>
 * The image is centered on the editor world origin so it is visible at the
 * default view.
 */
public final class StorePlanImageGenerator {

    /**
     * Building width, in meters.
     */
    public static final double BUILDING_W = 200;

    /**
     * Building depth, in meters (entrance at the front/bottom).
     */
    public static final double BUILDING_H = 120;

    /**
     * Rendering scale, in pixels per meter.
     */
    public static final double PX_PER_M = 8;

    /**
     * Margin around the building, in pixels.
     */
    public static final double PAD = 40;

    /**
     * Floor color (light warm gray): tints the whole image so it is visible
     * against the white canvas of the editor.
     */
    public static final Color FLOOR = new Color(0xf0, 0xed, 0xe6);

    // ----- Layout (meters, origin at the building top-left, y down) -----

    private static final double ENTRANCE_X0 = 92, ENTRANCE_X1 = 108;
    private static final double FRIGO_X0 = 8, FRIGO_X1 = 192, FRIGO_Y0 = 4, FRIGO_Y1 = 12;
    private static final double EPICERIE_X0 = 8, EPICERIE_X1 = 88;    // left gondola zone
    private static final double EPICERIE_X2 = 112, EPICERIE_X3 = 192; // right gondola zone
    private static final double[] EPICERIE_ROWS = {16, 28, 40, 52, 64};
    private static final double GONDOLA_D = 4;
    private static final double SURGELE_X0 = 8, SURGELE_X1 = 88, SURGELE_Y0 = 80, SURGELE_Y1 = 112;
    private static final double FREEZER_X0 = 12, FREEZER_X1 = 84;
    private static final double[] FREEZER_ROWS = {84, 94, 104};
    private static final double DROG_X0 = 112, DROG_X1 = 192, DROG_Y0 = 80, DROG_Y1 = 112;
    private static final double[] DROG_ROWS = {88, 100};
    private static final double CAISSE_Y0 = 112, CAISSE_Y1 = 120, CAISSE_W = 4;
    private static final double[] CAISSE_X = {56, 64, 72, 80, 112, 120, 128, 136};
    private static final double ACCUEIL_X0 = 152, ACCUEIL_X1 = 172, ACCUEIL_Y0 = 112, ACCUEIL_Y1 = 118;

    private static final Color WALL = new Color(0x3f, 0x47, 0x56);
    private static final Color FRAME = new Color(0x3f, 0x47, 0x56);
    private static final Color GONDOLA = new Color(0x64, 0x74, 0x8b);
    private static final Color GONDOLA_LINE = new Color(0x94, 0xa3, 0xb8);
    private static final Color FRIGO_FILL = new Color(0xdb, 0xea, 0xfe);
    private static final Color FRIGO_LINE = new Color(0x60, 0xa5, 0xfa);
    private static final Color CAISSE_FILL = new Color(0x94, 0xa3, 0xb8);
    private static final Color CAISSE_LINE = new Color(0x47, 0x55, 0x69);
    private static final Color ACCUEIL_FILL = new Color(0xe2, 0xe8, 0xf0);
    private static final Color DOOR = new Color(0xcb, 0xd5, 0xe1);
    private static final Color LABEL = new Color(0x1e, 0x29, 0x3b);
    private static final Color ENTRANCE = new Color(0x05, 0x96, 0x69);

    /**
     * Generation result: the encoded PNG, its origin in the plan world
     * (top-left corner) and its dimensions in pixels.
     *
     * @param png     the PNG image, encoded.
     * @param originX world X coordinate of the top-left corner.
     * @param originY world Y coordinate of the top-left corner.
     * @param width   image width in pixels.
     * @param height  image height in pixels.
     */
    public record Result(byte[] png, double originX, double originY, int width, int height) {
    }

    private StorePlanImageGenerator() {
    }

    /**
     * Generates the store plan background image.
     *
     * @param locations    the shelf locations of the building, used to derive
     *                     the department labels (distinct areas). May be null:
     *                     default department labels are used.
     * @param buildingCode the building code, drawn as the image title (may be null).
     * @return the generation result (PNG + world origin + dimensions).
     */
    public static Result generate(Collection<? extends ShelfLocation> locations, String buildingCode) {
        Set<String> areas = new LinkedHashSet<>();
        if (locations != null) {
            for (ShelfLocation loc : locations) {
                if (loc != null && loc.area != null && !loc.area.isBlank()) areas.add(loc.area);
            }
        }
        String epicerie = pick(areas, null, "EPICERIE");
        String frais = pick(areas, "FRAIS", "FRAIS");
        String surgeles = pick(areas, "SURGELE", "SURGELES");
        String droguerie = pick(areas, "DROGUERIE", "DROGUERIE");

        int w = (int) Math.round(BUILDING_W * PX_PER_M) + 2 * (int) PAD;
        int h = (int) Math.round(BUILDING_H * PX_PER_M) + 2 * (int) PAD;
        double ox = PAD, oy = PAD; // building origin in image coordinates

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            drawFloor(g, w, h);
            drawFrigos(g, ox, oy);
            drawGondolas(g, ox, oy, EPICERIE_X0, EPICERIE_X1, EPICERIE_ROWS);
            drawGondolas(g, ox, oy, EPICERIE_X2, EPICERIE_X3, EPICERIE_ROWS);
            drawFreezers(g, ox, oy);
            drawGondolas(g, ox, oy, DROG_X0, DROG_X1, DROG_ROWS);
            drawCaisses(g, ox, oy);
            drawAccueil(g, ox, oy);
            drawWalls(g, ox, oy);
            drawLabels(g, ox, oy, epicerie, frais, surgeles, droguerie);
            drawEntrance(g, ox, oy);
            drawFrame(g, w, h);
            drawTitle(g, buildingCode);
            drawScaleBar(g, w, h);
            drawNorth(g, w);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", baos);
        } catch (IOException e) {
            throw new IllegalStateException("PNG encoding failed", e);
        }
        // Center the image on the editor world origin
        return new Result(baos.toByteArray(), -w / 2.0, -h / 2.0, w, h);
    }

    // --------------------------------------------------
    // Drawing
    // --------------------------------------------------

    private static double mx(double ox, double x) {
        return ox + x * PX_PER_M;
    }

    private static double my(double oy, double y) {
        return oy + y * PX_PER_M;
    }

    private static void drawFloor(Graphics2D g, int w, int h) {
        g.setColor(FLOOR);
        g.fillRect(0, 0, w, h);
    }

    private static void drawRect(Graphics2D g, double ox, double oy,
                                 double x0, double y0, double x1, double y1,
                                 Color fill, Color line) {
        int ix = (int) Math.round(mx(ox, x0)), iy = (int) Math.round(my(oy, y0));
        int iw = (int) Math.round(mx(ox, x1)) - ix, ih = (int) Math.round(my(oy, y1)) - iy;
        if (fill != null) {
            g.setColor(fill);
            g.fillRect(ix, iy, iw, ih);
        }
        if (line != null) {
            g.setColor(line);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(ix, iy, iw, ih);
        }
    }

    /**
     * Refrigerated wall units along the back wall (fresh department).
     */
    private static void drawFrigos(Graphics2D g, double ox, double oy) {
        drawRect(g, ox, oy, FRIGO_X0, FRIGO_Y0, FRIGO_X1, FRIGO_Y1, FRIGO_FILL, FRIGO_LINE);
        g.setColor(FRIGO_LINE);
        g.setStroke(new BasicStroke(1f));
        for (double x = FRIGO_X0 + 12; x < FRIGO_X1; x += 12) {
            int ix = (int) Math.round(mx(ox, x));
            g.drawLine(ix, (int) Math.round(my(oy, FRIGO_Y0)), ix, (int) Math.round(my(oy, FRIGO_Y1)));
        }
    }

    /**
     * A block of gondola rows (double-sided racks): dark rectangles with a
     * centerline suggesting the two shelf faces, aisles (floor) between rows.
     */
    private static void drawGondolas(Graphics2D g, double ox, double oy,
                                     double x0, double x1, double[] rows) {
        for (double y : rows) {
            drawRect(g, ox, oy, x0, y, x1, y + GONDOLA_D, GONDOLA, null);
            g.setColor(GONDOLA_LINE);
            g.setStroke(new BasicStroke(1f));
            int iy = (int) Math.round(my(oy, y + GONDOLA_D / 2));
            g.drawLine((int) Math.round(mx(ox, x0)), iy, (int) Math.round(mx(ox, x1)), iy);
        }
    }

    /**
     * Freezer rows (frozen department): light blue cabinets in the corner.
     */
    private static void drawFreezers(Graphics2D g, double ox, double oy) {
        for (double y : FREEZER_ROWS) {
            drawRect(g, ox, oy, FREEZER_X0, y, FREEZER_X1, y + GONDOLA_D, FRIGO_FILL, FRIGO_LINE);
        }
    }

    /**
     * Checkout lanes: a row of small islands on both sides of the central
     * aisle, just inside the entrance.
     */
    private static void drawCaisses(Graphics2D g, double ox, double oy) {
        for (double x : CAISSE_X) {
            drawRect(g, ox, oy, x, CAISSE_Y0, x + CAISSE_W, CAISSE_Y1, CAISSE_FILL, CAISSE_LINE);
        }
    }

    /**
     * Customer service desk near the entrance.
     */
    private static void drawAccueil(Graphics2D g, double ox, double oy) {
        drawRect(g, ox, oy, ACCUEIL_X0, ACCUEIL_Y0, ACCUEIL_X1, ACCUEIL_Y1, ACCUEIL_FILL, CAISSE_LINE);
    }

    /**
     * Building walls: thick dark rectangle with a gap (and sliding doors) on
     * the front wall for the entrance.
     */
    private static void drawWalls(Graphics2D g, double ox, double oy) {
        g.setColor(WALL);
        g.setStroke(new BasicStroke(3f));
        double x0 = mx(ox, 0), y0 = my(oy, 0);
        double x1 = mx(ox, BUILDING_W), y1 = my(oy, BUILDING_H);
        double gx0 = mx(ox, ENTRANCE_X0), gx1 = mx(ox, ENTRANCE_X1);
        // Top, left, right walls
        g.drawLine((int) x0, (int) y0, (int) x1, (int) y0);
        g.drawLine((int) x0, (int) y0, (int) x0, (int) y1);
        g.drawLine((int) x1, (int) y0, (int) x1, (int) y1);
        // Front wall, with the entrance gap
        g.drawLine((int) x0, (int) y1, (int) gx0, (int) y1);
        g.drawLine((int) gx1, (int) y1, (int) x1, (int) y1);
        // Sliding door panels at the gap
        double mid = (gx0 + gx1) / 2;
        int doorY0 = (int) Math.round(my(oy, BUILDING_H - 0.6));
        int doorY1 = (int) Math.round(y1);
        g.setColor(DOOR);
        g.fillRect((int) gx0, doorY0, (int) Math.round(mid - gx0), doorY1 - doorY0);
        g.fillRect((int) Math.round(mid), doorY0, (int) Math.round(gx1 - mid), doorY1 - doorY0);
    }

    private static void drawLabels(Graphics2D g, double ox, double oy,
                                   String epicerie, String frais, String surgeles, String droguerie) {
        drawLabel(g, epicerie, mx(ox, 48), my(oy, 46));
        drawLabel(g, epicerie, mx(ox, 152), my(oy, 46));
        drawLabel(g, frais, mx(ox, 100), my(oy, 8));
        drawLabel(g, surgeles, mx(ox, 48), my(oy, 81));
        drawLabel(g, droguerie, mx(ox, 152), my(oy, 81));
        drawLabel(g, "CAISSES", mx(ox, 100), my(oy, 116));
    }

    /**
     * Department label: dark bold text on a white pill, readable over both
     * the floor and the racks.
     */
    private static void drawLabel(Graphics2D g, String text, double cx, double cy) {
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        int th = fm.getHeight();
        int px = (int) Math.round(cx - tw / 2.0 - 6);
        int py = (int) Math.round(cy - th / 2.0 - 2);
        int pw = tw + 12, ph = th + 4;
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect(px, py, pw, ph, 6, 6);
        g.setColor(new Color(0, 0, 0, 25));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(px, py, pw, ph, 6, 6);
        g.setColor(LABEL);
        g.drawString(text, (int) Math.round(cx - tw / 2.0), (int) Math.round(cy + th / 2.0 - 3));
    }

    /**
     * Entrance: label and arrow outside the front wall, pointing into the
     * building.
     */
    private static void drawEntrance(Graphics2D g, double ox, double oy) {
        double cx = mx(ox, (ENTRANCE_X0 + ENTRANCE_X1) / 2);
        double wallY = my(oy, BUILDING_H);
        g.setColor(ENTRANCE);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        String label = "Entrée";
        int tw = fm.stringWidth(label);
        g.drawString(label, (float) (cx - tw / 2.0), (float) (wallY + 30));
        g.setStroke(new BasicStroke(2f));
        g.drawLine((int) cx, (int) (wallY + 16), (int) cx, (int) (wallY + 4));
        g.drawLine((int) cx - 4, (int) (wallY + 10), (int) cx, (int) (wallY + 4));
        g.drawLine((int) cx + 4, (int) (wallY + 10), (int) cx, (int) (wallY + 4));
    }

    private static void drawFrame(Graphics2D g, int w, int h) {
        g.setColor(FRAME);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(1, 1, w - 2, h - 2);
    }

    private static void drawTitle(Graphics2D g, String buildingCode) {
        String title = (buildingCode == null || buildingCode.isBlank())
                ? "PLAN MAGASIN" : "PLAN MAGASIN - " + buildingCode;
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(FRAME);
        g.drawString(title, 12, 20);
    }

    private static void drawScaleBar(Graphics2D g, int w, int h) {
        int barPx = (int) Math.round(10 * PX_PER_M); // 10 m
        int x = w - 24 - barPx, y = h - 14;
        g.setColor(FRAME);
        g.fillRect(x, y, barPx, 3);
        g.fillRect(x, y - 4, 1, 11);
        g.fillRect(x + barPx - 1, y - 4, 1, 11);
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        FontMetrics fm = g.getFontMetrics();
        String label = "10 m";
        g.drawString(label, x + barPx / 2 - fm.stringWidth(label) / 2, y - 8);
    }

    private static void drawNorth(Graphics2D g, int w) {
        int x = w - 24, y = 24;
        g.setColor(FRAME);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("N", x - 3, y - 8);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x, y + 8, x, y - 2);
        g.drawLine(x - 4, y + 2, x, y - 2);
        g.drawLine(x + 4, y + 2, x, y - 2);
    }

    // --------------------------------------------------
    // Departments
    // --------------------------------------------------

    /**
     * Picks a department label from the areas. When {@code contains} is null,
     * picks the "main" department (the one that is not frais/surgelés/droguerie).
     *
     * @param areas    the distinct areas found in the shelf locations.
     * @param contains the substring to match (uppercase), or null for the main department.
     * @param fallback the label to use when no area matches.
     * @return the picked label.
     */
    private static String pick(Set<String> areas, String contains, String fallback) {
        for (String a : areas) {
            String u = a.toUpperCase();
            if (contains == null) {
                if (!u.contains("FRAIS") && !u.contains("SURGELE") && !u.contains("DROGUERIE")) return a;
            } else if (u.contains(contains)) {
                return a;
            }
        }
        return fallback;
    }
}
