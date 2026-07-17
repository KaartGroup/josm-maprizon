// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.gui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.function.DoubleConsumer;

/**
 * A lightweight, dependency-free 360° panorama viewer: it reprojects an
 * <b>equirectangular</b> source image onto a rectilinear (pinhole) view that the
 * user can look around with click-drag (yaw/pitch) and zoom with the scroll wheel
 * (field of view). Pure {@code java.awt} raster math — no GL, no browser, no
 * external library — which is what lets it live inside a JOSM plugin jar.
 *
 * <p>Rendering: for each output pixel a camera ray is cast (rays are precomputed
 * per viewport size + FOV), rotated by the current yaw/pitch, converted to a
 * longitude/latitude on the sphere, and bilinearly sampled from the source
 * (wrapping horizontally, clamping at the poles). During an active drag it renders
 * at reduced resolution for smoothness, then re-renders full-resolution once the
 * drag settles.
 */
public final class PanoramaPanel extends JPanel {

    private int[] srcPix;
    private int srcW;
    private int srcH;

    private double yaw;         // degrees, 0 = source centre column
    private double pitch;       // degrees, + = look up, clamped
    private double vfovDeg = 75;

    private static final double PITCH_LIMIT = 85;
    private static final double VFOV_MIN = 30;
    private static final double VFOV_MAX = 110;

    private boolean dragging;
    private int lastX;
    private int lastY;

    /** Notified (on the EDT) with the current yaw in degrees whenever the view is
     * turned, so the on-map 360 cone can track where the user is looking. */
    private DoubleConsumer yawListener;

    /** Register the yaw callback (see {@link #yawListener}); fires immediately with
     * the current yaw so the listener starts in sync. */
    public void setYawListener(DoubleConsumer l) {
        this.yawListener = l;
        fireYaw();
    }

    private void fireYaw() {
        if (yawListener != null) {
            yawListener.accept(yaw);
        }
    }

    // Precomputed per-pixel camera rays for the current (rw, rh, vfov).
    private double[] rayX;
    private double[] rayY;
    private double[] rayZ;
    private int rayW = -1;
    private int rayH = -1;
    private double rayVfov = -1;

    public PanoramaPanel() {
        setBackground(Color.DARK_GRAY);
        setPreferredSize(new Dimension(320, 240));
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastX = e.getX();
                lastY = e.getY();
                dragging = true;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
                repaint(); // full-resolution re-render now the drag has settled
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                // Grab-and-drag: dragging right reveals the left of the scene (yaw
                // down); dragging down reveals the top (look up). Scale by FOV so a
                // zoomed-in view pans proportionally slower.
                double scale = vfovDeg / Math.max(1, getHeight());
                yaw = norm360(yaw - (e.getX() - lastX) * scale);
                pitch = clamp(pitch - (e.getY() - lastY) * scale, -PITCH_LIMIT, PITCH_LIMIT);
                lastX = e.getX();
                lastY = e.getY();
                fireYaw();
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    yaw = 0;
                    pitch = 0;
                    vfovDeg = 75;
                    fireYaw();
                    repaint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                vfovDeg = clamp(vfovDeg + e.getWheelRotation() * 5.0, VFOV_MIN, VFOV_MAX);
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    /** Set the equirectangular source image (view orientation is preserved across
     * frames so walking a sequence keeps looking the same way). */
    public void setPanorama(BufferedImage img) {
        if (img == null) {
            srcPix = null;
            srcW = srcH = 0;
        } else {
            srcW = img.getWidth();
            srcH = img.getHeight();
            srcPix = img.getRGB(0, 0, srcW, srcH, null, 0, srcW);
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (srcPix == null || srcW <= 0 || srcH <= 0) {
            paintHint((Graphics2D) g);
            return;
        }
        BufferedImage frame = render(dragging ? 2 : 1);
        if (frame != null) {
            g.drawImage(frame, 0, 0, getWidth(), getHeight(), null);
        }
        // Small affordance so users know it's interactive.
        paintBadge((Graphics2D) g);
    }

    /** Test hook (package-private): render one full-resolution frame for the given
     * viewport + orientation, without needing a realized Swing peer. */
    BufferedImage renderForTest(int w, int h, double yawDeg, double pitchDeg, double vfov) {
        setSize(w, h);
        this.yaw = yawDeg;
        this.pitch = pitchDeg;
        this.vfovDeg = vfov;
        return render(1);
    }

    private BufferedImage render(int scaleDiv) {
        int pw = getWidth();
        int ph = getHeight();
        if (pw <= 0 || ph <= 0) {
            return null;
        }
        int rw = Math.max(1, pw / scaleDiv);
        int rh = Math.max(1, ph / scaleDiv);
        ensureRayTable(rw, rh);

        double cy = Math.cos(Math.toRadians(yaw));
        double sy = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch));
        double sp = Math.sin(Math.toRadians(pitch));

        BufferedImage out = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
        int[] o = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        double twoPi = 2 * Math.PI;
        int n = rw * rh;
        for (int i = 0; i < n; i++) {
            double x = rayX[i];
            double y = rayY[i];
            double z = rayZ[i];
            // Rx(pitch)
            double y1 = y * cp - z * sp;
            double z1 = y * sp + z * cp;
            // Ry(yaw)
            double x2 = x * cy + z1 * sy;
            double z2 = -x * sy + z1 * cy;
            double y2 = y1;

            double r = Math.sqrt(x2 * x2 + y2 * y2 + z2 * z2);
            double lon = Math.atan2(x2, z2);      // 0 at forward (+z)
            double lat = Math.asin(y2 / r);
            double u = lon / twoPi + 0.5;          // [0,1)
            double v = 0.5 - lat / Math.PI;        // [0,1]
            o[i] = sampleBilinear(u * srcW - 0.5, v * srcH - 0.5);
        }
        return out;
    }

