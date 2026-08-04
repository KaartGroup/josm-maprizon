// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.data;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One decoded coverage feature (a single point, or a sequence path rendered as a
 * line) read out of a per-facing PMTiles/MVT tile, together with its Viewer
 * properties.
 */
public final class ImageryFeature {

    /**
     * Sentinel {@link #getSourceZoom()} for features that did NOT come from a
     * coverage tile (e.g. the image-dialog frames decoded from a viewer API
     * response). Treated as "always fine" so any such feature that were ever
     * painted survives the detail-mode zoom filter in the layer.
     */
    public static final int NATIVE_ZOOM = Integer.MAX_VALUE;

    /** Ordered {lon, lat} vertex list; size 1 for a point feature. */
    private final List<double[]> points;
    private final Map<String, Object> properties;
    private final String facing;
    /** Slippy zoom of the tile this feature was actually decoded from (the
     * overzoom-resolved ancestor level, e.g. 15 for native fine, 12/13 for a
     * coarse overzoom). Drives the layer's level-of-detail paint filter so coarse
     * geometry and its full-resolution replacement never render on top of each
     * other. {@link #NATIVE_ZOOM} for non-tile features. */
    private final int sourceZoom;
    /**
     * Slippy zoom this feature was REQUESTED at — which, unlike {@link #sourceZoom},
     * identifies the tile grid it was clipped into and therefore the ground it
     * speaks for. The layer's level-of-detail filter needs this rather than the
     * decoded zoom: two tiles requested together at z16 can resolve to different
     * ancestors (z16 here, z15 in a gap), and comparing decoded zooms would throw
     * away the gap's data even though nothing finer exists for it.
     * {@link #NATIVE_ZOOM} for non-tile features.
     */
    private final int requestZoom;

    public ImageryFeature(List<double[]> points, Map<String, Object> properties, String facing) {
        this(points, properties, facing, NATIVE_ZOOM);
    }

    public ImageryFeature(List<double[]> points, Map<String, Object> properties, String facing, int sourceZoom) {
        this(points, properties, facing, sourceZoom, NATIVE_ZOOM);
    }

    public ImageryFeature(List<double[]> points, Map<String, Object> properties, String facing,
                          int sourceZoom, int requestZoom) {
        this.requestZoom = requestZoom;
        this.points = Collections.unmodifiableList(points);
        this.properties = properties == null ? Collections.emptyMap() : properties;
        // Single chokepoint: every facing is normalized to lowercase here, so all
        // case-sensitive consumers (FacingStyle.colorFor / DEEP_LINK_FACINGS,
        // MaprizonLayer.enabledFacings) match regardless of how the source (tile
        // decode vs viewer API response) cased it. Tile decode already passes
        // lowercase; this guards the API-parsed path.
        this.facing = facing == null ? null : facing.toLowerCase(Locale.ROOT);
        this.sourceZoom = sourceZoom;
    }

    public List<double[]> getPoints() {
        return points;
    }

    /** The facing this feature was loaded from (i.e. which per-facing PMTiles file). */
    public String getFacing() {
        return facing;
    }

    /** Slippy zoom of the tile this feature was decoded from; {@link #NATIVE_ZOOM}
     * for non-tile (API) features. See the field doc. */
    public int getSourceZoom() {
        return sourceZoom;
    }

    /** Slippy zoom this feature was requested at; {@link #NATIVE_ZOOM} for
     * non-tile (API) features. See the field doc. */
    public int getRequestZoom() {
        return requestZoom;
    }

    /** Copy carrying the requested zoom, stamped once the overzoom walk has
     * settled which requested tile these features belong to. */
    public ImageryFeature withRequestZoom(int zoom) {
        return new ImageryFeature(points, properties, facing, sourceZoom, zoom);
    }

    public String getSequenceId() {
        return prop("sequence_id");
    }

    public String getSequenceIndex() {
        return prop("sequence_index");
    }

    public String getTripId() {
        return prop("trip_id");
    }

    public String getImg() {
        return prop("img");
    }

    public String getHeading() {
        return prop("heading");
    }

    /** GPS/geometry-derived heading fallback the backend emits alongside
     * {@link #getHeading()} when the primary heading is absent. */
    public String getDerivedHeading() {
        return prop("derived_heading");
    }

    public String getTimestamp() {
        return prop("timestamp");
    }

    public String getUploadBatchId() {
        return prop("upload_batch_id");
    }

    public String getVehicleId() {
        return prop("vehicle_id");
    }

    public String getDayId() {
        return prop("day_id");
    }

    public String getPriority() {
        return prop("priority");
    }

    private String prop(String key) {
        Object v = properties.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
