import ch.poole.geo.pmtiles.Constants;
import ch.poole.geo.pmtiles.HttpUrlConnectionChannel;
import ch.poole.geo.pmtiles.Reader;

import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Standalone, throwaway test to prove out the riskiest part of the plugin:
 * fetching a real PMTiles archive over HTTP and decoding one MVT tile from it.
 *
 * NOT part of the shipped plugin - lives only in testbed/, not compiled by build.xml.
 *
 * Run with:
 *   javac -cp "lib/*" -d testbed/out testbed/PmtilesFetchTest.java
 *   java  -cp "lib/*:testbed/out" PmtilesFetchTest
 */
public class PmtilesFetchTest {

    private static final String PMTILES_URL =
            "https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-front.pmtiles";
    private static final String LAYER_NAME = "imagery";

    public static void main(String[] args) throws Exception {
        System.out.println("=== KaartViewer PMTiles fetch+decode test ===");
        System.out.println("URL: " + PMTILES_URL);

        URL url = new URL(PMTILES_URL);
        try (FileChannel channel = new HttpUrlConnectionChannel(url);
             Reader reader = new Reader(channel)) {

            System.out.println("Header parsed OK.");
            System.out.println("  minZoom=" + reader.getMinZoom() + " maxZoom=" + reader.getMaxZoom());
            double[] bounds = reader.getBounds();
            double[] center = reader.getCenter();
            System.out.println("  bounds=" + java.util.Arrays.toString(bounds));
            System.out.println("  center=" + java.util.Arrays.toString(center) + " centerZoom=" + reader.getCenterZoom());
            System.out.println("  tileType=" + reader.getTileType() + " tileCompression=" + reader.getTileCompression());

            // Pick a zoom level to probe: prefer the archive's centerZoom, else fall back
            // partway between min/max so the tile is likely to contain data.
            int zoom = reader.getCenterZoom() > 0 ? reader.getCenterZoom() : reader.getMaxZoom();
            double lon = center[0];
            double lat = center[1];

            int[] xy = lonLatToTileXY(lon, lat, zoom);
            System.out.println("  probing tile z=" + zoom + " x=" + xy[0] + " y=" + xy[1]
                    + " (derived from center lon=" + lon + " lat=" + lat + ")");

            byte[] raw = reader.getTile(zoom, xy[0], xy[1]);
            if (raw == null) {
                System.out.println("No tile at that z/x/y (empty/sparse coverage there) - "
                        + "trying a small neighborhood search...");
                raw = searchNeighborhood(reader, zoom, xy[0], xy[1]);
            }

            if (raw == null) {
                System.out.println("RESULT: FAILED - could not locate any populated tile near the "
                        + "archive center after neighborhood search.");
                return;
            }

            System.out.println("Fetched tile: " + raw.length + " raw bytes.");

            byte[] mvtBytes = raw;
            if (reader.getTileCompression() == Constants.COMPRESSION_GZIP) {
                mvtBytes = gunzip(raw);
                System.out.println("Gunzipped to: " + mvtBytes.length + " bytes.");
            }

            GeometryFactory gf = new GeometryFactory();
            JtsMvt mvt;
            try (InputStream is = new ByteArrayInputStream(mvtBytes)) {
                mvt = MvtReader.loadMvt(is, gf, new TagKeyValueMapConverter());
            }

            System.out.println("Decoded MVT layers: " + mvt.getLayersByName().keySet());

            JtsLayer layer = mvt.getLayer(LAYER_NAME);
            if (layer == null) {
                System.out.println("RESULT: PARTIAL - tile decoded but no '" + LAYER_NAME
                        + "' layer present in this particular tile (may be a sparse area). "
                        + "Available layers: " + mvt.getLayersByName().keySet());
                return;
            }

            System.out.println("Layer '" + LAYER_NAME + "' feature count: " + layer.getGeometries().size());

            int printed = 0;
            for (Geometry geom : layer.getGeometries()) {
                Object userData = geom.getUserData();
                System.out.println("  feature geom type=" + geom.getGeometryType()
                        + " coord=" + geom.getCoordinate()
                        + " properties=" + userData);
                if (userData instanceof Map) {
                    Object seqId = ((Map<?, ?>) userData).get("sequence_id");
                    System.out.println("    -> sequence_id = " + seqId);
                }
                printed++;
                if (printed >= 3) {
                    break;
                }
            }

            System.out.println("RESULT: SUCCESS - fetched real PMTiles archive over the network, "
                    + "parsed header+directory, located a tile, decoded MVT, read feature properties.");
        }
    }

    private static byte[] searchNeighborhood(Reader reader, int zoom, int cx, int cy) throws IOException {
        int radius = 8;
        for (int d = 1; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dy = -d; dy <= d; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != d) {
                        continue;
                    }
                    byte[] t = reader.getTile(zoom, cx + dx, cy + dy);
                    if (t != null) {
                        System.out.println("  found populated tile at offset (" + dx + "," + dy + ")");
                        return t;
                    }
                }
            }
        }
        return null;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** Standard slippy-map lon/lat -> tile x/y at a given zoom (Web Mercator). */
    private static int[] lonLatToTileXY(double lon, double lat, int zoom) {
        double n = Math.pow(2, zoom);
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        double latRad = Math.toRadians(lat);
        int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
        return new int[]{x, y};
    }
}
