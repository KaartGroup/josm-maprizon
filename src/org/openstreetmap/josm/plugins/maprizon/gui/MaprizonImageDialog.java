// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.gui;

import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.plugins.maprizon.FacingStyle;
import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.io.ViewerApiClient;
import org.openstreetmap.josm.plugins.maprizon.layer.MaprizonLayer;
import org.openstreetmap.josm.plugins.maprizon.oauth.ViewerAuth;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Docked side panel that shows the actual street-level image for a selected
 * Maprizon coverage point, and lets the user walk the sequence (prev/next,
 * arrow keys). Anonymous by default (public images fetched straight from their
 * public-read DO Spaces URL); when the user is logged in (see
 * {@link org.openstreetmap.josm.plugins.maprizon.oauth.ViewerAuth}), each image
 * is resolved through the viewer's signing endpoint so private imagery loads too.
 *
 * <p>360 (equirectangular) frames are shown in an interactive {@link PanoramaPanel}
 * (drag to look, scroll to zoom); everything else uses the flat {@link ImagePanel}.
 *
 * <p>A single instance is registered per map frame; {@link #getInstance()} lets
 * the coverage layer's click handler drive it.
 */
public final class MaprizonImageDialog extends ToggleDialog {

    private static volatile MaprizonImageDialog instance;

    /** Small LRU of decoded frames so walking back and forth is instant. */
    private static final int CACHE_MAX = 40;

    /** Max width a cached panorama is decoded to — memory-vs-detail knob (source
     * equirectangular 360 frames can be very large; 40 uncapped ones would blow the
     * heap). Panoramas wider than this are downscaled on load. */
    private static final int MAX_PANO_WIDTH = 4096;

    private final Map<String, BufferedImage> cache = new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > CACHE_MAX;
        }
    };

    private final ImagePanel imagePanel = new ImagePanel();
    private final PanoramaPanel panoPanel = new PanoramaPanel();
    /** Holds whichever viewer is active for the current frame (flat or panorama). */
    private final JPanel viewerHost = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JButton prevButton = new JButton("← Prev");
    private final JButton nextButton = new JButton("Next →");

    /** Background loads. A small pool (not a single thread) so one slow request
     * can't serialize/wedge every later click; newest request still wins visually
     * via loadToken. Combined with HTTP timeouts in ViewerApiClient/fetch, a hung
     * backend can no longer leave the dialog stuck on "Loading…". */
    private final ExecutorService exec = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "maprizon-image-loader");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong loadToken = new AtomicLong();

    private List<ImageryFeature> frames = Collections.emptyList();
    private int index;
    /** Layer that originated the current selection, so we can move its on-map
     * marker to the frame being shown as the user walks the sequence. */
    private MaprizonLayer originatingLayer;

    /** Clears the image cache + reloads the current frame when login state flips,
     * so private imagery loads right after login (and stops after logout). */
    private final Runnable authListener = this::onLoginStateChanged;

    public MaprizonImageDialog() {
        super("Maprizon Image",
                "maprizon",
                "View the selected Maprizon street-level image and walk its sequence",
                Shortcut.registerShortcut("maprizon:imageviewer",
                        "Maprizon: image viewer",
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                300);
        instance = this;

        JPanel root = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        root.add(status, BorderLayout.NORTH);
        viewerHost.add(imagePanel, BorderLayout.CENTER);
        root.add(viewerHost, BorderLayout.CENTER);

        JPanel nav = new JPanel(new BorderLayout());
        nav.add(prevButton, BorderLayout.WEST);
        nav.add(nextButton, BorderLayout.EAST);
        root.add(nav, BorderLayout.SOUTH);

        prevButton.addActionListener(e -> step(-1));
        nextButton.addActionListener(e -> step(1));
        prevButton.setEnabled(false);
        nextButton.setEnabled(false);

        // Left/Right arrows walk the sequence when the panel has focus.
        InputMap im = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = root.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "maprizon-prev");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "maprizon-next");
        am.put("maprizon-prev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                step(-1);
            }
        });
        am.put("maprizon-next", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                step(1);
            }
        });

        createLayout(root, false, Collections.emptyList());
        ViewerAuth.getInstance().addLoginStateListener(authListener);
    }

    public static MaprizonImageDialog getInstance() {
        return instance;
    }

    /** On login/logout: drop cached bytes and re-fetch the shown frame (now with
     * or without signing). Safe to call from any thread. */
    private void onLoginStateChanged() {
        synchronized (cache) {
            cache.clear();
        }
        SwingUtilities.invokeLater(() -> {
            if (!frames.isEmpty()) {
                display();
            }
        });
    }

    /**
     * Entry point from the coverage layer: given the clicked feature, resolve
     * its full sequence in the background and display it. Falls back to showing
     * just the clicked frame if the sequence lookup fails.
     */
    public void showForClickedFeature(ImageryFeature clicked, double[] clickLonLat, MaprizonLayer layer) {
        unfurlDialog();
        status.setText("Loading…");
        imagePanel.setImage(null);
        this.originatingLayer = layer;
        final long token = loadToken.incrementAndGet();
        exec.submit(() -> {
            try {
                ViewerApiClient.SequenceResult result = ViewerApiClient.fetchSequence(clicked);
                final List<ImageryFeature> seq;
                final int start;
                if (result != null) {
                    seq = result.frames;
                    // Open at the frame nearest the click so the image + on-map
                    // marker land where the user clicked (tile line features carry
                    // no per-frame index, so the API's clicked_index defaults to 0).
                    start = clickLonLat != null ? nearestIndex(seq, clickLonLat) : result.clickedIndex;
                } else {
                    seq = Collections.singletonList(clicked);
                    start = 0;
                }
                SwingUtilities.invokeLater(() -> {
                    if (token != loadToken.get()) {
                        return; // a newer click superseded this one
                    }
                    frames = seq;
                    index = start;
                    display();
                });
            } catch (Throwable t) {
                Logging.warn("Maprizon: sequence load failed: " + t);
                SwingUtilities.invokeLater(() -> status.setText("<html>Error loading sequence</html>"));
            }
        });
    }

    private static int nearestIndex(List<ImageryFeature> frames, double[] lonLat) {
        int best = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            List<double[]> pts = frames.get(i).getPoints();
            if (pts.isEmpty()) {
                continue;
            }
            double dLon = pts.get(0)[0] - lonLat[0];
            double dLat = pts.get(0)[1] - lonLat[1];
            double d = dLon * dLon + dLat * dLat;
            if (d < bestDistSq) {
                bestDistSq = d;
                best = i;
            }
        }
        return best;
    }

    /** Bearing (deg, clockwise from north) to orient the selected frame's view
     * cone: the baked per-image heading if present, else derived_heading, else the
     * GPS travel bearing between adjacent frames plus the camera's mount offset. */
    private static Double coneBearing(List<ImageryFeature> seq, int idx) {
        ImageryFeature f = seq.get(idx);
        Double h = parseDeg(f.getHeading());
        if (h != null) {
            return h;
        }
        h = parseDeg(f.getDerivedHeading());
        if (h != null) {
            return h;
        }
        Double travel = travelBearing(seq, idx);
        return travel == null ? null : norm360(travel + facingMountOffset(f.getFacing()));
    }

    private static Double parseDeg(String s) {
        if (s == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isNaN(d) ? null : norm360(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Initial great-circle bearing along travel, from the neighbouring frame (next
     * if available, else previous). Null if there is no usable neighbour. */
    private static Double travelBearing(List<ImageryFeature> seq, int idx) {
        int a = idx;
        int b = idx + 1;
        if (b >= seq.size()) {
            a = idx - 1;
            b = idx;
        }
        if (a < 0 || b >= seq.size() || a == b) {
            return null;
        }
        List<double[]> pa = seq.get(a).getPoints();
        List<double[]> pb = seq.get(b).getPoints();
        if (pa.isEmpty() || pb.isEmpty()) {
            return null;
        }
        double lat1 = Math.toRadians(pa.get(0)[1]);
        double lat2 = Math.toRadians(pb.get(0)[1]);
        double dLon = Math.toRadians(pb.get(0)[0] - pa.get(0)[0]);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return norm360(Math.toDegrees(Math.atan2(y, x)));
    }

    /** Camera mount angle relative to travel — ONLY used for the derived fallback;
     * the baked heading already encodes this. */
    private static double facingMountOffset(String facing) {
        if (FacingStyle.LEFT.equals(facing)) {
            return -90;
        }
        if (FacingStyle.RIGHT.equals(facing)) {
            return 90;
        }
        return 0; // front / still / 360
    }

    private static double norm360(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    /** Guards against a second crossing being kicked off while one is in flight —
     * arrow keys autorepeat, so hitting the end of a sequence could otherwise fire
     * a burst of identical requests. EDT only. */
    private boolean crossingSequence;

    private void step(int delta) {
        if (frames.isEmpty()) {
            return;
        }
        int next = index + delta;
        if (next < 0 || next >= frames.size()) {
            // End of THIS sequence — continue into the adjacent one rather than
            // dead-ending. A drive is split into many sequences, so stopping here
            // made following a long road a manual hunt: the user had to go back to
            // the map and click the next track by hand.
            crossToAdjacent(delta);
            return;
        }
        index = next;
        display();
    }

    /**
     * Load the next/previous sequence and continue walking into it.
     *
     * <p>Off-EDT because it is a network round trip; the web app has the same
     * delay. The button is disabled and the status says so, rather than the UI
     * appearing to ignore the click.
     *
     * <p>The boundary frame we hand the server is the one at the END we are leaving
     * (first frame when going backwards, last when going forwards) — that is the
     * position adjacency should be measured from.
     */
    private void crossToAdjacent(int delta) {
        if (crossingSequence || frames.isEmpty()) {
            return;
        }
        final boolean forward = delta > 0;
        final ImageryFeature boundary = frames.get(forward ? frames.size() - 1 : 0);
        final String direction = forward ? "next" : "previous";

        crossingSequence = true;
        prevButton.setEnabled(false);
        nextButton.setEnabled(false);
        status.setText("<html>Loading " + direction + " image set…</html>");

        final long token = loadToken.incrementAndGet();
        exec.submit(() -> {
            List<ImageryFeature> seq = ViewerApiClient.fetchAdjacentSequence(boundary, direction);
            SwingUtilities.invokeLater(() -> {
                crossingSequence = false;
                // A click elsewhere while we were fetching supersedes this result;
                // loadToken moving is how that is detected (same guard the image
                // loader uses).
                if (token != loadToken.get()) {
                    return;
                }
                if (seq == null || seq.isEmpty()) {
                    // Genuinely the end of the drive — say so instead of leaving a
                    // stale "Loading…" on screen.
                    //
                    // Re-enable the buttons directly rather than calling
                    // displayImpl(): that would immediately overwrite this message
                    // with the frame counter, so the explanation would flash and
                    // vanish. Nothing else needs refreshing — the displayed frame
                    // did not change.
                    prevButton.setEnabled(true);
                    nextButton.setEnabled(true);
                    status.setText("<html>No " + direction + " image set — end of coverage"
                            + "<br><i>pick another track on the map to continue</i></html>");
                    return;
                }
                frames = seq;
                // Enter the new sequence from the edge we arrived at, so the walk
                // continues in the same direction instead of jumping to its middle.
                index = forward ? 0 : seq.size() - 1;
                display();
            });
        });
    }

    private void display() {
        try {
            displayImpl();
        } catch (Throwable t) {
            Logging.warn("Maprizon: display failed: " + t);
            status.setText("<html>Error displaying image</html>");
        }
    }

    private void displayImpl() {
        if (frames.isEmpty()) {
            status.setText("No image selected");
            imagePanel.setImage(null);
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
            return;
        }
        ImageryFeature f = frames.get(index);
        boolean is360 = FacingStyle.FACING_360.equals(f.getFacing());
        // Move the on-map selection marker to the frame now being shown, and orient
        // its view cone by the frame's heading (ring for 360).
        if (originatingLayer != null) {
            originatingLayer.highlightFrame(f, coneBearing(frames, index), is360);
        }
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(f.getFacing()).append("  ·  ").append(index + 1).append(" / ").append(frames.size());
        if (f.getTimestamp() != null) {
            sb.append("<br>").append(f.getTimestamp());
        }
        sb.append("</html>");
        status.setText(sb.toString());
        // Enabled at the edges too, because the edges are no longer dead ends:
        // stepping past them crosses into the adjacent sequence. Whether one
        // EXISTS is only knowable by asking the server, so offering the control and
        // reporting "end of coverage" on a miss beats greying it out and leaving
        // the user to guess whether the drive continues.
        prevButton.setEnabled(!crossingSequence);
        nextButton.setEnabled(!crossingSequence);
        loadImage(f.getImg(), is360);
    }

    private void loadImage(String url, boolean is360) {
        if (url == null) {
            showLoaded(null, is360);
            return;
        }
        BufferedImage cached;
        synchronized (cache) {
            cached = cache.get(url);
        }
        if (cached != null) {
            showLoaded(cached, is360);
            return;
        }
        final long token = loadToken.incrementAndGet();
        exec.submit(() -> {
            try {
                // Resolve the raw (stored) URL to fetchable bytes: signed when logged
                // in (private + public), raw otherwise. Cache stays keyed by the raw
                // URL so it survives signed-URL expiry.
                String fetchUrl = ViewerApiClient.resolveImageUrl(url);
                BufferedImage img = fetch(fetchUrl);
                if (img != null && is360) {
                    img = capPano(img); // bound cache memory for big equirectangular frames
                }
                if (img != null) {
                    synchronized (cache) {
                        cache.put(url, img);
                    }
                }
                final BufferedImage fimg = img;
                final boolean loggedIn = ViewerAuth.getInstance().isLoggedIn();
                SwingUtilities.invokeLater(() -> {
                    if (token != loadToken.get()) {
                        return;
                    }
                    try {
                        showLoaded(fimg, is360);
                        if (fimg == null) {
                            status.setText(loggedIn
                                    ? "<html>Image unavailable</html>"
                                    : "<html>Image unavailable"
                                    + "<br><span style='font-size:90%'>(may be private — log in to Viewer to view)</span></html>");
                        }
                    } catch (Throwable t) {
                        Logging.warn("Maprizon: show image failed: " + t);
                        status.setText("<html>Error showing image</html>");
                    }
                });
            } catch (Throwable t) {
                Logging.warn("Maprizon: image load failed: " + t);
                SwingUtilities.invokeLater(() -> status.setText("<html>Error loading image</html>"));
            }
        });
    }

    /** Route a decoded frame to the right viewer: the panorama panel for a real
     * equirectangular 360, else the flat image panel. EDT. */
    private void showLoaded(BufferedImage img, boolean is360) {
        if (is360 && img != null && isEquirect(img)) {
            // Base compass bearing of the panorama's centre column; the live on-map
            // wedge points at base + current look-yaw (mirrors the viewer's cone).
            Double baseObj = frames.isEmpty() ? null : coneBearing(frames, index);
            final double base = baseObj == null ? 0.0 : baseObj;
            panoPanel.setPanorama(img);
            panoPanel.setYawListener(yawDeg -> {
                if (originatingLayer != null) {
                    originatingLayer.setViewConeBearing(norm360(base + yawDeg));
                }
            });
            setHost(panoPanel);
        } else {
            imagePanel.setImage(img);
            setHost(imagePanel);
        }
    }

    private void setHost(JComponent panel) {
        if (viewerHost.getComponentCount() == 1 && viewerHost.getComponent(0) == panel) {
            return;
        }
        viewerHost.removeAll();
        viewerHost.add(panel, BorderLayout.CENTER);
        viewerHost.revalidate();
        viewerHost.repaint();
    }

    private static boolean isEquirect(BufferedImage img) {
        double ar = (double) img.getWidth() / Math.max(1, img.getHeight());
        return ar >= 1.9 && ar <= 2.1;
    }

    /** Downscale a panorama wider than {@link #MAX_PANO_WIDTH} (aspect-preserving,
     * bilinear) so the frame cache can't exhaust the heap. */
    private static BufferedImage capPano(BufferedImage src) {
        if (src.getWidth() <= MAX_PANO_WIDTH) {
            return src;
        }
        int w = MAX_PANO_WIDTH;
        int h = (int) Math.round((double) src.getHeight() * MAX_PANO_WIDTH / src.getWidth());
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage fetch(String url) {
        try {
            HttpClient.Response res = HttpClient.create(new URL(url))
                    .setConnectTimeout(10_000)
                    .setReadTimeout(30_000)
                    .connect();
            if (res.getResponseCode() != 200) {
                Logging.warn("Maprizon: image fetch HTTP " + res.getResponseCode() + " for " + url);
                return null;
            }
            return ImageIO.read(res.getContent());
        } catch (IOException | RuntimeException e) {
            Logging.warn("Maprizon: image fetch failed: " + e);
            return null;
        }
    }

    @Override
    public void destroy() {
        ViewerAuth.getInstance().removeLoginStateListener(authListener);
        exec.shutdownNow();
        if (instance == this) {
            instance = null;
        }
        super.destroy();
    }

    /** Draws the current image scaled to fit while preserving aspect ratio. */
    private static final class ImagePanel extends JPanel {
        private transient BufferedImage image;

        ImagePanel() {
            setBackground(Color.DARK_GRAY);
            setPreferredSize(new Dimension(320, 240));
        }

        void setImage(BufferedImage img) {
            this.image = img;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int pw = getWidth();
            int ph = getHeight();
            int iw = image.getWidth();
            int ih = image.getHeight();
            if (iw <= 0 || ih <= 0) {
                return;
            }
            double scale = Math.min((double) pw / iw, (double) ph / ih);
            int w = (int) Math.round(iw * scale);
            int h = (int) Math.round(ih * scale);
            g2.drawImage(image, (pw - w) / 2, (ph - h) / 2, w, h, null);
        }
    }
}
