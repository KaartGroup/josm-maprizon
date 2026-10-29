// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.remotecontrol;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.remotecontrol.PermissionPrefWithDefault;
import org.openstreetmap.josm.io.remotecontrol.handler.RequestHandler;
import org.openstreetmap.josm.plugins.maprizon.FacingStyle;
import org.openstreetmap.josm.plugins.maprizon.MaprizonLog;
import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.gui.MaprizonImageDialog;
import org.openstreetmap.josm.plugins.maprizon.layer.MaprizonLayer;

import javax.swing.SwingUtilities;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remote-control command {@code /maprizon}: open JOSM at a Maprizon image — the
 * web viewer's "Edit here in JOSM" button calls it.
 *
 * <p>Takes the SAME parameters as the viewer's share link
 * ({@code viewer-2-0/client/src/utils/shareLink.js}) and as
 * {@code MaprizonLayer.buildDeepLink}, which is the reverse direction, so a link
 * can be translated by renaming the host and nothing else:
 * {@code /maprizon?lat=&lon=&sequence_id=&sequence_index=&facing=&trip_id=&upload_batch_id=}.
 * {@code lat}/{@code lon} are required (the viewer carries them in its map hash,
 * so the button must lift them out). With only those, JOSM zooms there and
 * downloads coverage; with {@code sequence_id} + {@code trip_id} + {@code facing}
 * as well, it also opens that frame in the Maprizon Image panel.
 *
 * <p>The frame is resolved by {@code sequence/by-feature} with no {@code img},
 * so the server places it by {@code sequence_index} ({@code _resolve_clicked_index}
 * in {@code Sequence.py}) — which is why the click position is passed as
 * {@code null}: a click position would re-pick "the frame nearest the point" and
 * could land on a neighbour.
 *
 * <p>Runs on JOSM's remote-control thread; everything touching layers or the map
 * is handed to the EDT.
 */
public class OpenMaprizonImageHandler extends RequestHandler {

    /** Command name — registered in {@code MaprizonPlugin}. */
    public static final String COMMAND = "maprizon";

    private static final PermissionPrefWithDefault PERMISSION = new PermissionPrefWithDefault(
            "maprizon.remotecontrol.open-image", true, "Open Maprizon images");

    /** Half-height of the area zoomed to, in degrees of latitude (~110 m), so
     * the coverage download that follows is one street's worth, not a city's. */
    private static final double HALF_SPAN_DEG = 0.001;

    private double lat;
    private double lon;
    private ImageryFeature frame;

    @Override
    public String[] getMandatoryParams() {
        return new String[] {"lat", "lon"};
    }

    @Override
    public String[] getOptionalParams() {
        return new String[] {"sequence_id", "sequence_index", "facing", "trip_id", "upload_batch_id"};
    }

    @Override
    public String getUsage() {
        return "zoom to a Maprizon image, download its coverage and open it in the Maprizon Image panel";
    }

    @Override
    public String[] getUsageExamples() {
        return new String[] {
            "/maprizon?lat=10.3157&lon=123.8854",
            "/maprizon?lat=10.3157&lon=123.8854&sequence_id=1234&sequence_index=17&facing=front&trip_id=56"
        };
    }

    @Override
    public String getPermissionMessage() {
        String where = String.format(Locale.ROOT, "%.6f, %.6f", lat, lon);
        return frame == null
                ? "Remote Control has been asked to show Maprizon coverage at " + where + "."
                : "Remote Control has been asked to open a Maprizon image at " + where + ".";
    }

    @Override
    public PermissionPrefWithDefault getPermissionPref() {
        return PERMISSION;
    }

    @Override
    protected void validateRequest() throws RequestHandlerBadRequestException {
        lat = parseCoordinate("lat", 90);
        lon = parseCoordinate("lon", 180);

        String sequenceId = blankToNull(args.get("sequence_id"));
        String tripId = blankToNull(args.get("trip_id"));
        String facing = blankToNull(args.get("facing"));
        if (facing != null) {
            facing = facing.toLowerCase(Locale.ROOT);
            if (!FacingStyle.DEEP_LINK_FACINGS.contains(facing)) {
                throw new RequestHandlerBadRequestException("Unknown facing: " + facing);
            }
        }
        // All three, or no image: by-feature 400s without any one of them, and a
        // half-specified link is better answered with "here is the place" than
        // with an image panel stuck on an error.
        if (sequenceId == null || tripId == null || facing == null) {
            frame = null;
            return;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("sequence_id", sequenceId);
        props.put("trip_id", tripId);
        String sequenceIndex = blankToNull(args.get("sequence_index"));
        if (sequenceIndex != null) {
            props.put("sequence_index", sequenceIndex);
        }
        String uploadBatchId = blankToNull(args.get("upload_batch_id"));
        if (uploadBatchId != null) {
            props.put("upload_batch_id", uploadBatchId);
        }
        List<double[]> point = Collections.singletonList(new double[] {lon, lat});
        frame = new ImageryFeature(point, props, facing);
    }

    @Override
    protected void handleRequest() {
        MaprizonLog.info("remote control: open " + (frame == null ? "location" : "image")
                + String.format(Locale.ROOT, " at %.6f,%.6f", lat, lon));
        GuiHelper.runInEDT(() -> {
            List<MaprizonLayer> existing = MainApplication.getLayerManager().getLayersOfType(MaprizonLayer.class);
            MaprizonLayer layer;
            if (existing.isEmpty()) {
                // Adding the first layer is what creates the map frame (and with
                // it the image panel, via MaprizonPlugin.mapFrameInitialized).
                layer = new MaprizonLayer();
                MainApplication.getLayerManager().addLayer(layer);
            } else {
                layer = existing.get(0);
            }
            // Next EDT turn: a map view created just above has not been laid out
            // yet, and zooming or planning a download against a zero-size view
            // gets the area wrong.
            SwingUtilities.invokeLater(() -> {
                if (!MainApplication.isDisplayingMapView()) {
                    return;
                }
                double halfLon = HALF_SPAN_DEG / Math.max(0.1, Math.cos(Math.toRadians(lat)));
                BoundingXYVisitor box = new BoundingXYVisitor();
                box.visit(new Bounds(lat - HALF_SPAN_DEG, lon - halfLon, lat + HALF_SPAN_DEG, lon + halfLon));
                MainApplication.getMap().mapView.zoomTo(box);
                layer.downloadCurrentView();
                if (frame != null) {
                    MaprizonImageDialog dialog = MaprizonImageDialog.getInstance();
                    if (dialog == null) {
                        MaprizonLog.warn("remote control: no Maprizon Image panel to open the image in");
                    } else {
                        dialog.showForClickedFeature(frame, null, layer);
                    }
                }
            });
        });
    }

    private double parseCoordinate(String key, double limit) throws RequestHandlerBadRequestException {
        try {
            double v = Double.parseDouble(args.get(key));
            if (Double.isNaN(v) || Math.abs(v) > limit) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException | NullPointerException e) {
            throw new RequestHandlerBadRequestException("Invalid " + key + ": " + args.get(key));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
