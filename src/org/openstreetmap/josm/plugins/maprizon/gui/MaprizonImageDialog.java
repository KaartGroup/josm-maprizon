package org.openstreetmap.josm.plugins.maprizon.gui;

import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
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
 * <p>A single instance is registered per map frame; {@link #getInstance()} lets
 * the coverage layer's click handler drive it.
 */
public final class MaprizonImageDialog extends ToggleDialog {

    private static volatile MaprizonImageDialog instance;

    /** Small LRU of decoded frames so walking back and forth is instant. */
    private static final int CACHE_MAX = 40;
    private final Map<String, BufferedImage> cache = new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > CACHE_MAX;
        }
    };

    private final ImagePanel imagePanel = new ImagePanel();
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final JButton prevButton = new JButton("← Prev");
    private final JButton nextButton = new JButton("Next →");

    /** Background loads. Single thread → newest request wins visually via loadToken. */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
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
        root.add(imagePanel, BorderLayout.CENTER);

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

    private void step(int delta) {
        if (frames.isEmpty()) {
            return;
        }
        int next = index + delta;
        if (next < 0 || next >= frames.size()) {
            return;
        }
        index = next;
        display();
    }

    private void display() {
        if (frames.isEmpty()) {
            status.setText("No image selected");
            imagePanel.setImage(null);
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
            return;
        }
        ImageryFeature f = frames.get(index);
        // Move the on-map selection marker to the frame now being shown.
        if (originatingLayer != null) {
            originatingLayer.highlightFrame(f);
        }
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(f.getFacing()).append("  ·  ").append(index + 1).append(" / ").append(frames.size());
        if (f.getTimestamp() != null) {
            sb.append("<br>").append(f.getTimestamp());
        }
        sb.append("</html>");
        status.setText(sb.toString());
        prevButton.setEnabled(index > 0);
        nextButton.setEnabled(index < frames.size() - 1);
        loadImage(f.getImg());
    }

    private void loadImage(String url) {
        if (url == null) {
            imagePanel.setImage(null);
            return;
        }
        BufferedImage cached;
        synchronized (cache) {
            cached = cache.get(url);
        }
        if (cached != null) {
            imagePanel.setImage(cached);
            return;
        }
        final long token = loadToken.incrementAndGet();
        exec.submit(() -> {
            // Resolve the raw (stored) URL to fetchable bytes: signed when logged
            // in (private + public), raw otherwise. Cache stays keyed by the raw
            // URL so it survives signed-URL expiry.
            String fetchUrl = ViewerApiClient.resolveImageUrl(url);
            BufferedImage img = fetch(fetchUrl);
            if (img != null) {
                synchronized (cache) {
                    cache.put(url, img);
                }
            }
            final boolean loggedIn = ViewerAuth.getInstance().isLoggedIn();
            SwingUtilities.invokeLater(() -> {
                if (token == loadToken.get()) {
                    imagePanel.setImage(img);
                    if (img == null) {
                        status.setText(loggedIn
                                ? "<html>Image unavailable</html>"
                                : "<html>Image unavailable"
                                + "<br><span style='font-size:90%'>(may be private — log in to Viewer to view)</span></html>");
                    }
                }
            });
        });
    }

    private static BufferedImage fetch(String url) {
        try {
            HttpClient.Response res = HttpClient.create(new URL(url)).connect();
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