    private void ensureRayTable(int rw, int rh) {
        if (rw == rayW && rh == rayH && vfovDeg == rayVfov && rayX != null) {
            return;
        }
        int n = rw * rh;
        rayX = new double[n];
        rayY = new double[n];
        rayZ = new double[n];
        double f = (rh / 2.0) / Math.tan(Math.toRadians(vfovDeg) / 2.0);
        int i = 0;
        for (int py = 0; py < rh; py++) {
            double cyv = py - rh / 2.0 + 0.5;
            for (int px = 0; px < rw; px++) {
                rayX[i] = px - rw / 2.0 + 0.5;  // +x right
                rayY[i] = -cyv;                 // +y up (screen y is down)
                rayZ[i] = f;                    // +z forward
                i++;
            }
        }
        rayW = rw;
        rayH = rh;
        rayVfov = vfovDeg;
    }

    /** Bilinear sample of the source at (fx, fy) in source-pixel space, wrapping
     * horizontally (longitude is cyclic) and clamping vertically (poles). */
    private int sampleBilinear(double fx, double fy) {
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        double dx = fx - x0;
        double dy = fy - y0;
        int x0w = wrap(x0, srcW);
        int x1w = wrap(x0 + 1, srcW);
        int y0c = clampInt(y0, 0, srcH - 1);
        int y1c = clampInt(y0 + 1, 0, srcH - 1);
        int c00 = srcPix[y0c * srcW + x0w];
        int c10 = srcPix[y0c * srcW + x1w];
        int c01 = srcPix[y1c * srcW + x0w];
        int c11 = srcPix[y1c * srcW + x1w];
        int r = lerp2(c00 >> 16 & 0xFF, c10 >> 16 & 0xFF, c01 >> 16 & 0xFF, c11 >> 16 & 0xFF, dx, dy);
        int gg = lerp2(c00 >> 8 & 0xFF, c10 >> 8 & 0xFF, c01 >> 8 & 0xFF, c11 >> 8 & 0xFF, dx, dy);
        int b = lerp2(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, dx, dy);
        return (r << 16) | (gg << 8) | b;
    }

    private static int lerp2(int c00, int c10, int c01, int c11, double dx, double dy) {
        double top = c00 + (c10 - c00) * dx;
        double bot = c01 + (c11 - c01) * dx;
        int v = (int) Math.round(top + (bot - top) * dy);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int wrap(int v, int m) {
        int r = v % m;
        return r < 0 ? r + m : r;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double norm360(double d) {
        double x = d % 360.0;
        return x < 0 ? x + 360.0 : x;
    }

    private void paintHint(Graphics2D g) {
        g.setColor(Color.LIGHT_GRAY);
        String msg = "360° panorama";
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
    }

    private void paintBadge(Graphics2D g) {
        String msg = "360° · drag to look · scroll to zoom";
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(msg);
        int pad = 6;
        int x = 8;
        int y = getHeight() - fm.getHeight() - 8;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(x, y, w + 2 * pad, fm.getHeight() + pad, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString(msg, x + pad, y + fm.getAscent() + pad / 2);
    }
}
