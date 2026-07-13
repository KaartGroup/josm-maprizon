package org.openstreetmap.josm.plugins.maprizon.layer;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.Notification;
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
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JOSM map layer showing Maprizon street-level imagery sequence coverage,
 * colored by camera facing, fetched from the public per-facing PMTiles files.
 *
 * <p>Coverage is fetched the way JOSM's own OSM data is: <b>scoped to the
 * current view and downloaded on demand</b> (Layer menu / toolbar
 * "Download Maprizon coverage in current view"), at the slippy zoom that
 * matches the on-screen resolution, and <b>accumulated</b> across downloads.
 * There is an opt-in "Auto-refresh on pan" mode for a live feel, but it is off
 * by default so the layer never silently dumps world-scale geometry.
 *
 * <p>Phase 1 scope: read-only coverage display + "View in Maprizon" deep link
 * for the nearest feature to the last map click. See the README for what is
 * explicitly NOT built yet (reverse sync, changeset attribution, private-org
 * coverage).
 */
public class MaprizonLayer extends Layer implements MouseListener {

    /**
     * Safety cap on the number of NEW tiles a single explicit download will
     * fetch (summed across enabled facings). Beyond this the user is asked to
     * zoom in, mirroring JOSM's "download area too large" guard — we never
     * coarsen to world zoom to fit. Because the zoom is screen-matched, one
     * viewport is ~ (screenW/256)·(screenH/256) tiles PER facing regardless of
     * geographic scale, so a 4K screen across all 5 facings is ~600 tiles; 1024
     * comfortably allows a full hi-DPI view while still catching pathological
     * requests. Tunable.
     */
    private static final int MAX_TILES_PER_DOWNLOAD = 1024;

    /** Archive zoom fallbacks if a reader header can't be read. */
    private static final int FALLBACK_MIN_ZOOM = 2;
    private static final int FALLBACK_MAX_ZOOM = 16;

    /**
     * Max levels to walk UP (to parent tiles) when the tile at the requested
     * zoom doesn't exist in the archive — i.e. overzoom. The archives advertise
     * maxZoom=16 in their header but only bake tiles down to z15, so a zoomed-in
     * request for z16 returns nothing; falling back to the z15 parent renders the
     * deepest real coverage instead of a "no coverage" modal. BOUNDED so a
     * street-level view can never collapse all the way to a low-zoom world tile
     * (which would pull thousands of features — the scale rule). 3 levels covers
     * the header-vs-real gap plus moderate sparsity.
     */
    private static final int MAX_OVERZOOM_STEPS = 3;

