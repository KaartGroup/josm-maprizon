import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.PmtilesTileLoader;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.TileMath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Faithful replay of the REWORKED download logic (per-tile clip + per-run
 * ancestor decode cache + persistent requested-tile ledger + feature-key dedup),
 * exercising the two reported failure scenarios:
 *
 *   A) fresh single download at z16 over the spot where front/left/360 only have
 *      data at z12  -> expect ALL FOUR facings non-zero (old bug: three zeros)
 *   B) download view A, then OVERLAPPING view B sharing the ledger
 *      -> expect view B fully covered for all facings (old bug: only the
 *         non-overlapping edge strip rendered)
 */
public class DownloadReplay {

    static final String[] FACINGS = {"front", "left", "right", "360"};
    static final int MAX_OVERZOOM_STEPS = 6;
    static final int MIN_ZOOM = 2, MAX_ZOOM = 16;

    static final Set<String> loadedTileKeys = new LinkedHashSet<>();      // persistent
    static final Set<String> storedFeatureKeys = new LinkedHashSet<>();   // persistent
    static final Map<String, List<ImageryFeature>> store = new HashMap<>(); // facing -> stored
    static PmtilesTileLoader loader = new PmtilesTileLoader();

    public static void main(String[] args) throws Exception {
        // --- Scenario A: the user's actual coverage area (green blob), fresh ---
        System.out.println("=== A) fresh z16 download over the real coverage area ===");
        download(16, 52215, 52218, 33890, 33893);
        // --- Scenario B: overlapping second download, shifted right by 2 tiles ---
        System.out.println("\n=== B) overlapping download shifted right (shared ledger) ===");
        download(16, 52217, 52220, 33890, 33893);
        // Verify view B is covered: count STORED in-B features per facing.
        double[] bWest = tb(16, 52217, 33890), bEast = tb(16, 52220 + 1, 33893 + 1);
        double minLon = bWest[0], maxLat = bWest[1], maxLon = bEast[0], minLat = bEast[1];
        System.out.println("\n--- stored features intersecting view B (must be >0 for all) ---");
        for (String f : FACINGS) {
            int inB = 0;
            for (ImageryFeature feat : store.getOrDefault(f, new ArrayList<>())) {
                for (double[] p : feat.getPoints()) {
                    if (p[0] >= minLon && p[0] <= maxLon && p[1] >= minLat && p[1] <= maxLat) { inB++; break; }
                }
            }
            System.out.printf("  %-6s inViewB=%d%n", f, inB);
        }
        // --- Scenario C: the earlier sparse spot (right-heavy transit path) ---
        System.out.println("\n=== C) sparse spot (whatever exists there should surface) ===");
        download(16, 52208, 52210, 33882, 33884);
        loader.close();
    }

    static double[] tb(int z, int x, int y) {
        return new double[]{TileMath.tileLocalXToLon(x, z, 0, 1), TileMath.tileLocalYToLat(y, z, 0, 1)};
    }

    static Map<String, Integer> download(int zoom, int x0, int x1, int y0, int y1) {
        Map<String, List<ImageryFeature>> fetched = new HashMap<>();
        Map<String, List<ImageryFeature>> ancestorCache = new HashMap<>();
        for (String facing : FACINGS) {
            List<ImageryFeature> acc = new ArrayList<>();
            for (int tx = x0; tx <= x1; tx++) {
                for (int ty = y0; ty <= y1; ty++) {
                    String key = facing + "/" + zoom + "/" + tx + "/" + ty;
                    if (loadedTileKeys.contains(key)) { continue; }
                    try {
                        acc.addAll(loadWithOverzoom(facing, zoom, tx, ty, ancestorCache));
                        loadedTileKeys.add(key);
                    } catch (Exception e) { /* logged in real code */ }
                }
            }
            fetched.put(facing, acc);
        }
        // merge with dedup
        Map<String, Integer> addedByFacing = new LinkedHashMap<>();
        for (Map.Entry<String, List<ImageryFeature>> e : fetched.entrySet()) {
            List<ImageryFeature> combined = store.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            int added = 0;
            for (ImageryFeature f : e.getValue()) {
                if (f.getPoints().isEmpty()) { continue; }
                if (storedFeatureKeys.add(featureKey(e.getKey(), f))) { combined.add(f); added++; }
            }
            addedByFacing.put(e.getKey(), added);
        }
        System.out.println("  added: " + addedByFacing);
        return addedByFacing;
    }

    static List<ImageryFeature> loadWithOverzoom(String facing, int zoom, int tx, int ty,
                                                 Map<String, List<ImageryFeature>> cache) throws Exception {
        int az = zoom, ax = tx, ay = ty;
        List<ImageryFeature> best = new ArrayList<>();
        for (int step = 0; step <= MAX_OVERZOOM_STEPS && az >= MIN_ZOOM; step++) {
            String ancestorKey = facing + "/" + az + "/" + ax + "/" + ay;
            List<ImageryFeature> features;
            if (cache.containsKey(ancestorKey)) {
                features = cache.get(ancestorKey);
            } else {
                features = loader.loadTileOrNull(facing, az, ax, ay);
                cache.put(ancestorKey, features);
            }
            if (features != null) {
                List<ImageryFeature> clipped = clipToTile(features, zoom, tx, ty);
                if (clipped.size() > best.size()) { best = clipped; }
            }
            az -= 1; ax >>= 1; ay >>= 1;
        }
        return best;
    }

    static List<ImageryFeature> clipToTile(List<ImageryFeature> features, int zoom, int x, int y) {
        double west = TileMath.tileLocalXToLon(x, zoom, 0, 1);
        double east = TileMath.tileLocalXToLon(x, zoom, 1, 1);
        double north = TileMath.tileLocalYToLat(y, zoom, 0, 1);
        double south = TileMath.tileLocalYToLat(y, zoom, 1, 1);
        List<ImageryFeature> kept = new ArrayList<>();
        for (ImageryFeature f : features) {
            double fMinLon = 1e9, fMaxLon = -1e9, fMinLat = 1e9, fMaxLat = -1e9;
            for (double[] p : f.getPoints()) {
                fMinLon = Math.min(fMinLon, p[0]); fMaxLon = Math.max(fMaxLon, p[0]);
                fMinLat = Math.min(fMinLat, p[1]); fMaxLat = Math.max(fMaxLat, p[1]);
            }
            if (fMaxLon >= west && fMinLon <= east && fMaxLat >= south && fMinLat <= north) { kept.add(f); }
        }
        return kept;
    }

    static String featureKey(String facing, ImageryFeature f) {
        List<double[]> pts = f.getPoints();
        double[] a = pts.get(0);
        double[] z = pts.get(pts.size() - 1);
        return facing + '|' + f.getSequenceId() + '|' + pts.size() + '|'
                + Math.round(a[0] * 1e7) + ',' + Math.round(a[1] * 1e7) + '|'
                + Math.round(z[0] * 1e7) + ',' + Math.round(z[1] * 1e7);
    }
}
