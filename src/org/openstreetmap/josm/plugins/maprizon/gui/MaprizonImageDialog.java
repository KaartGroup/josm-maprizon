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
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
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

    /** Camera-facing buttons, in the app's DirectionPicker order. Keyed by facing so
     * the selected one can be filled when the shown frame changes. */
    private final Map<String, JToggleButton> facingButtons = new LinkedHashMap<>();
    private final JToggleButton adjustToggle = new JToggleButton("\u2600");
    private final JPanel adjustRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
    private final JSlider brightness = new JSlider(-100, 100, 0);
    private final JSlider contrast = new JSlider(50, 200, 100);
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
        viewerHost.add(imagePanel, BorderLayout.CENTER);
        root.add(viewerHost, BorderLayout.CENTER);
        root.add(buildControlBar(), BorderLayout.SOUTH);

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

    /**
     * One control bar under the image, replacing the old split navigation.
     *
     * <p>THREE THINGS THIS FIXES, all reported together:
     * <ul>
     * <li><b>Prev/Next used to be pinned to opposite edges</b> ({@code BorderLayout}
     * WEST/EAST), so widening the panel drove them further apart and stepping
     * through a sequence meant crossing the whole dialog between clicks. They are
     * centred now, and stay adjacent at every width.</li>
     * <li><b>The facing row.</b> Switching cameras previously meant going back to
     * the map, finding the same spot on another coloured ribbon and clicking it.
     * The buttons resolve the counterpart frame at the same capture point through
     * {@link ViewerApiClient#nearestFeature}, in the app's own order (360, Left,
     * Front, Right) and the app's own colours.</li>
     * <li><b>The frame's identity moved off the top.</b> It was a full-width label
     * above the image; it is a line inside this bar now, so the image gets that
     * height back.</li>
     * </ul>
     *
     * <p>Brightness/contrast sit behind a toggle rather than always-on sliders: they
     * are occasional (reading a sign in shadow), and this panel is often narrow.
     */
    private JComponent buildControlBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
        nav.add(prevButton);
        for (String facing : FacingStyle.DEEP_LINK_FACINGS.stream()
                .sorted(java.util.Comparator.comparingInt(MaprizonImageDialog::facingOrder))
                .collect(java.util.stream.Collectors.toList())) {
            JToggleButton b = new JToggleButton(facingLabel(facing));
            b.setToolTipText("Show this spot on the " + facingLabel(facing) + " camera");
            b.setMargin(new java.awt.Insets(1, 5, 1, 5));
            b.setFocusable(false);
            b.addActionListener(e -> swapFacing(facing));
            facingButtons.put(facing, b);
            nav.add(b);
        }
        adjustToggle.setToolTipText("Brightness and contrast");
        adjustToggle.setMargin(new java.awt.Insets(1, 5, 1, 5));
        adjustToggle.setFocusable(false);
        adjustToggle.addActionListener(e -> {
            adjustRow.setVisible(adjustToggle.isSelected());
            adjustRow.getParent().revalidate();
        });
        nav.add(adjustToggle);
        nav.add(nextButton);
        bar.add(nav);

        status.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));
        status.setAlignmentX(Component.CENTER_ALIGNMENT);
        bar.add(status);

        brightness.setToolTipText("Brightness");
        contrast.setToolTipText("Contrast");
        brightness.setPreferredSize(new Dimension(90, 18));
        contrast.setPreferredSize(new Dimension(90, 18));
        brightness.addChangeListener(e -> applyAdjustments());
        contrast.addChangeListener(e -> applyAdjustments());
        JButton reset = new JButton("Reset");
        reset.setMargin(new java.awt.Insets(1, 5, 1, 5));
        reset.setFocusable(false);
        reset.addActionListener(e -> {
            brightness.setValue(0);
            contrast.setValue(100);
        });
        adjustRow.add(new JLabel("\u2600"));
        adjustRow.add(brightness);
        adjustRow.add(new JLabel("\u25D1"));
        adjustRow.add(contrast);
        adjustRow.add(reset);
        adjustRow.setVisible(false);
        bar.add(adjustRow);

        prevButton.addActionListener(e -> step(-1));
        nextButton.addActionListener(e -> step(1));
        prevButton.setEnabled(false);
        nextButton.setEnabled(false);
        return bar;
    }

    /** The app's DirectionPicker order: 360 first, then Left / Front / Right. */
    private static int facingOrder(String facing) {
        switch (facing) {
            case FacingStyle.FACING_360: return 0;
            case FacingStyle.LEFT: return 1;
            case FacingStyle.FRONT: return 2;
            case FacingStyle.RIGHT: return 3;
            default: return 4;
        }
    }

    private static String facingLabel(String facing) {
        return FacingStyle.FACING_360.equals(facing) ? "360\u00b0"
                : Character.toUpperCase(facing.charAt(0)) + facing.substring(1);
    }

    /** Mark the button for the facing now on screen, clear the rest. EDT.
     *
     * <p>The mark is a coloured UNDERLINE, not a filled background. A filled
     * {@code JToggleButton} background is honoured by some look-and-feels and
     * silently ignored by others — Aqua being the one most of this project's users
     * are on — which would leave the selected camera indistinguishable on exactly
     * the platform it was developed on. A border is drawn by every LAF, and it
     * carries the facing's own colour, so the button agrees with the ribbon on the
     * map. */
    private void syncFacingButtons(String facing) {
        for (Map.Entry<String, JToggleButton> e : facingButtons.entrySet()) {
            boolean on = e.getKey().equalsIgnoreCase(facing);
            JToggleButton b = e.getValue();
            b.setSelected(on);
            b.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, on ? 3 : 1, 0,
                            on ? FacingStyle.colorFor(e.getKey()) : b.getBackground()),
                    BorderFactory.createEmptyBorder(1, 5, on ? 0 : 2, 5)));
        }
    }

    /**
     * Show this same spot on another camera. Resolves the counterpart frame off the
     * EDT, then reuses {@link #showForClickedFeature} so the swap lands in the new
     * facing's own sequence and can be walked from there.
     *
     * <p>A miss is reported as a miss and a fault as a fault. They are different
     * sentences on purpose: telling someone "no left camera here" when the request
     * was rate-limited sends them looking for imagery that exists.
     */
    private void swapFacing(String targetFacing) {
        if (frames.isEmpty() || index < 0 || index >= frames.size()) {
            return;
        }
        final ImageryFeature current = frames.get(index);
        if (targetFacing.equalsIgnoreCase(current.getFacing())) {
            syncFacingButtons(current.getFacing());
            return;
        }
        final MaprizonLayer layer = originatingLayer;
        status.setText("Switching camera…");
        exec.submit(() -> {
            ImageryFeature match = ViewerApiClient.nearestFeature(current, targetFacing);
            final String failure = ViewerApiClient.lastSwapFailure();
            SwingUtilities.invokeLater(() -> {
                if (match != null) {
                    List<double[]> pts = match.getPoints();
                    showForClickedFeature(match, pts.isEmpty() ? null : pts.get(0), layer);
                } else {
                    status.setText("<html>" + (failure == null
                            ? "No " + facingLabel(targetFacing) + " camera at this spot"
                            : "Couldn't switch camera — the imagery is still there, try again")
                            + "</html>");
                    syncFacingButtons(current.getFacing());
                }
            });
        });
    }

    /** Push the slider values into both viewers and repaint. EDT. */
    private void applyAdjustments() {
        float scale = contrast.getValue() / 100f;
        float offset = brightness.getValue();
        imagePanel.setAdjustments(scale, offset);
        panoPanel.setAdjustments(scale, offset);
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
        // ONE compact line now that this sits in the bar rather than above the image:
        // the facing is already shown by which button is lit, so it does not need
        // repeating in text, and the timestamp no longer costs a second row.
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(index + 1).append(" / ").append(frames.size());
        if (f.getTimestamp() != null) {
            sb.append("  ·  ").append(f.getTimestamp());
        }
        sb.append("</html>");
        status.setText(sb.toString());
        syncFacingButtons(f.getFacing());
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

    /** Downscale a panorama wider than {@link #MAX_PANO_WIDTH} (aspect-preserving)
     * so the frame cache can't exhaust the heap. Uses {@link #halveDownTo} rather
     * than one big {@code drawImage} for the reason given there. */
    private static BufferedImage capPano(BufferedImage src) {
        if (src.getWidth() <= MAX_PANO_WIDTH) {
            return src;
        }
        int w = MAX_PANO_WIDTH;
        int h = (int) Math.round((double) src.getHeight() * MAX_PANO_WIDTH / src.getWidth());
        return scaleTo(halveDownTo(src, w, h), w, h);
    }

    /** Repeatedly halve {@code src} while the next halving would still be at least
     * the target size. The result is <b>not</b> the target size — it is the smallest
     * power-of-two reduction that still covers it, ready for one final short step.
     *
     * <p>WHY THIS EXISTS. A single {@code drawImage} to the target size samples a
     * 2x2 neighbourhood per output pixel however far it is reducing, so at the
     * ratios this panel actually works at it reads ~4 of every 144 source pixels
     * and the other 140 are simply not looked at. That is not a blur, it is
     * undersampling: fine detail turns into speckle, which is what makes a
     * destination sign unreadable in the panel while the same frame is legible in
     * the browser (browsers downscale progressively).
     *
     * <p>Measured on a live 3840x2160 public frame (testbed/ImageQualityProbe.java),
     * against an exact area-average of the same frame, RMSE per channel:
     * <pre>
     *   panel width   one-step bilinear   one-step bicubic   progressive halving
     *   320                    14.30              15.48                  2.92
     *   420                    14.15              15.08                  4.65
     *   600                    11.77              12.79                  3.78
     * </pre>
     * Bicubic is <i>worse</i>, not better — the problem is which pixels get read,
     * not how they are weighted. Halving reads all of them. */
    private static BufferedImage halveDownTo(BufferedImage src, int targetW, int targetH) {
        BufferedImage cur = src;
        int w = src.getWidth();
        int h = src.getHeight();
        while (w / 2 >= targetW && h / 2 >= targetH && w / 2 > 0 && h / 2 > 0) {
            w /= 2;
            h /= 2;
            cur = scaleTo(cur, w, h);
        }
        return cur;
    }

    private static BufferedImage scaleTo(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) {
            return src;
        }
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

    /** Brightness/contrast as a {@link RescaleOp}, shared by the flat and panorama
     * viewers so one pair of sliders drives both.
     *
     * <p>Contrast is the multiplier and brightness the offset, which is exactly what
     * {@code RescaleOp} applies — {@code out = in * scale + offset}. Returns null at
     * the identity so the paint path can skip the whole filter rather than run a
     * no-op over every pixel of every frame. */
    static final class ImageAdjust {
        private ImageAdjust() {
        }

        static RescaleOp opFor(float scale, float offset) {
            if (Math.abs(scale - 1f) < 0.001f && Math.abs(offset) < 0.5f) {
                return null;
            }
            return new RescaleOp(scale, offset, null);
        }
    }

    /** Draws the current image fitted to the panel, and lets the user zoom in on it
     * (scroll wheel) and pan around (click-drag) — the flat counterpart to
     * {@link PanoramaPanel}'s look-around. Before this, only 360 frames could be
     * magnified, so a sign readable in a front/left/right image at full size could
     * not be read in the panel at all.
     *
     * <p>Zoom is a multiplier over the fit scale, so <b>1.0 is always "whole image
     * visible"</b> and is the floor — zooming out past the panel would only add
     * letterbox. Like the panorama's yaw, zoom and pan <b>persist across frames</b>:
     * walking a sequence while zoomed into a sign keeps the sign magnified instead
     * of dropping back to fit on every step. */
    private static final class ImagePanel extends JPanel {
        private transient BufferedImage image;

        /** Multiplier over the fit-to-panel scale; 1.0 = fitted (the floor). */
        private double zoom = 1.0;
        /** Pan offset in panel pixels from the centred position; 0 when fitted. */
        private double panX;
        private double panY;

        private static final double ZOOM_MAX = 8.0;
        private static final double ZOOM_STEP = 1.15;

        private int lastX;
        private int lastY;

        /** Progressively-halved copy of {@link #image} covering the current drawn
         * size, and the image it was derived from. Rebuilt only when the drawn size
         * crosses a power-of-two boundary, so panning and small zoom steps are free
         * and a resize costs one rebuild. */
        private transient BufferedImage mip;
        private transient BufferedImage mipOf;

        /** Brightness/contrast, or null when neither is off its default — the common
         * case, which then costs nothing at all in paint. */
        private transient RescaleOp adjust;

        ImagePanel() {
            setBackground(Color.DARK_GRAY);
            setPreferredSize(new Dimension(320, 240));
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    lastX = e.getX();
                    lastY = e.getY();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (zoom > 1.0) {
                        panX += e.getX() - lastX;
                        panY += e.getY() - lastY;
                        repaint();
                    }
                    lastX = e.getX();
                    lastY = e.getY();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() >= 2) {
                        resetView();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    zoomAt(e.getX(), e.getY(), Math.pow(ZOOM_STEP, -e.getWheelRotation()));
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        void setImage(BufferedImage img) {
            this.image = img;
            this.mip = null;
            this.mipOf = null;
            repaint();
        }

        void setAdjustments(float scale, float offset) {
            this.adjust = ImageAdjust.opFor(scale, offset);
            repaint();
        }

        /** The bitmap to draw from for a target of {@code drawnW x drawnH}: the
         * source itself when drawing at or above 1:1, else a cached halved copy.
         * See {@link MaprizonImageDialog#halveDownTo} for why the halving matters. */
        private BufferedImage sourceFor(int drawnW, int drawnH) {
            if (image == null) {
                return null;
            }
            if (drawnW >= image.getWidth() || drawnH >= image.getHeight()) {
                mip = null;
                mipOf = null;
                return image;
            }
            if (mip != null && mipOf == image
                    && mip.getWidth() / 2 < drawnW && mip.getWidth() >= drawnW) {
                return mip;
            }
            BufferedImage halved = halveDownTo(image, drawnW, drawnH);
            if (halved == image) {
                mip = null;
                mipOf = null;
                return image;
            }
            mip = halved;
            mipOf = image;
            return mip;
        }

        private void resetView() {
            zoom = 1.0;
            panX = panY = 0;
            setCursor(Cursor.getDefaultCursor());
            repaint();
        }

        /** Scale by {@code factor} about the panel point (mx, my), holding whatever
         * pixel is under the cursor still — otherwise repeated wheel clicks walk the
         * point of interest off-screen and the user has to chase it with the drag. */
        private void zoomAt(int mx, int my, double factor) {
            if (image == null) {
                return;
            }
            double fit = fitScale();
            if (fit <= 0) {
                return;
            }
            double before = fit * zoom;
            double after = fit * clamp(zoom * factor, 1.0, ZOOM_MAX);
            if (after == before) {
                return;
            }
            // Image coordinate currently under the cursor, held fixed across the change.
            double ix = (mx - originX(before)) / before;
            double iy = (my - originY(before)) / before;
            zoom = after / fit;
            panX = mx - ix * after - centredX(after);
            panY = my - iy * after - centredY(after);
            setCursor(zoom > 1.0 ? Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    : Cursor.getDefaultCursor());
            repaint();
        }

        private double fitScale() {
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return 0;
            }
            return Math.min((double) getWidth() / image.getWidth(),
                    (double) getHeight() / image.getHeight());
        }

        private double centredX(double scale) {
            return (getWidth() - image.getWidth() * scale) / 2;
        }

        private double centredY(double scale) {
            return (getHeight() - image.getHeight() * scale) / 2;
        }

        private double originX(double scale) {
            return centredX(scale) + clampPan(panX, getWidth(), image.getWidth() * scale);
        }

        private double originY(double scale) {
            return centredY(scale) + clampPan(panY, getHeight(), image.getHeight() * scale);
        }

        /** Keep the drawn image covering the panel while it is larger than the panel,
         * and pinned to centre while it is not, so the image can never be dragged off
         * into empty background. */
        private static double clampPan(double pan, int panelSize, double drawnSize) {
            if (drawnSize <= panelSize) {
                return 0;
            }
            double edge = (panelSize - drawnSize) / 2; // negative
            return clamp(pan, edge, -edge);
        }

        private static double clamp(double v, double lo, double hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                return;
            }
            double scale = fitScale() * zoom;
            if (scale <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int w = (int) Math.round(image.getWidth() * scale);
            int h = (int) Math.round(image.getHeight() * scale);
            // Re-clamp on every paint: the panel is resizable and the frames in a
            // sequence are not all the same size, so a pan valid a moment ago may not be.
            panX = clampPan(panX, getWidth(), w);
            panY = clampPan(panY, getHeight(), h);
            int ox = (int) Math.round(centredX(scale) + panX);
            int oy = (int) Math.round(centredY(scale) + panY);
            BufferedImage from = sourceFor(w, h);
            if (adjust == null) {
                g2.drawImage(from, ox, oy, w, h, null);
            } else {
                // Filter a PANEL-SIZED buffer, never the source: at 8x zoom the drawn
                // image is many times the panel, and only what is on screen needs
                // adjusting. Keeps the cost flat however far in the user has zoomed.
                BufferedImage buf = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D bg = buf.createGraphics();
                bg.setColor(getBackground());
                bg.fillRect(0, 0, getWidth(), getHeight());
                bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                bg.drawImage(from, ox, oy, w, h, null);
                bg.dispose();
                adjust.filter(buf, buf);
                g2.drawImage(buf, 0, 0, null);
            }
            if (zoom > 1.0) {
                paintZoomBadge(g2);
            }
        }

        private void paintZoomBadge(Graphics2D g) {
            String msg = String.format("%.1f\u00d7 \u00b7 drag to pan \u00b7 double-click to fit", zoom);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
            FontMetrics fm = g.getFontMetrics();
            int pad = 6;
            int x = 8;
            int y = getHeight() - fm.getHeight() - 8;
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(x, y, fm.stringWidth(msg) + 2 * pad, fm.getHeight() + pad, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(msg, x + pad, y + fm.getAscent() + pad / 2);
        }
    }
}
