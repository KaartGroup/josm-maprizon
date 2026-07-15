import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.PmtilesTileLoader;

import java.util.List;

/**
 * Tile-for-tile, per-facing, EXACTLY what the download's own code path
 * (PmtilesTileLoader.loadTileOrNull) returns for the same z/x/y. Distinguishes:
 *   null  = archive has no tile here (getTile null)
 *   0     = tile present but decodes to zero "imagery" features
 *   N     = tile present with N features
 *
 * If front/left/360 return null/0 where right returns N at the SAME x/y, the
 * gap is at the fetch level; the code reads all four identically.
 *
 *   javac -cp "lib/*:build/classes" -d testbed/out testbed/TilePresenceProbe.java
 *   java  -cp "lib/*:build/classes:testbed/out" TilePresenceProbe
 */
public class TilePresenceProbe {

    static final String[] FACINGS = {"front", "left", "right", "360"};

    public static void main(String[] args) throws Exception {
        int z = 15;
        int x0 = 26104, x1 = 26111, y0 = 16941, y1 = 16948; // the wider-view z15 range
        PmtilesTileLoader loader = new PmtilesTileLoader();

        System.out.println("=== per-tile loadTileOrNull() at z=" + z + " x[" + x0 + ".." + x1 + "] y[" + y0 + ".." + y1 + "] ===");
        for (String facing : FACINGS) {
            int present = 0, empty = 0, absent = 0, totalFeat = 0;
            StringBuilder grid = new StringBuilder();
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    String cell;
                    try {
                        List<ImageryFeature> f = loader.loadTileOrNull(facing, z, x, y);
                        if (f == null) { absent++; cell = "  ."; }
                        else if (f.isEmpty()) { empty++; cell = "  0"; }
                        else { present++; totalFeat += f.size(); cell = String.format("%3d", f.size()); }
                    } catch (Exception e) {
                        cell = "ERR";
                    }
                    grid.append(cell).append(' ');
                }
                grid.append('\n');
            }
            System.out.println("\n--- " + facing + ": present=" + present + " empty=" + empty
                    + " absent(null)=" + absent + " totalFeatures=" + totalFeat + " ---");
            System.out.print(grid);
        }
        loader.close();
    }
}
