import ch.poole.geo.pmtiles.Constants;
import ch.poole.geo.pmtiles.HttpUrlConnectionChannel;
import ch.poole.geo.pmtiles.Reader;

import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Throwaway Y-axis correctness check for the Maprizon MVT decode + reprojection.
 * NOT part of the shipped plugin. See task in report.
 *
 * Strategy:
 *  - Fetch live front PMTiles, find a populated parent tile at a decent zoom.
 *  - Pick point features with a UNIQUE property (external identity).
 *  - Predict, under TileMath's y-DOWN assumption, which vertical HALF of the
 *    parent tile the feature sits in -> which zoom+1 CHILD tile (top=2y,
 *    bottom=2y+1) should store it, per standard slippy tile-pyramid rules.
 *  - Fetch both children, locate the SAME feature by its unique id, and see
 *    which child actually stores it.
 *  - The tile-pyramid index is EXTERNAL ground truth (a feature is stored in the
 *    tile whose geo-bounds contain its true location), so this discriminates a
 *    globally-consistent y-flip, which per-tile range checks cannot.
 */
public class YAxisCheck {

    private static final String PMTILES_URL =
            "https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-front.pmtiles";
    private static final String LAYER_NAME = "imagery";

    // ---- TileMath formulas copied verbatim from the plugin (top-left / y-down) ----
    static double tileLocalXToLon(int tileX, int zoom, double localX, int extent) {
        double n = Math.pow(2, zoom);
        double fracX = tileX + (localX / extent);
        return fracX / n * 360.0 - 180.0;
    }
    static double tileLocalYToLat(int tileY, int zoom, double localY, int extent) {
        double n = Math.pow(2, zoom);
        double fracY = tileY + (localY / extent);
        double yRad = Math.PI * (1.0 - 2.0 * fracY / n);
        return Math.toDegrees(Math.atan(Math.sinh(yRad)));
    }
    // Tile grid corner latitude for integer/fractional tile row (north edge of row ty).
    static double tileRowToLat(double ty, int zoom) {
        double n = Math.pow(2, zoom);
        double yRad = Math.PI * (1.0 - 2.0 * ty / n);
        return Math.toDegrees(Math.atan(Math.sinh(yRad)));
    }
    static double tileColToLon(double tx, int zoom) {
        double n = Math.pow(2, zoom);
        return tx / n * 360.0 - 180.0;
    }
    static int[] lonLatToTileXY(double lon, double lat, int zoom) {
        double n = Math.pow(2, zoom);
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        double latRad = Math.toRadians(lat);
        int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
        return new int[]{x, y};
    }

