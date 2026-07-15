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
import org.openstreetmap.josm.plugins.maprizon.gui.MaprizonImageDialog;
import org.openstreetmap.josm.plugins.maprizon.oauth.LoginFlow;
import org.openstreetmap.josm.plugins.maprizon.oauth.ViewerAuth;
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
     * maxZoom=16 in their header but bake NO z16 tiles, so a zoomed-in request for
     * z16 always misses on the first step (wasting it). Worse, different facings
     * are baked to different depths: `right` coverage begins at ~z15, but
     * `front`/`left`/`360` coverage for the same spot can begin only at ~z12.
     * With too small a budget the walk stops before reaching z12, so those facings
     * come back EMPTY while `right` renders — the "only right shows up" bug
     * (verified: budget 3 → front/left/360 = 0; budget 4 → all four populate).
     * So the budget must bridge z16→~z12 with margin. BOUNDED (not down to world
     * zoom) so a spot with genuinely no coverage can't collapse to a huge low-zoom
     * tile — the scale rule — and clip-to-view keeps the coarse parent's geometry
     * from spilling outside the downloaded area.
     */
    private static final int MAX_OVERZOOM_STEPS = 6;

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

    /**
     * Shown in the download notification + log so it is always self-evident WHICH
     * build JOSM actually loaded (JOSM only reads plugin jars at startup — a
     * stale jar silently runs old code otherwise). Bump on behavior changes.
     */
    private static final String BUILD_TAG = "b6-ribbons";

    /** REQUESTED tiles already fetched (key = facing/z/x/y), so re-downloading an
     * overlapping area skips work. Only sound because stored content is clipped
     * per requested tile (never to the view) — a skipped tile never hides data.
     * Concurrent: read + written on the loader thread, cleared on the EDT. */
    private final Set<String> loadedTileKeys = ConcurrentHashMap.newKeySet();

    /** Keys of every stored feature (see {@link #featureKey}) for cross-tile,
     * cross-download dedup in {@link #merge}. EDT-mutated; cleared with coverage. */
    private final Set<String> storedFeatureKeys = ConcurrentHashMap.newKeySet();

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
    /** Perpendicular spacing (px) between adjacent facings' ribbons. The four
     * drive facings share ONE physical GPS trace (one vehicle, four cameras),
     * so their geometries are coincident; drawn stacked, the last/densest facing
     * (right) hides the rest. Offsetting each facing sideways by a multiple of
     * this step renders them as parallel colored ribbons — order-independent,
     * every facing stays visible regardless of density. Sized just over the
     * casing width (LINE_WIDTH + 2) so colored cores separate cleanly. */
    private static final float RIBBON_STEP_PX = 4.5f;
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

        // TWO passes so no facing's colour is buried under another facing's black
        // casing where they overlap (facings share a vehicle's GPS trace and can
        // be dense — e.g. right >> front here). Pass 1: all black casings/outlines.
        // Pass 2: all coloured lines/points on top.
        for (Map.Entry<String, List<ImageryFeature>> entry : featuresByFacing.entrySet()) {
            if (!enabledFacings.contains(entry.getKey())) {
                continue;
            }
            float offsetPx = facingOffsetPx(entry.getKey());
            for (ImageryFeature feature : entry.getValue()) {
                paintCasing(g, mv, feature, offsetPx);
            }
        }
        for (Map.Entry<String, List<ImageryFeature>> entry : featuresByFacing.entrySet()) {
            if (!enabledFacings.contains(entry.getKey())) {
                continue;
            }
            Color color = FacingStyle.colorFor(entry.getKey());
            float offsetPx = facingOffsetPx(entry.getKey());
            for (ImageryFeature feature : entry.getValue()) {
                paintColor(g, mv, feature, color, offsetPx);
            }
        }

        // Selected-feature highlight drawn last, on top of everything.
        if (lastNearestFeature != null && lastNearestPoint != null
                && enabledFacings.contains(lastNearestFeature.getFacing())) {
            paintHighlight(g, mv);
        }
    }

    /** Per-facing sideways offset (px) applied perpendicular to travel, so the
     * four coincident drive facings render as parallel ribbons instead of
     * stacking. Symmetric about the true trace; {@code still} (loose points)
     * stays centered. See {@link #RIBBON_STEP_PX}. */
    private static float facingOffsetPx(String facing) {
        switch (facing) {
            case FacingStyle.FRONT:      return -1.5f * RIBBON_STEP_PX;
            case FacingStyle.LEFT:       return -0.5f * RIBBON_STEP_PX;
            case FacingStyle.RIGHT:      return  0.5f * RIBBON_STEP_PX;
            case FacingStyle.FACING_360: return  1.5f * RIBBON_STEP_PX;
            default:                     return  0f; // still
        }
    }

    /**
     * Project a feature's lon/lat vertices to screen, then shift each vertex
     * {@code offsetPx} perpendicular to the local travel direction. Coincident
     * facings thus separate into parallel ribbons; a zero offset returns the
     * unshifted projection. For a lone point (no direction) the offset is applied
     * horizontally so stacked single-image markers still fan out.
     */
    private Point[] toScreen(MapView mv, ImageryFeature feature, float offsetPx) {
        List<double[]> pts = feature.getPoints();
        Point[] base = new Point[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            double[] lonLat = pts.get(i);
            base[i] = mv.getPoint(new LatLon(lonLat[1], lonLat[0]));
        }
        if (offsetPx == 0f || base.length == 0) {
            return base;
        }
        if (base.length == 1) {
            return new Point[]{new Point(base[0].x + Math.round(offsetPx), base[0].y)};
        }
        Point[] out = new Point[base.length];
        for (int i = 0; i < base.length; i++) {
            // Local direction from the neighboring segment(s): the incoming +
            // outgoing chord at interior vertices, the single segment at ends.
            double dx;
            double dy;
            if (i == 0) {
                dx = base[1].x - base[0].x;
                dy = base[1].y - base[0].y;
            } else if (i == base.length - 1) {
                dx = base[i].x - base[i - 1].x;
                dy = base[i].y - base[i - 1].y;
            } else {
                dx = base[i + 1].x - base[i - 1].x;
                dy = base[i + 1].y - base[i - 1].y;
            }
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) {
                out[i] = new Point(base[i].x, base[i].y);
            } else {
                // Left-hand normal (-dy, dx), normalized, scaled by the offset.
                double nx = -dy / len;
                double ny = dx / len;
                out[i] = new Point(
                        (int) Math.round(base[i].x + nx * offsetPx),
                        (int) Math.round(base[i].y + ny * offsetPx));
            }
        }
        return out;
    }

    /** Pass 1: black casing under the line + black outline under each point, so
     * light colours (esp. white front) stay visible — mirrors the viewer. */
    private void paintCasing(Graphics2D g, MapView mv, ImageryFeature feature, float offsetPx) {
        Point[] screen = toScreen(mv, feature, offsetPx);
        g.setColor(Color.BLACK);
        if (screen.length > 1) {
            g.setStroke(new BasicStroke(LINE_WIDTH + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawPolyline(g, screen);
        }
        int r = POINT_RADIUS + 1;
        for (Point p : screen) {
            g.fillOval(p.x - r, p.y - r, 2 * r, 2 * r);
        }
    }

    /** Pass 2: the facing-coloured line + point on top of the casing. */
    private void paintColor(Graphics2D g, MapView mv, ImageryFeature feature, Color color, float offsetPx) {
        Point[] screen = toScreen(mv, feature, offsetPx);
        g.setColor(color);
        if (screen.length > 1) {
            g.setStroke(new BasicStroke(LINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawPolyline(g, screen);
        }
        int r = POINT_RADIUS;
        for (Point p : screen) {
            g.fillOval(p.x - r, p.y - r, 2 * r, 2 * r);
        }
    }

    private static void drawPolyline(Graphics2D g, Point[] pts) {
        for (int i = 1; i < pts.length; i++) {
            g.drawLine(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y);
        }
    }

    /**
     * Move the selection highlight to a specific sequence frame — called by the
     * image dialog as the user walks the sequence (prev/next), so the on-map
     * marker tracks the image currently shown. EDT only.
     */
    public void highlightFrame(ImageryFeature frame) {
        if (frame == null || frame.getPoints().isEmpty()) {
            return;
        }
        lastNearestFeature = frame;
        lastNearestPoint = frame.getPoints().get(0);
        invalidate();
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
        storedFeatureKeys.clear();
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
                // Per-run cache of decoded (possibly ancestor) tiles: one decode
                // serves every requested tile that overzooms onto it — each
                // requested tile then clips ITS OWN slice out of it. Misses (null)
                // are cached too, so absent z16 tiles aren't re-probed per sibling.
                // Deliberately LOCAL to this run: since content is clipped per
                // REQUESTED tile (never to the view), the persistent ledger's
                // "tile done" is always the whole truth, and a later adjacent
                // download simply re-decodes the ancestor for its own tiles.
                Map<String, List<ImageryFeature>> ancestorCache = new HashMap<>();
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
                                acc.addAll(loadWithOverzoom(facing, zoom, tx, ty, ancestorCache));
                                // Record the REQUESTED tile persistently so a later
                                // download skips it. Safe ONLY because what we store
                                // is the tile's FULL within-tile content (clipped to
                                // the tile, never the view) — skipping loses nothing.
                                // (Clipping to the VIEW here previously left
                                // permanent holes: an overlapping later download
                                // skipped the tile whose out-of-old-view content had
                                // been discarded — the "only an edge strip renders"
                                // bug.)
                                loadedTileKeys.add(key);
                            } catch (IOException ioe) {
                                Logging.warn("Maprizon: tile fetch failed " + key + ": " + ioe.getMessage());
                            }
                        }
                    }
                    fetched.put(facing, acc);
                }

                final int reqZoom = zoom;
                SwingUtilities.invokeLater(() -> {
                    // Merge dedups (a line spans many tiles); report what was ADDED
                    // per facing, so a download states exactly what it contributed.
                    Map<String, Integer> addedByFacing = merge(fetched);
                    invalidate();
                    int added = 0;
                    StringBuilder perFacing = new StringBuilder();
                    for (String f : FacingStyle.ALL_FACINGS) {
                        Integer n = addedByFacing.get(f);
                        if (n != null) {
                            perFacing.append(f).append("=").append(n).append("  ");
                            added += n;
                        }
                    }
                    Logging.info("Maprizon " + BUILD_TAG + " download z" + reqZoom + " added: " + perFacing);
                    if (userInitiated) {
                        new Notification("<html><b>Maprizon download</b> (z" + reqZoom + ", " + BUILD_TAG + ")<br>"
                                + perFacing + "</html>")
                                .setDuration(Notification.TIME_LONG).show();
                    }
                    if (userInitiated && added == 0) {
                        JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                                "No new Maprizon coverage was added for this view.\n" +
                                        "Already-downloaded tiles are skipped — use \"Clear downloaded\n" +
                                        "coverage\" (layer menu) to refetch, or try a different area.",
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

    /**
     * Merge freshly fetched features into the accumulated store, deduplicating
     * against everything already stored. Duplicates are normal now: a sequence
     * line spanning several requested tiles is clipped-in by EACH of them, and a
     * later download can re-decode the same coarse ancestor for its own tiles.
     * Returns the number of features actually ADDED per facing. EDT only.
     * (The tile ledger {@code loadedTileKeys} is updated on the loader thread as
     * tiles are fetched, not here — see {@link #submitDownload}.)
     */
    private Map<String, Integer> merge(Map<String, List<ImageryFeature>> fetched) {
        Map<String, List<ImageryFeature>> next = new HashMap<>(featuresByFacing);
        Map<String, Integer> addedByFacing = new LinkedHashMap<>();
        for (Map.Entry<String, List<ImageryFeature>> e : fetched.entrySet()) {
            List<ImageryFeature> combined = new ArrayList<>(
                    next.getOrDefault(e.getKey(), Collections.emptyList()));
            int added = 0;
            for (ImageryFeature f : e.getValue()) {
                if (f.getPoints().isEmpty()) {
                    continue;
                }
                if (storedFeatureKeys.add(featureKey(e.getKey(), f))) {
                    combined.add(f);
                    added++;
                }
            }
            next.put(e.getKey(), combined);
            addedByFacing.put(e.getKey(), added);
        }
        featuresByFacing = next;
        return addedByFacing;
    }

    /** Identity of a feature across tiles and downloads: facing + sequence id +
     * vertex count + first/last vertex (rounded to ~1cm). Two clips of the same
     * decoded geometry always produce the same key. */
    private static String featureKey(String facing, ImageryFeature f) {
        List<double[]> pts = f.getPoints();
        double[] a = pts.get(0);
        double[] z = pts.get(pts.size() - 1);
        return facing + '|' + f.getSequenceId() + '|' + pts.size() + '|'
                + Math.round(a[0] * 1e7) + ',' + Math.round(a[1] * 1e7) + '|'
                + Math.round(z[0] * 1e7) + ',' + Math.round(z[1] * 1e7);
    }

    /**
     * Fetch one requested tile's features with BOUNDED overzoom: if the tile is
     * absent at the requested zoom, walk up to parent tiles (z-1, z-2, …) until
     * one exists, or the step budget / archive-min-zoom floor is reached. This is
     * what makes zoomed-in views render — the archives declare maxZoom=16 but only
     * bake to z15, so the requested z16 tile is absent and we fall back to z15.
     *
     * <p>Decodes each level's tile ONCE per download via {@code ancestorCache}
     * (misses cached as null), clips each present level's features to the
     * REQUESTED tile's own bounds, and keeps the level with the MOST in-tile
     * features. "First present tile wins" is NOT safe: a facing can have a
     * present-but-nearly-empty fine tile shadowing a dense coarser one (observed
     * live: front z13 tile = 16 features while its z12 parent = 3188 — stopping
     * at z13 rendered crumbs and made the facing look missing). Duplicates from
     * a line spanning several requested tiles are collapsed later in
     * {@link #merge}.
     */
    private List<ImageryFeature> loadWithOverzoom(String facing, int zoom, int tx, int ty,
                                                  Map<String, List<ImageryFeature>> ancestorCache) throws IOException {
        int az = zoom;
        int ax = tx;
        int ay = ty;
        int minZoom = archiveMinZoom();
        List<ImageryFeature> best = Collections.emptyList();
        for (int step = 0; step <= MAX_OVERZOOM_STEPS && az >= minZoom; step++) {
            String ancestorKey = tileKey(facing, az, ax, ay);
            List<ImageryFeature> features;
            if (ancestorCache.containsKey(ancestorKey)) {
                features = ancestorCache.get(ancestorKey); // may be a cached MISS (null)
            } else {
                features = loader.loadTileOrNull(facing, az, ax, ay);
                ancestorCache.put(ancestorKey, features);
            }
            if (features != null) {
                List<ImageryFeature> clipped = clipToTile(features, zoom, tx, ty);
                // Strictly-greater: on ties the DEEPER level (seen first) wins,
                // favoring finer geometry.
                if (clipped.size() > best.size()) {
                    best = clipped;
                }
            }
            // Continue up the chain regardless — a coarser level may be denser.
            az -= 1;
            ax >>= 1;
            ay >>= 1;
        }
        return best;
    }

    /** Bounds of slippy tile (zoom, x, y) as {west, south, east, north}. */
    private static double[] tileBounds(int zoom, int x, int y) {
        double west = TileMath.tileLocalXToLon(x, zoom, 0, 1);
        double east = TileMath.tileLocalXToLon(x, zoom, 1, 1);
        double north = TileMath.tileLocalYToLat(y, zoom, 0, 1);
        double south = TileMath.tileLocalYToLat(y, zoom, 1, 1);
        return new double[]{west, south, east, north};
    }

    /**
     * Keep only features whose geographic extent intersects the REQUESTED tile.
     * Overzoom serves a (much coarser) ancestor tile covering far more ground
     * than the requested tile; without clipping, its features would spill far
     * outside the downloaded area. Clipping to the requested tile (rather than
     * the view) keeps the persistent tile ledger truthful: "tile done" always
     * means its full within-tile content is stored, so overlapping later
     * downloads can safely skip it. Bbox-intersection (not vertex containment)
     * so a sequence line crossing the tile is kept; the resulting duplicates
     * across adjacent tiles are collapsed in {@link #merge}.
     */
    private static List<ImageryFeature> clipToTile(List<ImageryFeature> features, int zoom, int x, int y) {
        double[] b = tileBounds(zoom, x, y);
        double minLon = b[0];
        double minLat = b[1];
        double maxLon = b[2];
        double maxLat = b[3];
        List<ImageryFeature> kept = new ArrayList<>(features.size());
        for (ImageryFeature f : features) {
            double fMinLon = Double.MAX_VALUE;
            double fMaxLon = -Double.MAX_VALUE;
            double fMinLat = Double.MAX_VALUE;
            double fMaxLat = -Double.MAX_VALUE;
            for (double[] p : f.getPoints()) {
                fMinLon = Math.min(fMinLon, p[0]);
                fMaxLon = Math.max(fMaxLon, p[0]);
                fMinLat = Math.min(fMinLat, p[1]);
                fMaxLat = Math.max(fMaxLat, p[1]);
            }
            boolean intersects = fMaxLon >= minLon && fMinLon <= maxLon
                    && fMaxLat >= minLat && fMinLat <= maxLat;
            if (intersects) {
                kept.add(f);
            }
        }
        return kept;
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
            // Double-click still opens the web viewer (deep link) as a convenience.
            openInMaprizon();
        } else {
            // Single click shows the actual image IN JOSM (Phase 1 image viewer).
            MaprizonImageDialog dialog = MaprizonImageDialog.getInstance();
            if (dialog != null) {
                dialog.showForClickedFeature(nearest.feature, nearest.point, this);
            } else {
                // Dialog not registered (no map frame yet) — fall back to a hint.
                new Notification("<html><b>Maprizon:</b> " + nearest.feature.getFacing()
                        + "<br><i>double-click to open in Maprizon</i></html>")
                        .setDuration(Notification.TIME_SHORT).show();
            }
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

        // Optional login: unlocks private imagery (signed image bytes + private
        // sequences). Logged-out is the default and everything else works without it.
        ViewerAuth auth = ViewerAuth.getInstance();
        if (auth.isLoggedIn()) {
            String who = auth.email().isEmpty() ? "" : " (" + auth.email() + ")";
            actions.add(new AbstractAction("Log out of Viewer" + who) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    auth.logout();
                    invalidate();
                }
            });
        } else {
            actions.add(new AbstractAction("Log in to Viewer (view private imagery)") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    LoginFlow.start(MaprizonLayer.this::invalidate);
                }
            });
        }
        actions.add(Layer.SeparatorLayerAction.INSTANCE);

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