    private final PmtilesTileLoader loader = new PmtilesTileLoader();
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maprizon-tile-loader");
        t.setDaemon(true);
        return t;
    });

    /** Thread-safe: read on the loader thread during a download, mutated on the
     * EDT by the Hide/Show menu actions. Iteration order is not relied on
     * (ordered iteration always uses FacingStyle.ALL_FACINGS + contains). */
    private final Set<String> enabledFacings = ConcurrentHashMap.newKeySet();

    /** Archive zoom range, cached so screenZoom() never blocks on a reader-header
     * HTTP read (it is called from paint() on the EDT in auto-refresh mode).
     * Seeded with the fallback range and refreshed on the loader thread. */
    private volatile int archiveMinZoomCached = FALLBACK_MIN_ZOOM;
    private volatile int archiveMaxZoomCached = FALLBACK_MAX_ZOOM;
    private volatile boolean zoomBoundsResolved = false;

    /**
     * facing -> accumulated features currently rendered. Mutated only on the EDT
     * (in {@link #merge}); {@code volatile} so the loader thread sees a stable
     * reference when planning. Downloads ADD to this (accumulate), matching how
     * JOSM data layers grow as you download more areas.
     */
    private volatile Map<String, List<ImageryFeature>> featuresByFacing = new HashMap<>();

    /** Tiles already fetched (key = facing/z/x/y), so re-downloading an overlapping
     * area skips work. Concurrent: read on the loader thread, added on the EDT. */
    private final Set<String> loadedTileKeys = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean loading = new AtomicBoolean(false);

    /** Opt-in live behavior: re-download around the view as it changes. Off by default. */
    private volatile boolean autoRefresh = false;
    private volatile Bounds lastAutoBounds;
    private volatile int lastAutoZoom = -1;

    private LatLon lastClickLatLon;
    private ImageryFeature lastNearestFeature;
    private double[] lastNearestPoint;

    // --- rendering constants (Phase 1 styling: coverage was hairline-thin +
    // tiny dots, invisible except at max zoom; these make it legible). ---
    /** Sequence-line stroke width, in px. */
    private static final float LINE_WIDTH = 3.0f;
    /** Per-image point marker radius, in px. */
    private static final int POINT_RADIUS = 3;
    /** Highlight ring radius for the selected feature's clicked point, in px. */
    private static final int SELECTED_RADIUS = 8;
    /** Max screen-space distance (px) from a click to a feature for it to count
     * as selected — zoom-independent, so a click in empty space selects nothing. */
    private static final double SELECT_PIXEL_THRESHOLD = 18.0;

    public MaprizonLayer() {
        super("Maprizon Coverage");
        enabledFacings.addAll(FacingStyle.ALL_FACINGS);
    }

    @Override
    public void hookUpMapView() {
        MapFrame map = MainApplication.getMap();
        if (map != null && map.mapView != null) {
            map.mapView.addMouseListener(this);
        }
        // No auto-download on add: coverage is fetched explicitly (see
        // downloadCurrentView), so adding the layer never dumps world geometry.
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
        // Live mode only: opt-in re-download when the view has meaningfully moved
        // or zoomed. Uses the SAME fetch path as an explicit download, just
        // without the area-too-large prompt.
        if (autoRefresh && !loading.get() && autoViewChanged(bbox, mv)) {
            submitDownload(bbox, mv.getWidth(), false, false);
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(LINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (Map.Entry<String, List<ImageryFeature>> entry : featuresByFacing.entrySet()) {
            String facing = entry.getKey();
            if (!enabledFacings.contains(facing)) {
                continue;
            }
            g.setColor(FacingStyle.colorFor(facing));
            for (ImageryFeature feature : entry.getValue()) {
                paintFeature(g, mv, feature);
            }
        }

        // Selected-feature highlight drawn last, on top of everything.
        if (lastNearestFeature != null && lastNearestPoint != null
                && enabledFacings.contains(lastNearestFeature.getFacing())) {
            paintHighlight(g, mv);
        }
    }

    private void paintFeature(Graphics2D g, MapView mv, ImageryFeature feature) {
        List<double[]> pts = feature.getPoints();
        Point prev = null;
        int r = POINT_RADIUS;
        for (double[] lonLat : pts) {
            Point p = mv.getPoint(new LatLon(lonLat[1], lonLat[0]));
            if (prev != null) {
                g.drawLine(prev.x, prev.y, p.x, p.y);
            }
            g.fillOval(p.x - r, p.y - r, 2 * r, 2 * r);
            prev = p;
        }
    }

    /** Draw a prominent ring + facing-coloured dot at the currently selected
     * feature's clicked point, so a click gives visible confirmation of what is
     * selected (and thus what "View in Maprizon" / double-click will open). */
    private void paintHighlight(Graphics2D g, MapView mv) {
        Point p = mv.getPoint(new LatLon(lastNearestPoint[1], lastNearestPoint[0]));
        int r = SELECTED_RADIUS;
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(Color.WHITE);
        g.drawOval(p.x - r - 1, p.y - r - 1, 2 * (r + 1), 2 * (r + 1));
        g.setColor(FacingStyle.colorFor(lastNearestFeature.getFacing()));
        g.drawOval(p.x - r, p.y - r, 2 * r, 2 * r);
        g.fillOval(p.x - POINT_RADIUS, p.y - POINT_RADIUS, 2 * POINT_RADIUS, 2 * POINT_RADIUS);
    }

    // ------------------------------------------------------------- download

    /**
     * Explicit, JOSM-style scoped download: fetch coverage for the CURRENT view
     * at the on-screen zoom, enforcing the area-too-large guard. Public so the
     * DownloadMaprizonCoverageAction / menu can invoke it.
     */
    public void downloadCurrentView() {
        MapFrame map = MainApplication.getMap();
        if (map == null || map.mapView == null) {
            return;
        }
        MapView mv = map.mapView;
        submitDownload(mv.getRealBounds(), mv.getWidth(), true, true);
    }

    /** Drop all downloaded coverage (start fresh). */
    public void clearCoverage() {
        loadedTileKeys.clear();
        featuresByFacing = new HashMap<>();
        lastAutoBounds = null;
        lastAutoZoom = -1;
        invalidate();
    }

    /**
     * SSOT fetch path used by BOTH the explicit download and the auto-refresh
     * mode. Plans the covering tiles for {@code bbox} at the screen-matched zoom
     * (skipping already-loaded tiles), optionally enforces the tile budget, then
     * fetches + merges on the background executor.
     *
     * @param enforceBudget  true for explicit downloads (prompt if too large);
     *                       false for auto-refresh (silently bounded by the view).
     * @param userInitiated  true to surface an empty/too-large result to the user.
     */
    private void submitDownload(Bounds bbox, int widthPx, boolean enforceBudget, boolean userInitiated) {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        final Bounds view = new Bounds(bbox);
        loadExecutor.submit(() -> {
            try {
                refreshZoomBounds(); // blocking header read, but on the loader thread
                int zoom = screenZoom(view, widthPx);
                Map<String, int[]> rangesByFacing = new LinkedHashMap<>();
                long totalNewTiles = 0;
                for (String facing : FacingStyle.ALL_FACINGS) {
                    if (!enabledFacings.contains(facing)) {
                        continue;
                    }
                    int[] r = tileRange(view, zoom);
                    rangesByFacing.put(facing, r);
                    totalNewTiles += countNewTiles(facing, zoom, r);
                }

                if (enforceBudget && totalNewTiles > MAX_TILES_PER_DOWNLOAD) {
                    final long tooMany = totalNewTiles;
                    SwingUtilities.invokeLater(() -> showTooLarge(tooMany));
                    return;
                }

                Map<String, List<ImageryFeature>> fetched = new HashMap<>();
                int newFeatures = 0;
                for (Map.Entry<String, int[]> e : rangesByFacing.entrySet()) {
                    String facing = e.getKey();
                    int[] r = e.getValue();
                    List<ImageryFeature> acc = new ArrayList<>();
                    for (int tx = r[0]; tx <= r[1]; tx++) {
                        for (int ty = r[2]; ty <= r[3]; ty++) {
                            String key = tileKey(facing, zoom, tx, ty);
                            if (loadedTileKeys.contains(key)) {
                                continue;
                            }
                            try {
                                acc.addAll(loadWithOverzoom(facing, zoom, tx, ty));
                                // Record the REQUESTED tile as loaded ONLY on
                                // success, HERE on the loader thread (not deferred
                                // to the EDT merge): the single-thread executor
                                // guarantees this download's ledger is complete
                                // before the next one plans, so overlapping
                                // downloads never re-fetch or double-count. A tile
                                // that threw stays absent from the ledger ->
                                // retriable. (loadWithOverzoom separately records
                                // the ANCESTOR tile it actually served, so sibling
                                // requests that collapse onto the same parent skip
                                // the decode instead of duplicating its features.)
                                loadedTileKeys.add(key);
                            } catch (IOException ioe) {
                                Logging.warn("Maprizon: tile fetch failed " + key + ": " + ioe.getMessage());
                            }
                        }
                    }
                    fetched.put(facing, acc);
                    newFeatures += acc.size();
                }

                final int added = newFeatures;
                SwingUtilities.invokeLater(() -> {
                    merge(fetched);
                    invalidate();
                    if (userInitiated && added == 0) {
                        JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                                "No Maprizon coverage found in this view.\n" +
                                        "Try a different area, or zoom to a place with recent captures.",
                                "Maprizon", JOptionPane.INFORMATION_MESSAGE);
                    }
                });
            } catch (Exception ex) {
                Logging.warn("Maprizon: coverage download failed: " + ex);
            } finally {
                loading.set(false);
            }
        });
    }

    /** Merge freshly fetched features into the accumulated store. EDT only.
     * (The tile ledger {@code loadedTileKeys} is updated on the loader thread as
     * tiles are fetched, not here — see {@link #submitDownload}.) */
    private void merge(Map<String, List<ImageryFeature>> fetched) {
        Map<String, List<ImageryFeature>> next = new HashMap<>(featuresByFacing);
        for (Map.Entry<String, List<ImageryFeature>> e : fetched.entrySet()) {
            List<ImageryFeature> combined = new ArrayList<>(
                    next.getOrDefault(e.getKey(), Collections.emptyList()));
            combined.addAll(e.getValue());
            next.put(e.getKey(), combined);
        }
        featuresByFacing = next;
    }

    /**
     * Fetch one requested tile's features with BOUNDED overzoom: if the tile is
     * absent at the requested zoom, walk up to parent tiles (z-1, z-2, …) until
     * one exists, or the step budget / archive-min-zoom floor is reached. This is
     * what makes zoomed-in views render — the archives declare maxZoom=16 but only
     * bake to z15, so the requested z16 tile is absent and we fall back to z15.
     *
     * <p>Records the ANCESTOR tile actually served in {@link #loadedTileKeys}, and
     * returns an empty list (skipping the decode) when a sibling request already
     * served that same ancestor — so several requested tiles collapsing onto one
     * parent never duplicate its features.
     */
    private List<ImageryFeature> loadWithOverzoom(String facing, int zoom, int tx, int ty) throws IOException {
        int az = zoom;
        int ax = tx;
        int ay = ty;
        int minZoom = archiveMinZoom();
        for (int step = 0; step <= MAX_OVERZOOM_STEPS && az >= minZoom; step++) {
            String servedKey = tileKey(facing, az, ax, ay);
            if (loadedTileKeys.contains(servedKey)) {
                // A sibling already fetched this ancestor; its features are in the
                // store — don't add them again.
                return Collections.emptyList();
            }
            List<ImageryFeature> features = loader.loadTileOrNull(facing, az, ax, ay);
            if (features != null) {
                loadedTileKeys.add(servedKey);
                return features;
            }
            // Tile absent at this level -> step up to the parent tile.
            az -= 1;
            ax >>= 1;
            ay >>= 1;
        }
        return Collections.emptyList();
    }

    private long countNewTiles(String facing, int zoom, int[] r) {
        long n = 0;
        for (int tx = r[0]; tx <= r[1]; tx++) {
            for (int ty = r[2]; ty <= r[3]; ty++) {
                if (!loadedTileKeys.contains(tileKey(facing, zoom, tx, ty))) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String tileKey(String facing, int z, int x, int y) {
        return facing + "/" + z + "/" + x + "/" + y;
    }

    /** Tile range [minX,maxX,minY,maxY] covering {@code bbox} at {@code zoom}. */
    private int[] tileRange(Bounds bounds, int zoom) {
        int[] a = TileMath.lonLatToTile(bounds.getMinLon(), bounds.getMaxLat(), zoom);
        int[] b = TileMath.lonLatToTile(bounds.getMaxLon(), bounds.getMinLat(), zoom);
        return new int[]{
                Math.min(a[0], b[0]), Math.max(a[0], b[0]),
                Math.min(a[1], b[1]), Math.max(a[1], b[1])
        };
    }

    /**
     * SSOT zoom selection: the slippy zoom whose tile resolution matches the
     * current on-screen resolution, clamped to the archive's [min,max]. This
     * replaces the old "~6 tiles across" heuristic + coarsen-to-minZoom loop that
     * collapsed to world zoom. At the view's pixel width {@code widthPx} spanning
     * {@code lonSpan} degrees, a slippy tile is 256px / (360/2^z) degrees, so the
     * matching zoom is z = log2(widthPx * 360 / (lonSpan * 256)).
     */
    private int screenZoom(Bounds view, int widthPx) {
        double lonSpan = Math.max(1e-9, view.getMaxLon() - view.getMinLon());
        int px = Math.max(1, widthPx);
        int z = (int) Math.round(Math.log(px * 360.0 / (lonSpan * 256.0)) / Math.log(2));
        return Math.max(archiveMinZoom(), Math.min(archiveMaxZoom(), z));
    }

    // Non-blocking accessors: return the cached range (seeded with the fallback,
    // refreshed on the loader thread by refreshZoomBounds()). Never do network
    // I/O here — screenZoom() calls these from paint() on the EDT.
    private int archiveMinZoom() {
        return archiveMinZoomCached;
    }

    private int archiveMaxZoom() {
        return archiveMaxZoomCached;
    }

    /** Resolve the archive's real zoom range once, on the loader thread (the
     * reader-header read is a blocking HTTP range request). Iterates
     * FacingStyle.ALL_FACINGS (not enabledFacings) since all facings share the
     * same archive structure and any one gives the range. */
    private void refreshZoomBounds() {
        if (zoomBoundsResolved) {
            return;
        }
        for (String facing : FacingStyle.ALL_FACINGS) {
            try {
                archiveMinZoomCached = loader.getMinZoom(facing);
                archiveMaxZoomCached = loader.getMaxZoom(facing);
                zoomBoundsResolved = true;
                return;
            } catch (IOException ignored) {
                // try next facing; keep the fallback range if none resolve
            }
        }
    }

    /** True when the view moved to a new tile footprint / zoom since the last auto load. */
    private boolean autoViewChanged(Bounds bbox, MapView mv) {
        int zoom = screenZoom(bbox, mv.getWidth());
        Bounds last = lastAutoBounds;
        boolean changed = last == null || zoom != lastAutoZoom
                || !last.contains(bbox.getMin()) || !last.contains(bbox.getMax());
        if (changed) {
            lastAutoBounds = new Bounds(bbox);
            lastAutoZoom = zoom;
        }
        return changed;
    }

    private void showTooLarge(long tiles) {
        JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                "This view is too large to download Maprizon coverage in one go\n" +
                        "(" + tiles + " tiles > " + MAX_TILES_PER_DOWNLOAD + " limit).\n\n" +
                        "Zoom in further and download again — coverage accumulates across downloads.",
                "Maprizon: zoom in to download",
                JOptionPane.INFORMATION_MESSAGE);
    }

    public boolean isAutoRefresh() {
        return autoRefresh;
    }

    public void setAutoRefresh(boolean on) {
        autoRefresh = on;
        if (on) {
            invalidate(); // next paint() picks up the current view
        }
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
        // Reject if the nearest feature is too far from the click in SCREEN space
        // (zoom-independent), so clicking empty map selects nothing.
        if (nearest != null) {
            Point fp = map.mapView.getPoint(new LatLon(nearest.point[1], nearest.point[0]));
            if (Math.hypot(fp.x - e.getX(), fp.y - e.getY()) > SELECT_PIXEL_THRESHOLD) {
                nearest = null;
            }
        }
        if (nearest == null) {
            return;
        }
        lastNearestFeature = nearest.feature;
        lastNearestPoint = nearest.point;
        invalidate();
        if (e.getClickCount() >= 2) {
            openInMaprizon();
        } else {
            showFeatureInfo(nearest.feature);
        }
    }

    /** Surface the selected feature's identity plus how to open it, right at the
     * click, instead of the "View in Maprizon" action being hidden in the layer
     * menu. */
    private void showFeatureInfo(ImageryFeature f) {
        StringBuilder sb = new StringBuilder("<html><b>Maprizon:</b> ").append(f.getFacing());
        if (f.getSequenceId() != null) {
            sb.append("<br>sequence ").append(f.getSequenceId());
        }
        if (f.getTimestamp() != null) {
            sb.append("<br>").append(f.getTimestamp());
        }
        sb.append("<br><i>double-click to open in Maprizon</i></html>");
        new Notification(sb.toString()).setDuration(Notification.TIME_SHORT).show();
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
        StringBuilder sb = new StringBuilder("<html>Maprizon coverage (downloaded)<br>");
        sb.append(total).append(" features across ").append(loadedTileKeys.size()).append(" tile(s)<br>");
        for (String facing : FacingStyle.ALL_FACINGS) {
            int count = featuresByFacing.getOrDefault(facing, Collections.emptyList()).size();
            sb.append(facing).append(": ").append(count)
                    .append(enabledFacings.contains(facing) ? "" : " (hidden)").append("<br>");
        }
        sb.append(autoRefresh ? "auto-refresh: on" : "auto-refresh: off").append("<br>");
        sb.append("</html>");
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(sb.toString()), BorderLayout.CENTER);
        return panel;
    }

    @Override
    public String getToolTipText() {
        int total = featuresByFacing.values().stream().mapToInt(List::size).sum();
        return "Maprizon coverage (" + total + " features downloaded)";
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
        actions.add(new AbstractAction("Download Maprizon coverage in current view") {
            @Override
            public void actionPerformed(ActionEvent e) {
                downloadCurrentView();
            }
        });
        actions.add(new AbstractAction("Clear downloaded coverage") {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearCoverage();
            }
        });
        actions.add(new AbstractAction(
                (autoRefresh ? "Disable" : "Enable") + " auto-refresh on pan") {
            @Override
            public void actionPerformed(ActionEvent e) {
                setAutoRefresh(!autoRefresh);
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
     * Builds the Maprizon deep link for a feature at (lat, lon). Only sequence_id
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
