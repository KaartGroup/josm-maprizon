package org.openstreetmap.josm.plugins.maprizon.layer;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.plugins.maprizon.FacingStyle;
import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.PmtilesTileLoader;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.TileMath;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JOSM map layer showing Maprizon street-level imagery sequence coverage,
 * colored by camera facing, fetched from the public per-facing PMTiles files.
 *
 * Phase 1 scope: read-only coverage display + "View in Maprizon" deep link
 * for the nearest feature to the last map click. See this repo's README for
 * what is explicitly NOT built yet (reverse sync, changeset attribution,
 * private-org coverage).
 */
public class MaprizonLayer extends Layer implements MouseListener {

    /** Cap on tiles fetched per refresh, to keep a single refresh cheap/bounded. */
    private static final int MAX_TILES_PER_FACING = 48;
    private static final int TARGET_TILES_ACROSS = 6;

    private final PmtilesTileLoader loader = new PmtilesTileLoader();
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maprizon-tile-loader");
        t.setDaemon(true);
        return t;
    });

    private final Set<String> enabledFacings = new LinkedHashSet<>(FacingStyle.ALL_FACINGS);

    /** facing -> features currently cached/rendered. Replaced wholesale after each successful load. */
    private volatile java.util.Map<String, List<ImageryFeature>> featuresByFacing = new java.util.HashMap<>();

    private final AtomicBoolean loading = new AtomicBoolean(false);
    private volatile Bounds lastLoadedBounds;

    private LatLon lastClickLatLon;
    private ImageryFeature lastNearestFeature;
    private double[] lastNearestPoint;

    public MaprizonLayer() {
        super("Maprizon Coverage");
    }

    @Override
    public void hookUpMapView() {
        MapFrame map = MainApplication.getMap();
        if (map != null && map.mapView != null) {
            map.mapView.addMouseListener(this);
        }
        triggerLoad(true);
    }

    @Override
    public synchronized void destroy() {
        MapFrame map = MainApplication.getMap();
        if (map != null && map.mapView != null) {
            map.mapView.removeMouseListener(this);
        }
        loadExecutor.shutdownNow();
        loader.close();
        super.destroy();
    }

    // ---------------------------------------------------------------- paint

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        if (!loading.get() && needsReload(bbox)) {
            triggerLoad(false);
        }

        for (java.util.Map.Entry<String, List<ImageryFeature>> entry : featuresByFacing.entrySet()) {
            String facing = entry.getKey();
            if (!enabledFacings.contains(facing)) {
                continue;
            }
            g.setColor(FacingStyle.colorFor(facing));
            for (ImageryFeature feature : entry.getValue()) {
                paintFeature(g, mv, feature);
            }
        }
    }

    private void paintFeature(Graphics2D g, MapView mv, ImageryFeature feature) {
        List<double[]> pts = feature.getPoints();
        Point prev = null;
        for (double[] lonLat : pts) {
            Point p = mv.getPoint(new LatLon(lonLat[1], lonLat[0]));
            if (prev != null) {
                g.drawLine(prev.x, prev.y, p.x, p.y);
            }
            g.fillOval(p.x - 2, p.y - 2, 4, 4);
            prev = p;
        }
    }

    // ------------------------------------------------------------- loading

    private boolean needsReload(Bounds bbox) {
        Bounds last = lastLoadedBounds;
        if (last == null) {
            return true;
        }
        // Reload if the view moved/zoomed outside of what we last fetched.
        return !last.contains(bbox.getMin()) || !last.contains(bbox.getMax());
    }

    /** Kick off an async reload for the current view. Safe to call from the EDT. */
    public void triggerLoad(boolean force) {
        MapFrame map = MainApplication.getMap();
        if (map == null || map.mapView == null) {
            return;
        }
        // Never run two loads concurrently; a forced refresh while one is already
        // in flight simply lets that in-flight load win (it will pick up the
        // current bounds itself once it's done, via the next paint() call).
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        Bounds view = map.mapView.getRealBounds();
        // Expand slightly so small pans don't immediately re-trigger a reload.
        double latPad = (view.getMaxLat() - view.getMinLat()) * 0.25;
        double lonPad = (view.getMaxLon() - view.getMinLon()) * 0.25;
        Bounds expanded = new Bounds(view);
        expanded.extend(view.getMinLat() - latPad, view.getMinLon() - lonPad);
        expanded.extend(view.getMaxLat() + latPad, view.getMaxLon() + lonPad);

        loadExecutor.submit(() -> {
            try {
                java.util.Map<String, List<ImageryFeature>> loaded = loadAllFacings(expanded);
                SwingUtilities.invokeLater(() -> {
                    featuresByFacing = loaded;
                    lastLoadedBounds = expanded;
                    invalidate();
                });
            } catch (Exception e) {
                Logging.warn("Maprizon: coverage load failed: " + e);
            } finally {
                loading.set(false);
            }
        });
    }

    private java.util.Map<String, List<ImageryFeature>> loadAllFacings(Bounds bounds) {
        java.util.Map<String, List<ImageryFeature>> result = new java.util.HashMap<>();
        for (String facing : FacingStyle.ALL_FACINGS) {
            if (!enabledFacings.contains(facing)) {
                continue;
            }
            try {
                result.put(facing, loadFacing(facing, bounds));
            } catch (IOException e) {
                Logging.warn("Maprizon: failed to load facing '" + facing + "': " + e.getMessage());
                result.put(facing, java.util.Collections.emptyList());
            }
        }
        return result;
    }

    private List<ImageryFeature> loadFacing(String facing, Bounds bounds) throws IOException {
        int minZoom = loader.getMinZoom(facing);
        int maxZoom = loader.getMaxZoom(facing);
        int zoom = pickZoom(bounds, minZoom, maxZoom);

        int[] min = TileMath.lonLatToTile(bounds.getMinLon(), bounds.getMaxLat(), zoom);
        int[] max = TileMath.lonLatToTile(bounds.getMaxLon(), bounds.getMinLat(), zoom);
        int minX = Math.min(min[0], max[0]);
        int maxX = Math.max(min[0], max[0]);
        int minY = Math.min(min[1], max[1]);
        int maxY = Math.max(min[1], max[1]);

        // Coarsen zoom further if the naive tile range is still too big to fetch.
        while ((long) (maxX - minX + 1) * (maxY - minY + 1) > MAX_TILES_PER_FACING && zoom > minZoom) {
            zoom--;
            min = TileMath.lonLatToTile(bounds.getMinLon(), bounds.getMaxLat(), zoom);
            max = TileMath.lonLatToTile(bounds.getMaxLon(), bounds.getMinLat(), zoom);
            minX = Math.min(min[0], max[0]);
            maxX = Math.max(min[0], max[0]);
            minY = Math.min(min[1], max[1]);
            maxY = Math.max(min[1], max[1]);
        }

        List<ImageryFeature> all = new ArrayList<>();
        int fetched = 0;
        for (int tx = minX; tx <= maxX; tx++) {
            for (int ty = minY; ty <= maxY; ty++) {
                if (fetched++ >= MAX_TILES_PER_FACING) {
                    break;
                }
                all.addAll(loader.loadTile(facing, zoom, tx, ty));
            }
        }
        return all;
    }

    private int pickZoom(Bounds bounds, int minZoom, int maxZoom) {
        double lonSpan = Math.max(1e-9, bounds.getMaxLon() - bounds.getMinLon());
        int zoom = (int) Math.round(Math.log(360.0 / lonSpan * TARGET_TILES_ACROSS) / Math.log(2));
        return Math.max(minZoom, Math.min(maxZoom, zoom));
    }

    // ----------------------------------------------------------- selection

    @Override
    public void mouseClicked(MouseEvent e) {
        MapFrame map = MainApplication.getMap();
        if (map == null || map.mapView == null) {
            return;
        }
        lastClickLatLon = map.mapView.getLatLon(e.getX(), e.getY());
        NearestResult nearest = findNearest(lastClickLatLon);
        if (nearest != null) {
            lastNearestFeature = nearest.feature;
            lastNearestPoint = nearest.point;
            invalidate();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }

    private static final class NearestResult {
        final ImageryFeature feature;
        final double[] point;

        NearestResult(ImageryFeature feature, double[] point) {
            this.feature = feature;
            this.point = point;
        }
    }

    private NearestResult findNearest(LatLon from) {
        ImageryFeature bestFeature = null;
        double[] bestPoint = null;
        double bestDistSq = Double.MAX_VALUE;
        for (List<ImageryFeature> features : featuresByFacing.values()) {
            for (ImageryFeature f : features) {
                for (double[] p : f.getPoints()) {
                    double dLon = p[0] - from.lon();
                    double dLat = p[1] - from.lat();
                    double distSq = dLon * dLon + dLat * dLat;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestFeature = f;
                        bestPoint = p;
                    }
                }
            }
        }
        return bestFeature == null ? null : new NearestResult(bestFeature, bestPoint);
    }

    // -------------------------------------------------------- Layer plumbing

    @Override
    public Icon getIcon() {
        try {
            return ImageProvider.get("maprizon");
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Object getInfoComponent() {
        int total = featuresByFacing.values().stream().mapToInt(List::size).sum();
        StringBuilder sb = new StringBuilder("<html>Maprizon coverage<br>");
        sb.append(total).append(" features loaded for the last-fetched view<br>");
        for (String facing : FacingStyle.ALL_FACINGS) {
            int count = featuresByFacing.getOrDefault(facing, java.util.Collections.emptyList()).size();
            sb.append(facing).append(": ").append(count).append(enabledFacings.contains(facing) ? "" : " (hidden)").append("<br>");
        }
        sb.append("</html>");
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(sb.toString()), BorderLayout.CENTER);
        return panel;
    }

    @Override
    public String getToolTipText() {
        int total = featuresByFacing.values().stream().mapToInt(List::size).sum();
        return "Maprizon coverage (" + total + " features in view)";
    }

    @Override
    public void mergeFrom(Layer from) {
        throw new UnsupportedOperationException("MaprizonLayer does not support merging");
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
        for (List<ImageryFeature> features : featuresByFacing.values()) {
            for (ImageryFeature f : features) {
                for (double[] p : f.getPoints()) {
                    v.visit(new LatLon(p[1], p[0]));
                }
            }
        }
    }

    @Override
    public Action[] getMenuEntries() {
        List<Action> actions = new ArrayList<>();
        actions.add(new AbstractAction("Refresh Maprizon coverage for current view") {
            @Override
            public void actionPerformed(ActionEvent e) {
                triggerLoad(true);
            }
        });
        actions.add(Layer.SeparatorLayerAction.INSTANCE);
        for (String facing : FacingStyle.ALL_FACINGS) {
            boolean enabled = enabledFacings.contains(facing);
            String label = (enabled ? "Hide " : "Show ") + facing + " facing";
            actions.add(new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (enabledFacings.contains(facing)) {
                        enabledFacings.remove(facing);
                    } else {
                        enabledFacings.add(facing);
                    }
                    invalidate();
                }
            });
        }
        actions.add(Layer.SeparatorLayerAction.INSTANCE);
        actions.add(new AbstractAction("View in Maprizon") {
            @Override
            public void actionPerformed(ActionEvent e) {
                openInMaprizon();
            }
        });
        actions.add(Layer.SeparatorLayerAction.INSTANCE);
        actions.add(LayerListDialog.getInstance().createShowHideLayerAction());
        actions.add(LayerListDialog.getInstance().createDeleteLayerAction());
        return actions.toArray(new Action[0]);
    }

    private void openInMaprizon() {
        if (lastNearestFeature == null || lastNearestPoint == null) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Click a point on the Maprizon coverage layer first, then use this action.",
                    "No Maprizon feature selected",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String url = buildDeepLink(lastNearestFeature, lastNearestPoint[1], lastNearestPoint[0]);
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            Logging.error("Maprizon: failed to open browser for deep link " + url, e);
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Could not open browser: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Builds the Viewer deep link for a feature at (lat, lon). Only sequence_id
     * and lat/lon are required; other fields are included when present on the
     * feature. Per spec, "facing" is only ever emitted for front/left/right/360 -
     * "still" is not an accepted value for that parameter, so it is omitted for
     * still features (the rest of the link is unaffected).
     */
    static String buildDeepLink(ImageryFeature feature, double lat, double lon) {
        StringBuilder qs = new StringBuilder("https://viewer.kaart.com/?");
        boolean first = true;
        first = appendParam(qs, "sequence_id", feature.getSequenceId(), first);
        first = appendParam(qs, "sequence_index", feature.getSequenceIndex(), first);
        if (FacingStyle.DEEP_LINK_FACINGS.contains(feature.getFacing())) {
            first = appendParam(qs, "facing", feature.getFacing(), first);
        }
        first = appendParam(qs, "trip_id", feature.getTripId(), first);
        first = appendParam(qs, "upload_batch_id", feature.getUploadBatchId(), first);
        appendParam(qs, "feature_timestamp", feature.getTimestamp(), first);

        qs.append("#mapHash=16.9/")
                .append(String.format(Locale.ROOT, "%.6f", lat))
                .append("/")
                .append(String.format(Locale.ROOT, "%.6f", lon));
        return qs.toString();
    }

    private static boolean appendParam(StringBuilder sb, String key, String value, boolean first) {
        if (value == null || value.isEmpty()) {
            return first;
        }
        if (!first) {
            sb.append("&");
        }
        sb.append(key).append("=").append(urlEncode(value));
        return false;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }
}
