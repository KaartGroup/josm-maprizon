// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
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
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
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

    /**
     * Fraction of the max per-level clipped feature count that a level must reach
     * to be considered "fully populated" and thus eligible to win. Used by
     * {@link #loadWithOverzoom} to prefer the FINEST such level (crisp geometry)
     * over a coarser ancestor that carries only marginally more features. Set high
     * (favor fine geometry) because in practice the finest requested zoom is fully
     * baked; the guard only defends the pathological near-empty-fine-tile case.
     * Tunable.
     */
    private static final double FINE_ENOUGH_FRACTION = 0.6;

    /**
     * Display slippy zoom at/above which the layer is in "detail" mode: every
     * enabled facing renders, but ONLY at its finest fetched resolution, and
     * auto-download is allowed. Below it ("overview", zoomed out) a single facing
     * renders and auto-download is suppressed. Chosen as roughly the zoom where
     * individual image points separate on screen. Tunable.
     */
    private static final int USABLE_ZOOM = 16;

    /** The one facing shown in overview (zoomed-out) mode — 360 is the most
     * representative of overall coverage. */
    private static final String OVERVIEW_FACING = FacingStyle.FACING_360;

    /** Debounce (ms) after the view settles before an auto-download fires; further
     * movement within the window resets it (mirrors JOSM's ContinuousDownload). */
    private static final int AUTO_DEBOUNCE_MS = 400;

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
    private static final String BUILD_TAG = "1.0.1";

    /** Set true right after a download merges, so the NEXT paint logs a one-shot
     * snapshot of what is actually on screen (per facing: total + in-view). */
    private volatile boolean diagPaintPending = false;

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
    /** Longitude span of the last auto-load view, used to tell a PAN (extent
     * unchanged, centre moved) from a ZOOM (extent changed). Rounded integer zoom
     * levels were too coarse — a small wheel-zoom keeps the same rounded zoom yet
     * recentres toward the cursor, which read as a pan and triggered a needless
     * download. -1 = none yet. */
    private volatile double lastAutoLonSpan = -1;

    /** Finest tile zoom among all stored features (-1 = none yet). In detail mode
     * the paint filter shows only features at this zoom, hiding coarse overzoom
     * leftovers. EDT-updated in {@link #merge}; volatile so paint reads it safely. */
    private volatile int maxStoredSourceZoom = -1;

    // Debounced auto-download (opt-in live mode). All EDT.
    private Timer autoDebounceTimer;
    private Bounds pendingAutoBounds;
    private int pendingAutoWidth;

    // Loading-spinner animation. All EDT.
    private Timer busyTicker;
    private int spinnerTick;

    private LatLon lastClickLatLon;
    private ImageryFeature lastNearestFeature;
    private double[] lastNearestPoint;
    /** Camera bearing (deg, clockwise from north) orienting the selected frame's
     * view cone; null = unknown (no wedge). {@link #cone360} true = draw a ring
     * (panoramic) instead of a wedge. Set via {@link #highlightFrame}. */
    private Double coneBearingDeg;
    private boolean cone360;

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
    /** View-cone field-of-view spread (degrees) for the selected image's camera
     * wedge, and its on-screen radius (px). Mirrors the viewer's cone (which bakes
     * the angle into a sprite); here it is drawn vectorially. Tunable. */
    private static final double CONE_FOV_DEG = 60.0;
    private static final int CONE_RADIUS_PX = 40;

    /** Login/logout flips the tile scope (public bake vs the org's private bake),
     * so accumulated coverage + the tile ledger are stale — drop them so the next
     * download pulls from the correct bake. */
    private final Runnable authListener = () -> SwingUtilities.invokeLater(this::clearCoverage);

    public MaprizonLayer() {
        super("Maprizon Coverage");
        enabledFacings.addAll(FacingStyle.ALL_FACINGS);
        ViewerAuth.getInstance().addLoginStateListener(authListener);
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
        ViewerAuth.getInstance().removeLoginStateListener(authListener);
        if (autoDebounceTimer != null) {
            autoDebounceTimer.stop();
        }
        if (busyTicker != null) {
            busyTicker.stop();
        }
        loadExecutor.shutdownNow();
        loader.close();
        super.destroy();
    }

    // ---------------------------------------------------------------- paint

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        // Current display zoom decides level-of-detail: below USABLE_ZOOM we are in
        // "overview" (zoomed out); at/above it in "detail" (zoomed in enough to see
        // individual image points).
        int rawZoom = rawScreenZoom(bbox, mv.getWidth());
        boolean detailMode = rawZoom >= USABLE_ZOOM;
        int maxSourceZoom = maxStoredSourceZoom;

        // Live mode (opt-in): re-download when the view meaningfully moved/zoomed —
        // but ONLY in detail mode (so a zoomed-out pan never triggers a coarse
        // mass-load) and DEBOUNCED (a continuous pan fires one load when it settles,
        // not a storm mid-drag). Same fetch path as an explicit download, minus the
        // area-too-large prompt.
        if (autoRefresh && detailMode && !loading.get() && autoViewChanged(bbox, mv)) {
            scheduleAutoDownload(bbox, mv.getWidth());
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
                if (!lodVisible(feature, detailMode, maxSourceZoom)) {
                    continue;
                }
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
                if (!lodVisible(feature, detailMode, maxSourceZoom)) {
                    continue;
                }
                paintColor(g, mv, feature, color, offsetPx);
            }
        }

        // Selected-feature highlight drawn last, on top of everything.
        if (lastNearestFeature != null && lastNearestPoint != null
                && enabledFacings.contains(lastNearestFeature.getFacing())) {
            paintHighlight(g, mv);
        }

        // Loading feedback: animated spinner + label while a download runs (explicit
        // or debounced auto). ensureBusyTicker drives the repaint that animates it,
        // and stops itself when idle.
        boolean busy = loading.get();
        ensureBusyTicker(busy);
        if (busy) {
            paintSpinner(g);
        }

        // One-shot post-download snapshot: exactly what's on screen right now,
        // per facing — total stored vs how many actually fall inside THIS view.
        // The gap between "stored total" and "inView" is the whole diagnosis:
        // stored>0 but inView=0 => coverage landed outside the view (placement);
        // inView>0 but you see nothing => a render problem.
        if (diagPaintPending) {
            diagPaintPending = false;
            diag(String.format(Locale.ROOT, "PAINT view lon[%.6f..%.6f] lat[%.6f..%.6f]",
                    bbox.getMinLon(), bbox.getMaxLon(), bbox.getMinLat(), bbox.getMaxLat()));
            for (String f : FacingStyle.ALL_FACINGS) {
                List<ImageryFeature> feats = featuresByFacing.getOrDefault(f, Collections.emptyList());
                int inView = 0;
                for (ImageryFeature feat : feats) {
                    boolean hit = false;
                    for (double[] p : feat.getPoints()) {
                        if (p[0] >= bbox.getMinLon() && p[0] <= bbox.getMaxLon()
                                && p[1] >= bbox.getMinLat() && p[1] <= bbox.getMaxLat()) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit) {
                        inView++;
                    }
                }
                diag("  paint facing=" + f + " enabled=" + enabledFacings.contains(f)
                        + " total=" + feats.size() + " inView=" + inView);
            }
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
    public void highlightFrame(ImageryFeature frame, Double bearingDeg, boolean is360) {
        if (frame == null || frame.getPoints().isEmpty()) {
            return;
        }
        lastNearestFeature = frame;
        lastNearestPoint = frame.getPoints().get(0);
        coneBearingDeg = bearingDeg;
        cone360 = is360;
        invalidate();
    }

    /** Live-update the view cone's bearing (deg CW from north) for the selected
     * 360 as the user looks around its panorama; no-op unless a 360 is selected. */
    public void setViewConeBearing(double deg) {
        if (!cone360) {
            return;
        }
        coneBearingDeg = deg;
        invalidate();
    }

    /** Draw a prominent ring + facing-coloured dot at the currently selected
     * feature's clicked point, so a click gives visible confirmation of what is
     * selected (and thus what "View in Maprizon" / double-click will open). */
    private void paintHighlight(Graphics2D g, MapView mv) {
        Point p = mv.getPoint(new LatLon(lastNearestPoint[1], lastNearestPoint[0]));
        // View cone first, so the selection ring/dot stay on top of it.
        paintViewCone(g, p);
        int r = SELECTED_RADIUS;
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(Color.WHITE);
        g.drawOval(p.x - r - 1, p.y - r - 1, 2 * (r + 1), 2 * (r + 1));
        g.setColor(FacingStyle.colorFor(lastNearestFeature.getFacing()));
        g.drawOval(p.x - r, p.y - r, 2 * r, 2 * r);
        g.fillOval(p.x - POINT_RADIUS, p.y - POINT_RADIUS, 2 * POINT_RADIUS, 2 * POINT_RADIUS);
    }

    /**
     * Draw the selected image's camera view cone: a facing-coloured translucent
     * wedge centred on the frame's heading (or a full ring for 360), with a dark
     * halo so it reads over any imagery — mirrors the viewer's cone. No-op when no
     * frame with a known orientation is selected.
     */
    private void paintViewCone(Graphics2D g, Point p) {
        if (lastNearestFeature == null) {
            return;
        }
        Color base = FacingStyle.colorFor(lastNearestFeature.getFacing());
        int r = CONE_RADIUS_PX;
        if (cone360) {
            // Omnidirectional ring for the 360...
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 70));
            g.fillOval(p.x - r, p.y - r, 2 * r, 2 * r);
            g.setColor(new Color(0, 0, 0, 120));
            g.setStroke(new BasicStroke(3f));
            g.drawOval(p.x - r, p.y - r, 2 * r, 2 * r);
            g.setColor(base);
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(p.x - r, p.y - r, 2 * r, 2 * r);
            // ...plus a wedge tracking where the user is currently looking inside the
            // panorama (updated live via setViewConeBearing as they drag).
            if (coneBearingDeg != null) {
                paintWedge(g, p, base, r);
            }
            return;
        }
        if (coneBearingDeg != null) {
            paintWedge(g, p, base, r);
        }
    }

    /** Filled facing-coloured FOV wedge from {@code p}, centred on
     * {@link #coneBearingDeg}, with a dark halo so it reads over imagery/the ring. */
    private void paintWedge(Graphics2D g, Point p, Color base, int r) {
        double br = Math.toRadians(coneBearingDeg);
        double half = Math.toRadians(CONE_FOV_DEG / 2.0);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(p.x, p.y);
        int steps = 12;
        for (int i = 0; i <= steps; i++) {
            double a = br - half + (2 * half) * i / steps;
            // Compass bearing: 0 deg = north (screen up = -y), increasing clockwise.
            path.lineTo(p.x + r * Math.sin(a), p.y - r * Math.cos(a));
        }
        path.closePath();
        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 150));
        g.fill(path);
        g.setColor(new Color(0, 0, 0, 120));
        g.setStroke(new BasicStroke(3f));
        g.draw(path);
        g.setColor(base);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(path);
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
        lastAutoLonSpan = -1;
        maxStoredSourceZoom = -1;
        if (autoDebounceTimer != null) {
            autoDebounceTimer.stop();
        }
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
        // Kick one repaint so the loading spinner appears immediately: an explicit
        // download over a static view produces no map movement to trigger paint(),
        // so without this the spinner (and its self-sustaining animation ticker)
        // wouldn't start until the download had already finished.
        SwingUtilities.invokeLater(this::invalidate);
        loadExecutor.submit(() -> {
            try {
                refreshZoomBounds(); // blocking header read, but on the loader thread
                // Request at the screen-matched zoom clamped to the archive range.
                // We deliberately do NOT drop this to "the deepest zoom with a tile
                // at the view centre" (the old cappedZoom): that single-point probe
                // collapsed the WHOLE view to a coarse zoom whenever the centre pixel
                // sat in a small coverage gap, and since per-tile overzoom only walks
                // UP (coarser), the fine geometry was then unreachable — the jagged
                // tracks. Instead we request the fine zoom (header advertises 16; the
                // finest tiles actually baked are z15) and let loadWithOverzoom fetch
                // the native z15 tile per requested tile: full-resolution geometry,
                // clipped losslessly by bbox, matching what the web viewer renders.
                int zoom = screenZoom(view, widthPx);
                diagReset("==== MAPRIZON DOWNLOAD " + BUILD_TAG + " ====");
                diag(String.format(Locale.ROOT,
                        "view lon[%.6f..%.6f] lat[%.6f..%.6f] widthPx=%d -> zoom=%d archiveZoom[%d..%d] enforceBudget=%b enabled=%s",
                        view.getMinLon(), view.getMaxLon(), view.getMinLat(), view.getMaxLat(),
                        widthPx, zoom, archiveMinZoom(), archiveMaxZoom(), enforceBudget, enabledFacings));
                Map<String, int[]> rangesByFacing = new LinkedHashMap<>();
                long totalNewTiles = 0;
                for (String facing : FacingStyle.ALL_FACINGS) {
                    if (!enabledFacings.contains(facing)) {
                        diag("plan facing=" + facing + " SKIPPED (disabled)");
                        continue;
                    }
                    int[] r = tileRange(view, zoom);
                    rangesByFacing.put(facing, r);
                    long tiles = (long) (r[1] - r[0] + 1) * (r[3] - r[2] + 1);
                    long newTiles = countNewTiles(facing, zoom, r);
                    // Geographic extent the requested tile range covers, to compare
                    // against the view bbox above (should match closely).
                    double[] nw = tileBounds(zoom, r[0], r[2]); // {west,south,east,north}
                    double[] se = tileBounds(zoom, r[1], r[3]);
                    diag(String.format(Locale.ROOT,
                            "plan facing=%s z=%d tileX[%d..%d] tileY[%d..%d] tiles=%d new=%d covers lon[%.6f..%.6f] lat[%.6f..%.6f]",
                            facing, zoom, r[0], r[1], r[2], r[3], tiles, newTiles,
                            nw[0], se[2], se[1], nw[3]));
                    totalNewTiles += newTiles;
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
                                List<ImageryFeature> tileFeats =
                                        loadWithOverzoom(facing, zoom, tx, ty, ancestorCache);
                                acc.addAll(tileFeats);
                                // Record the REQUESTED tile persistently so a later
                                // download skips it — but ONLY if it actually yielded
                                // features. An empty result must be retried on a later
                                // download, not skipped forever.
                                if (!tileFeats.isEmpty()) {
                                    loadedTileKeys.add(key);
                                }
                            } catch (IOException ioe) {
                                Logging.warn("Maprizon: tile fetch failed " + key + ": " + ioe.getMessage());
                            }
                        }
                    }
                    fetched.put(facing, acc);
                    diag("fetched facing=" + facing + " " + bboxStr(acc));
                }

                final int reqZoom = zoom;
                SwingUtilities.invokeLater(() -> {
                    // Merge dedups (a line spans many tiles); report what was ADDED
                    // per facing, so a download states exactly what it contributed.
                    Map<String, Integer> addedByFacing = merge(fetched);
                    for (String f : FacingStyle.ALL_FACINGS) {
                        List<ImageryFeature> stored = featuresByFacing.getOrDefault(f, Collections.emptyList());
                        diag("stored facing=" + f + " added=" + addedByFacing.getOrDefault(f, 0)
                                + " total=" + stored.size() + " " + bboxStr(stored));
                    }
                    diagPaintPending = true; // next paint logs the on-screen snapshot
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
                    if (f.getSourceZoom() != ImageryFeature.NATIVE_ZOOM
                            && f.getSourceZoom() > maxStoredSourceZoom) {
                        maxStoredSourceZoom = f.getSourceZoom();
                    }
                }
            }
            next.put(e.getKey(), combined);
            addedByFacing.put(e.getKey(), added);
        }
        featuresByFacing = next;
        return addedByFacing;
    }

    /** Identity of a feature across tiles and downloads: facing + sequence id +
     * vertex count + a hash of all vertices (rounded to ~1cm). Two clips of the
     * same decoded geometry always produce the same key. */
    private static String featureKey(String facing, ImageryFeature f) {
        List<double[]> pts = f.getPoints();
        // Hash EVERY vertex (not just first/last), so two distinct features that
        // share facing + sequence id + vertex count + endpoints but differ in the
        // middle cannot collide and silently dedup one away.
        long h = 1469598103934665603L; // FNV-1a 64-bit offset basis
        for (double[] p : pts) {
            h = (h ^ Math.round(p[0] * 1e7)) * 1099511628211L;
            h = (h ^ Math.round(p[1] * 1e7)) * 1099511628211L;
        }
        return facing + '|' + f.getSequenceId() + '|' + pts.size() + '|' + h;
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
    /** Start-of-download trace marker. Goes to JOSM's debug log (off by default),
     * not a file in the user's home dir. */
    private static void diagReset(String header) {
        Logging.debug("MAPRIZON-DIAG " + header);
    }

    /** One download/paint trace line, to JOSM's debug log. */
    private static void diag(String msg) {
        Logging.debug("MAPRIZON-DIAG " + msg);
    }

    /** Feature count + total vertex count + the geographic bbox the features
     * actually plot into — the key signal for "coverage landed miles from view". */
    private static String bboxStr(List<ImageryFeature> feats) {
        if (feats == null || feats.isEmpty()) {
            return "EMPTY";
        }
        double minLon = 1e9;
        double minLat = 1e9;
        double maxLon = -1e9;
        double maxLat = -1e9;
        long pts = 0;
        for (ImageryFeature f : feats) {
            for (double[] p : f.getPoints()) {
                minLon = Math.min(minLon, p[0]);
                maxLon = Math.max(maxLon, p[0]);
                minLat = Math.min(minLat, p[1]);
                maxLat = Math.max(maxLat, p[1]);
                pts++;
            }
        }
        return String.format(Locale.ROOT, "n=%d pts=%d lon[%.6f..%.6f] lat[%.6f..%.6f]",
                feats.size(), pts, minLon, maxLon, minLat, maxLat);
    }

    private List<ImageryFeature> loadWithOverzoom(String facing, int zoom, int tx, int ty,
                                                  Map<String, List<ImageryFeature>> ancestorCache) throws IOException {
        int az = zoom;
        int ax = tx;
        int ay = ty;
        int minZoom = archiveMinZoom();
        // Collect every present level's clip (finest -> coarsest order), then
        // choose. We can't decide during the walk: the choice depends on the max
        // clipped count across ALL levels (see selection rule below).
        List<int[]> levelZoomHolder = new ArrayList<>();   // each: {az}
        List<List<ImageryFeature>> levelClipped = new ArrayList<>();
        StringBuilder presence = new StringBuilder(); // per-level: zN=raw/clip or zN=.
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
                presence.append(" z").append(az).append('=').append(features.size())
                        .append('/').append(clipped.size());
                levelZoomHolder.add(new int[]{az});
                levelClipped.add(clipped);
            } else {
                presence.append(" z").append(az).append("=.");
            }
            // Continue up the chain regardless — a coarser level may be denser.
            az -= 1;
            ax >>= 1;
            ay >>= 1;
        }
        // Selection rule: keep the FINEST (deepest) level that carries essentially
        // all of the data — the deepest level whose clipped count is at least
        // FINE_ENOUGH_FRACTION of the max clipped count across levels. Tippecanoe
        // simplifies line geometry at coarse zooms, so a coarse ancestor's lines
        // are decimated; serving them overzoomed produces the ragged zigzags we
        // saw. A bare "most clipped features wins" rule would drag a facing down
        // to a coarse level for a trivial one- or two-feature margin (observed:
        // front z13=37 vs z10=38 clipped -> picked z10, jagged). The fraction keeps
        // fine geometry in that common case while still falling back to a denser
        // coarse parent if the fine tile is genuinely near-empty (the guarded
        // case: fine z13=16 vs z12=3188 -> z12 still wins).
        int maxClipped = 0;
        for (List<ImageryFeature> c : levelClipped) {
            maxClipped = Math.max(maxClipped, c.size());
        }
        double floor = FINE_ENOUGH_FRACTION * maxClipped;
        List<ImageryFeature> best = Collections.emptyList();
        int bestZoom = -1;
        for (int i = 0; i < levelClipped.size(); i++) {
            List<ImageryFeature> clipped = levelClipped.get(i);
            // levelClipped is finest -> coarsest, so the first qualifier is finest.
            if (!clipped.isEmpty() && clipped.size() >= floor) {
                best = clipped;
                bestZoom = levelZoomHolder.get(i)[0];
                break;
            }
        }
        // Per requested tile: which zoom levels had a tile (raw/clipped counts),
        // which level won, and how much survived the clip. Reveals coarse-facing
        // clip-to-zero and any placement surprises. One line per fetched tile.
        diag("  ozoom facing=" + facing + " req z" + zoom + "/" + tx + "/" + ty
                + " levels[" + presence.toString().trim() + "] wonZ=" + bestZoom + " kept=" + best.size());
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
        return Math.max(archiveMinZoom(), Math.min(archiveMaxZoom(), rawScreenZoom(view, widthPx)));
    }

    /** The screen-matched slippy zoom BEFORE clamping to the archive range — the
     * true on-screen zoom, used for the level-of-detail decision and the auto-load
     * gate (both of which must reason about zoom levels deeper than the archive). */
    private int rawScreenZoom(Bounds view, int widthPx) {
        double lonSpan = Math.max(1e-9, view.getMaxLon() - view.getMinLon());
        int px = Math.max(1, widthPx);
        return (int) Math.round(Math.log(px * 360.0 / (lonSpan * 256.0)) / Math.log(2));
    }

    /**
     * Level-of-detail paint filter. Overview (zoomed out, {@code !detailMode}): only
     * the single {@link #OVERVIEW_FACING} renders, to cut clutter. Detail (zoomed
     * in): every enabled facing renders but ONLY at the finest fetched zoom
     * ({@code maxSourceZoom}), so coarse overzoom geometry stops rendering beneath
     * its full-resolution replacement. {@code maxSourceZoom < 0} means nothing has
     * been stored yet (show whatever exists).
     */
    private boolean lodVisible(ImageryFeature f, boolean detailMode, int maxSourceZoom) {
        if (!detailMode) {
            return OVERVIEW_FACING.equals(f.getFacing());
        }
        return maxSourceZoom < 0 || f.getSourceZoom() >= maxSourceZoom;
    }

    /** (Re)start the debounce timer for an auto-download of the given view; a fresh
     * view change within the window resets it, so a continuous pan fires exactly one
     * load when it settles rather than a storm mid-drag. EDT. */
    private void scheduleAutoDownload(Bounds bbox, int widthPx) {
        pendingAutoBounds = new Bounds(bbox);
        pendingAutoWidth = widthPx;
        if (autoDebounceTimer == null) {
            autoDebounceTimer = new Timer(AUTO_DEBOUNCE_MS, e -> fireAutoDownload());
            autoDebounceTimer.setRepeats(false);
        }
        autoDebounceTimer.restart();
    }

    private void fireAutoDownload() {
        Bounds b = pendingAutoBounds;
        if (b == null || !autoRefresh || loading.get()) {
            return;
        }
        // Re-check the gate at fire time: if the pan ended zoomed out of detail
        // range, don't auto-load coarse data.
        if (rawScreenZoom(b, pendingAutoWidth) < USABLE_ZOOM) {
            return;
        }
        submitDownload(b, pendingAutoWidth, false, false);
    }

    /** Start/stop the repaint ticker that animates the loading spinner. EDT. */
    private void ensureBusyTicker(boolean on) {
        if (on) {
            if (busyTicker == null) {
                busyTicker = new Timer(120, e -> {
                    spinnerTick++;
                    invalidate();
                });
                busyTicker.setRepeats(true);
            }
            if (!busyTicker.isRunning()) {
                busyTicker.start();
            }
        } else if (busyTicker != null && busyTicker.isRunning()) {
            busyTicker.stop();
        }
    }

    /** Small animated "loading" indicator drawn top-left while a download runs, so
     * the layer's busy state is visible. Component (screen) coordinates. */
    private void paintSpinner(Graphics2D g) {
        String msg = "Loading Maprizon coverage…";
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        FontMetrics fm = g.getFontMetrics();
        int spin = 16;
        int pad = 10;
        int gap = 8;
        int textW = fm.stringWidth(msg);
        int boxH = Math.max(spin, fm.getHeight()) + pad;
        int boxW = pad + spin + gap + textW + pad;
        int x = 12;
        int y = 12;
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRoundRect(x, y, boxW, boxH, 12, 12);
        int cx = x + pad;
        int cy = y + (boxH - spin) / 2;
        int start = (spinnerTick * 30) % 360;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(cx, cy, spin, spin, start, 270);
        g.drawString(msg, cx + spin + gap, y + (boxH + fm.getAscent() - fm.getDescent()) / 2);
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

    /** True when the view has PANNED to a new area since the last auto load. A zoom
     * change alone does NOT count: the view centre leaving the last-loaded bounds is
     * a genuine pan, whereas zooming in/out keeps the centre put and the coverage we
     * already downloaded still covers it (the LOD filter handles resolution). */
    private boolean autoViewChanged(Bounds bbox, MapView mv) {
        double lonSpan = bbox.getMaxLon() - bbox.getMinLon();
        LatLon centre = new LatLon(
                (bbox.getMinLat() + bbox.getMaxLat()) / 2.0,
                (bbox.getMinLon() + bbox.getMaxLon()) / 2.0);
        Bounds last = lastAutoBounds;
        if (last == null || lastAutoLonSpan <= 0) {
            // First activation in detail mode: load the current view once.
            lastAutoBounds = new Bounds(bbox);
            lastAutoLonSpan = lonSpan;
            return true;
        }
        // ZOOM = the view extent changed. NEVER triggers a download: a pan keeps the
        // extent essentially bit-identical, whereas any zoom (including sub-integer
        // wheel-zoom that recentres toward the cursor) changes the span. Re-baseline
        // to the new view so the next same-extent pan is still detected — this is
        // also what re-arms auto-load after you zoom out to overview and back in.
        double ratio = lonSpan / lastAutoLonSpan;
        if (ratio < 0.99 || ratio > 1.01) {
            lastAutoBounds = new Bounds(bbox);
            lastAutoLonSpan = lonSpan;
            return false;
        }
        // Same extent → a genuine pan is the view centre leaving the last-loaded box.
        if (!last.contains(centre)) {
            lastAutoBounds = new Bounds(bbox);
            lastAutoLonSpan = lonSpan;
            return true;
        }
        return false;
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
        // No cone until a specific frame (with a heading) is shown by the dialog.
        coneBearingDeg = null;
        cone360 = false;
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
            // Scale to JOSM's standard layer-icon size — the source PNG is 64px so
            // without this the layers list renders it oversized (row-height-tall).
            return new ImageProvider("maprizon").setSize(ImageProvider.ImageSizes.LAYER).get();
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
