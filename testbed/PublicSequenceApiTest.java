import ch.poole.geo.pmtiles.Constants;
import ch.poole.geo.pmtiles.HttpUrlConnectionChannel;
import ch.poole.geo.pmtiles.Reader;

import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * End-to-end verification for the Phase-1 public image viewer data path:
 *   1. Pull a real public feature's properties from the public front PMTiles.
 *   2. POST it to /sequence/public/by-feature and confirm it returns frames.
 *   3. GET the first frame's img URL and confirm the JPEG is publicly fetchable.
 * Throwaway — not part of the shipped plugin.
 */
public class PublicSequenceApiTest {

    private static final String PMTILES =
            "https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-front.pmtiles";
    private static final String BY_FEATURE =
            "https://viewer.kaart.com/backend/api/sequence/public/by-feature";
    // Central Jakarta, z15 tile known-populated from JakartaZoomProbe.
    private static final int Z = 15, X = 26107, Y = 16947;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Public sequence API + image fetch test ===");

        Map<String, Object> props = grabPointProps();
        if (props == null) {
            System.out.println("Could not find a point feature with properties in the probe tile.");
            return;
        }
        System.out.println("Sample public feature props: " + props);

        jakarta.json.JsonObjectBuilder p = Json.createObjectBuilder();
        addNum(p, "sequence_id", props.get("sequence_id"));
        p.add("trip_id", String.valueOf(props.get("trip_id")));
        p.add("facing", String.valueOf(props.get("facing")));
        if (props.get("img") != null) {
            p.add("img", String.valueOf(props.get("img")));
        }
        if (props.get("upload_batch_id") != null) {
            p.add("upload_batch_id", String.valueOf(props.get("upload_batch_id")));
        }
        addNum(p, "sequence_index", props.get("sequence_index"));
        String body = Json.createObjectBuilder()
                .add("feature", Json.createObjectBuilder()
                        .add("type", "Feature")
                        .add("properties", p))
                .build().toString();

        System.out.println("\nPOST " + BY_FEATURE);
        HttpURLConnection c = (HttpURLConnection) new URL(BY_FEATURE).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        System.out.println("HTTP " + code);
        if (code != 200) {
            System.out.println("Body: " + new String(readAll(c.getErrorStream()), StandardCharsets.UTF_8));
            return;
        }
        String resp = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        try (JsonReader r = Json.createReader(new ByteArrayInputStream(resp.getBytes(StandardCharsets.UTF_8)))) {
            JsonObject root = r.readObject();
            JsonArray feats = root.getJsonArray("features");
            int clicked = root.getInt("clicked_index", -1);
            System.out.println("features=" + (feats == null ? 0 : feats.size()) + " clicked_index=" + clicked);
            if (feats != null && !feats.isEmpty()) {
                String firstImg = feats.getJsonObject(0).getJsonObject("properties").getString("img", null);
                System.out.println("first frame geometry=" + feats.getJsonObject(0).get("geometry"));
                System.out.println("first frame img=" + firstImg);
                if (firstImg != null) {
                    testImage(firstImg);
                }
            }
        }
    }

    private static void testImage(String url) throws IOException {
        System.out.println("\nGET (no auth) " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        System.out.println("HTTP " + code + "  content-type=" + c.getContentType()
                + "  content-length=" + c.getContentLength());
        System.out.println(code == 200
                ? "RESULT: public image IS anonymously fetchable -> plugin needs no login for this imagery."
                : "RESULT: image NOT anonymously fetchable (HTTP " + code + ") -> would need signing/login.");
    }

    private static void addNum(jakarta.json.JsonObjectBuilder b, String key, Object v) {
        if (v == null) {
            return;
        }
        try {
            b.add(key, Long.parseLong(String.valueOf(v).trim()));
        } catch (NumberFormatException nfe) {
            b.add(key, String.valueOf(v));
        }
    }

    private static Map<String, Object> grabPointProps() throws IOException {
        try (FileChannel ch = new HttpUrlConnectionChannel(new URL(PMTILES));
             Reader reader = new Reader(ch)) {
            byte[] raw = reader.getTile(Z, X, Y);
            if (raw == null) {
                return null;
            }
            byte[] mvt = reader.getTileCompression() == Constants.COMPRESSION_GZIP ? gunzip(raw) : raw;
            JtsMvt decoded;
            try (InputStream is = new ByteArrayInputStream(mvt)) {
                decoded = MvtReader.loadMvt(is, new GeometryFactory(), new TagKeyValueMapConverter());
            }
            JtsLayer layer = decoded.getLayer("imagery");
            if (layer == null) {
                return null;
            }
            int dumped = 0;
            Map<String, Object> firstUsable = null;
            for (Geometry g : layer.getGeometries()) {
                Object ud = g.getUserData();
                if (dumped < 4) {
                    System.out.println("  feature geomType=" + g.getGeometryType() + " props=" + ud);
                    dumped++;
                }
                if (ud instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) ud;
                    if (firstUsable == null && m.get("sequence_id") != null
                            && m.get("trip_id") != null && m.get("facing") != null) {
                        firstUsable = m;
                    }
                }
            }
            return firstUsable;
        }
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

    private static byte[] readAll(InputStream is) throws IOException {
        if (is == null) {
            return new byte[0];
        }
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
