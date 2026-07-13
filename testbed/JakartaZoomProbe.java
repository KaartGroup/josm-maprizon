import ch.poole.geo.pmtiles.Constants;
import ch.poole.geo.pmtiles.HttpUrlConnectionChannel;
import ch.poole.geo.pmtiles.Reader;

import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;

import org.locationtech.jts.geom.GeometryFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.zip.GZIPInputStream;

/**
 * Throwaway diagnostic for the "no coverage when zoomed in over Jakarta" bug.
 *
 * For each facing archive, walk zoom levels from the archive max down, compute
 * the tile containing central Jakarta, and report whether that tile exists and
 * how many "imagery" features it holds. If the exact tile is empty, do a small
 * neighborhood search so we distinguish "no detail baked at this zoom" from
 * "detail exists but our exact-tile addressing missed it".
 *
 * Run with:
 *   javac -cp "lib/*" -d testbed/out testbed/JakartaZoomProbe.java
 *   java  -cp "lib/*:testbed/out" JakartaZoomProbe
 */
public class JakartaZoomProbe {

    private static final String BASE =
            "https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-";
    private static final String LAYER_NAME = "imagery";
    private static final String[] FACINGS = {"front", "left", "right", "360", "still"};

    // Central Jakarta (Monas / Merdeka Square area).
    private static final double LAT = -6.1754;
    private static final double LON = 106.8272;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Jakarta zoom probe (lat=" + LAT + " lon=" + LON + ") ===");
        for (String facing : FACINGS) {
            String url = BASE + facing + ".pmtiles";
            System.out.println("\n--- facing=" + facing + " ---");
            try (FileChannel channel = new HttpUrlConnectionChannel(new URL(url));
                 Reader reader = new Reader(channel)) {
                int min = reader.getMinZoom();
                int max = reader.getMaxZoom();
                boolean gzip = reader.getTileCompression() == Constants.COMPRESSION_GZIP;
                System.out.println("  header: minZoom=" + min + " maxZoom=" + max
                        + " bounds=" + java.util.Arrays.toString(reader.getBounds()));
                for (int z = max; z >= Math.max(min, 6); z--) {
                    int[] xy = lonLatToTileXY(LON, LAT, z);
                    byte[] raw = reader.getTile(z, xy[0], xy[1]);
                    String exact;
                    if (raw != null) {
                        exact = "EXACT tile has " + countFeatures(raw, gzip) + " features";
                    } else {
                        int[] near = searchNeighborhood(reader, z, xy[0], xy[1]);
                        exact = near == null
                                ? "EMPTY (no tile within 8-tile radius)"
                                : "exact EMPTY, but neighbor (+" + near[0] + "," + near[1] + ") has "
                                        + near[2] + " features";
                    }
                    System.out.println("  z=" + z + " tile=" + xy[0] + "/" + xy[1] + " -> " + exact);
                }
            } catch (Exception e) {
                System.out.println("  ERROR: " + e);
            }
        }
        System.out.println("\n=== done ===");
    }

    private static int countFeatures(byte[] raw, boolean gzip) throws IOException {
        byte[] mvt = gzip ? gunzip(raw) : raw;
        GeometryFactory gf = new GeometryFactory();
        JtsMvt decoded;
        try (InputStream is = new ByteArrayInputStream(mvt)) {
            decoded = MvtReader.loadMvt(is, gf, new TagKeyValueMapConverter());
        }
        JtsLayer layer = decoded.getLayer(LAYER_NAME);
        return layer == null ? -1 : layer.getGeometries().size();
    }

    /** Returns {dx, dy, featureCount} of the first populated neighbor, or null. */
    private static int[] searchNeighborhood(Reader reader, int zoom, int cx, int cy) throws IOException {
        int radius = 8;
        for (int d = 1; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dy = -d; dy <= d; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != d) {
                        continue;
                    }
                    byte[] t = reader.getTile(zoom, cx + dx, cy + dy);
                    if (t != null) {
                        boolean gzip = reader.getTileCompression() == Constants.COMPRESSION_GZIP;
                        return new int[]{dx, dy, countFeatures(t, gzip)};
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

    private static int[] lonLatToTileXY(double lon, double lat, int zoom) {
        double n = Math.pow(2, zoom);
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        double latRad = Math.toRadians(lat);
        int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
        return new int[]{x, y};
    }
}