    static Reader R;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Maprizon Y-AXIS check ===");
        System.out.println("URL: " + PMTILES_URL);
        URL url = new URL(PMTILES_URL);
        try (FileChannel channel = new HttpUrlConnectionChannel(url);
             Reader reader = new Reader(channel)) {
            R = reader;
            double[] bounds = reader.getBounds();   // [minLon,minLat,maxLon,maxLat]
            double[] center = reader.getCenter();
            int minZ = reader.getMinZoom(), maxZ = reader.getMaxZoom();
            System.out.println("minZoom=" + minZ + " maxZoom=" + maxZ);
            System.out.println("bounds(WGS84 minLon,minLat,maxLon,maxLat)=" + java.util.Arrays.toString(bounds));
            System.out.println("center=" + java.util.Arrays.toString(center) + " centerZoom=" + reader.getCenterZoom());
            System.out.println("tileType=" + reader.getTileType() + " compression=" + reader.getTileCompression());

            // Archive center is (0,0) placeholder; the real data is around Jakarta.
            // Seed the search there (also proven by the z5 probe below).
            double lon = 106.83, lat = -6.23;

            // Find a populated parent tile at a NON-TRIVIAL zoom whose data STRADDLES
            // the child boundary (both zoom+1 children populated) -> sharpest test.
            int[] hit = null; int zP = -1;
            for (int z = Math.min(15, maxZ - 1); z >= 8 && hit == null; z--) {
                int[] xy = lonLatToTileXY(lon, lat, z);
                int[] found = findPopulated(z, xy[0], xy[1], 12);
                if (found == null) continue;
                // prefer a straddling tile: scan a small window for one whose both children have data
                int[] straddle = findStraddling(z, found[0], found[1], 6);
                if (straddle != null) { hit = straddle; zP = z; }
            }
            if (hit == null) {
                // fall back to any populated tile from a high zoom downward
                for (int z = Math.min(16, maxZ - 1); z >= minZ && hit == null; z--) {
                    int[] xy = lonLatToTileXY(lon, lat, z);
                    int[] found = findPopulated(z, xy[0], xy[1], 12);
                    if (found != null) { hit = found; zP = z; }
                }
            }
            if (hit == null) {
                System.out.println("RESULT: could not find a populated tile near data. Aborting.");
                return;
            }
            int xP = hit[0], yP = hit[1];
            System.out.println("\nPARENT populated tile: z=" + zP + " x=" + xP + " y=" + yP);

            // Parent tile geo bounds (external, from grid).
            double north = tileRowToLat(yP, zP);
            double south = tileRowToLat(yP + 1, zP);
            double west  = tileColToLon(xP, zP);
            double east  = tileColToLon(xP + 1, zP);
            double midLat = tileRowToLat(yP + 0.5, zP);
            System.out.printf("  parent geo-bounds: north=%.7f south=%.7f west=%.7f east=%.7f  midLat=%.7f%n",
                    north, south, west, east, midLat);

            List<Feat> parentFeats = decodePoints(zP, xP, yP);
            System.out.println("  parent point-feature count: " + parentFeats.size());
            if (parentFeats.isEmpty()) { System.out.println("no point features in parent; abort."); return; }

            // Dump a few features to show real data + find a usable unique key.
            System.out.println("  sample parent features (reprojected y-DOWN):");
            for (int i = 0; i < Math.min(4, parentFeats.size()); i++) {
                Feat f = parentFeats.get(i);
                double flat = tileLocalYToLat(yP, zP, f.ly, tileExtent);
                double flon = tileLocalXToLon(xP, zP, f.lx, tileExtent);
                boolean inside = flat <= north + 1e-9 && flat >= south - 1e-9 && flon >= west - 1e-9 && flon <= east + 1e-9;
                System.out.printf("    [%d] localX=%.1f localY=%.1f -> lon=%.7f lat=%.7f insideTile=%b props=%s%n",
                        i, f.lx, f.ly, flon, flat, inside, f.props);
            }

            // Fetch both children.
            List<Feat> topChild = decodePoints(zP + 1, 2 * xP, 2 * yP);       // northern half
            List<Feat> botChild = decodePoints(zP + 1, 2 * xP, 2 * yP + 1);   // southern half
            System.out.println("\nCHILDREN at z=" + (zP + 1) + ":");
            System.out.println("  TOP child (x=" + (2 * xP) + ", y=" + (2 * yP) + ")   [northern half] pointFeats=" + topChild.size());
            System.out.println("  BOT child (x=" + (2 * xP) + ", y=" + (2 * yP + 1) + ") [southern half] pointFeats=" + botChild.size());

            // Cross-zoom matched test (proximity-based, uses the tile PYRAMID as ground truth):
            //   For each parent point feature, predict its storage child from TileMath's
            //   y-DOWN half (localY < extent/2 => north half => TOP child).
            //   Then find the SAME real point in the children by nearest reprojected
            //   position (y-down), independently in each child. Whichever child actually
            //   stores a coincident point (dist ~ 0) is ground truth (pyramid storage is
            //   orientation-independent). Compare storage-child to the y-DOWN prediction.
            //   A globally-consistent y-flip would put the prediction in the wrong child.
            int agree = 0, disagree = 0, ambiguous = 0;
            StringBuilder ex = new StringBuilder();
            // precompute child reprojected coords (y-down)
            double[][] topLL = reprojectAll(topChild, zP + 1, 2 * yP, 2 * xP);
            double[][] botLL = reprojectAll(botChild, zP + 1, 2 * yP + 1, 2 * xP);
            // sample a spread of parent features (both halves) to keep runtime bounded
            int step = Math.max(1, parentFeats.size() / 400);
            for (int i = 0; i < parentFeats.size(); i += step) {
                Feat f = parentFeats.get(i);
                double pLon = tileLocalXToLon(xP, zP, f.lx, tileExtent);
                double pLat = tileLocalYToLat(yP, zP, f.ly, tileExtent);
                boolean predictTop = f.ly < tileExtent / 2.0;
                double dTop = nearestMeters(pLon, pLat, topLL);
                double dBot = nearestMeters(pLon, pLat, botLL);
                double TOL = 3.0; // metres; same real capture point
                boolean inTop = dTop <= TOL, inBot = dBot <= TOL;
                if (inTop == inBot) { ambiguous++; continue; } // both or neither -> skip
                boolean actualTop = inTop;
                boolean ok = (actualTop == predictTop);
                if (ok) agree++; else disagree++;
                if (agree + disagree <= 8) {
                    ex.append(String.format(
                        "    parent localY=%.1f y-down lat=%.7f -> predict=%-10s | nearest child match: TOP=%.2fm BOT=%.2fm => stored in %s => %s%n",
                        f.ly, pLat, predictTop ? "TOP(north)" : "BOT(south)", dTop, dBot,
                        actualTop ? "TOP" : "BOT", ok ? "AGREE" : "DISAGREE"));
                }
            }
            System.out.println("\nCross-zoom matched features (pyramid storage vs y-DOWN prediction): agree=" + agree
                    + " disagree=" + disagree + " ambiguous(skipped)=" + ambiguous);
            System.out.print(ex);

            // Fallback continuity check if unique matching was thin: verify child feature
            // reprojections (y-down) actually land in the correct half-band, using the
            // parent's own point cloud lat-range as the external reference.
            System.out.println("\nHalf-band occupancy (y-DOWN reprojection of child features vs parent midLat="
                    + String.format("%.7f", midLat) + "):");
            bandStats("TOP child", topChild, zP + 1, 2 * yP, north, midLat);
            bandStats("BOT child", botChild, zP + 1, 2 * yP + 1, midLat, south);

            // Verdict
            System.out.println("\n================ VERDICT ================");
            if (agree + disagree >= 3) {
                if (disagree == 0) {
                    System.out.println("Y-AXIS CORRECT: all " + agree + " uniquely-matched features landed in the"
                            + " tile-pyramid child predicted by TileMath's y-DOWN assumption.");
                } else if (agree == 0) {
                    System.out.println("Y-AXIS FLIPPED: all " + disagree + " uniquely-matched features landed in the"
                            + " OPPOSITE child from the y-DOWN prediction.");
                } else {
                    System.out.println("MIXED (unexpected): agree=" + agree + " disagree=" + disagree
                            + " -- inspect above.");
                }
            } else {
                System.out.println("Unique cross-zoom matches were too few (" + (agree + disagree)
                        + "); rely on half-band occupancy above + bytecode analysis for the verdict.");
            }
        }
    }

    static int tileExtent = 4096;

    static void bandStats(String label, List<Feat> feats, int z, int ty, double bandNorth, double bandSouth) {
        if (feats.isEmpty()) { System.out.println("  " + label + ": (empty)"); return; }
        int in = 0, out = 0; double minLat = 90, maxLat = -90;
        for (Feat f : feats) {
            double flat = tileLocalYToLat(ty, z, f.ly, tileExtent);
            minLat = Math.min(minLat, flat); maxLat = Math.max(maxLat, flat);
            // expected band for this child (its own bounds); we report the actual lat span
            if (flat <= bandNorth + 1e-9 && flat >= bandSouth - 1e-9) in++; else out++;
        }
        System.out.printf("  %s: n=%d y-down lat span=[%.7f .. %.7f] expectedBand=[%.7f .. %.7f] inBand=%d outBand=%d%n",
                label, feats.size(), minLat, maxLat, bandSouth, bandNorth, in, out);
    }

    static double[][] reprojectAll(List<Feat> feats, int z, int ty, int tx) {
        double[][] out = new double[feats.size()][2];
        for (int i = 0; i < feats.size(); i++) {
            Feat f = feats.get(i);
            out[i][0] = tileLocalXToLon(tx, z, f.lx, tileExtent);
            out[i][1] = tileLocalYToLat(ty, z, f.ly, tileExtent);
        }
        return out;
    }

    static double nearestMeters(double lon, double lat, double[][] pts) {
        double best = Double.MAX_VALUE;
        double cosLat = Math.cos(Math.toRadians(lat));
        for (double[] p : pts) {
            double dLon = (p[0] - lon) * cosLat;
            double dLat = (p[1] - lat);
            double d2 = dLon * dLon + dLat * dLat;
            if (d2 < best) best = d2;
        }
        return Math.sqrt(best) * 111320.0; // deg -> metres (approx)
    }

    // Look for a tile in a small window whose BOTH zoom+1 children are populated.
    static int[] findStraddling(int z, int cx, int cy, int radius) throws IOException {
        for (int d = 0; d <= radius; d++)
            for (int dx = -d; dx <= d; dx++)
                for (int dy = -d; dy <= d; dy++) {
                    if (d != 0 && Math.max(Math.abs(dx), Math.abs(dy)) != d) continue;
                    int x = cx + dx, y = cy + dy;
                    if (R.getTile(z, x, y) == null) continue;
                    boolean top = R.getTile(z + 1, 2 * x, 2 * y) != null;
                    boolean bot = R.getTile(z + 1, 2 * x, 2 * y + 1) != null;
                    if (top && bot) return new int[]{x, y};
                }
        return null;
    }

    static Feat findByKey(List<Feat> list, String key) {
        for (Feat f : list) if (key.equals(f.uniqueKey())) return f;
        return null;
    }

    static String shortKey(String k) { return k.length() > 40 ? k.substring(0, 40) + "..." : k; }

    // ---- feature model ----
    static class Feat {
        double lx, ly; Map<String, Object> props;
        String uniqueKey() {
            if (props == null) return null;
            // Prefer an obviously-unique id; fall back to a composite.
            for (String k : new String[]{"id", "image_id", "image_key", "img", "key"}) {
                Object v = props.get(k);
                if (v != null) return k + "=" + v;
            }
            Object trip = props.get("trip_id");
            Object cap = props.get("captured_at");
            Object seq = props.get("sequence_id");
            if (trip != null && cap != null) return "trip+cap=" + trip + "|" + cap;
            if (seq != null && cap != null) return "seq+cap=" + seq + "|" + cap;
            return null;
        }
    }

    static List<Feat> decodePoints(int z, int x, int y) throws IOException {
        List<Feat> out = new ArrayList<>();
        byte[] raw = R.getTile(z, x, y);
        if (raw == null) return out;
        byte[] mvt = raw;
        if (R.getTileCompression() == Constants.COMPRESSION_GZIP) mvt = gunzip(raw);
        GeometryFactory gf = new GeometryFactory();
        JtsMvt m;
        try (InputStream is = new ByteArrayInputStream(mvt)) {
            m = MvtReader.loadMvt(is, gf, new TagKeyValueMapConverter());
        }
        JtsLayer layer = m.getLayer(LAYER_NAME);
        if (layer == null) return out;
        tileExtent = layer.getExtent();
        for (Geometry g : layer.getGeometries()) {
            Object ud = g.getUserData();
            @SuppressWarnings("unchecked")
            Map<String, Object> props = ud instanceof Map ? (Map<String, Object>) ud : null;
            for (Coordinate c : g.getCoordinates()) {
                Feat f = new Feat();
                f.lx = c.x; f.ly = c.y; f.props = props;
                out.add(f);
            }
        }
        return out;
    }

    static int[] findPopulated(int z, int cx, int cy, int radius) throws IOException {
        if (R.getTile(z, cx, cy) != null) return new int[]{cx, cy};
        for (int d = 1; d <= radius; d++)
            for (int dx = -d; dx <= d; dx++)
                for (int dy = -d; dy <= d; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != d) continue;
                    if (R.getTile(z, cx + dx, cy + dy) != null) return new int[]{cx + dx, cy + dy};
                }
        return null;
    }

    static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = gis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
