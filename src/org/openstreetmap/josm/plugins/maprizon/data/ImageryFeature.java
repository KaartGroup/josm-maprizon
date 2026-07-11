package org.openstreetmap.josm.plugins.maprizon.data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One decoded coverage feature (a single point, or a sequence path rendered as a
 * line) read out of a per-facing PMTiles/MVT tile, together with its Viewer
 * properties.
 */
public final class ImageryFeature {

    /** Ordered {lon, lat} vertex list; size 1 for a point feature. */
    private final List<double[]> points;
    private final Map<String, Object> properties;
    private final String facing;

    public ImageryFeature(List<double[]> points, Map<String, Object> properties, String facing) {
        this.points = Collections.unmodifiableList(points);
        this.properties = properties == null ? Collections.emptyMap() : properties;
        this.facing = facing;
    }

    public List<double[]> getPoints() {
        return points;
    }

    /** The facing this feature was loaded from (i.e. which per-facing PMTiles file). */
    public String getFacing() {
        return facing;
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
