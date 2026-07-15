import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.PmtilesTileLoader;

import java.util.List;

/**
 * Walk the exact overzoom parent chain (z16 -> z2) for one spot, per facing, and
 * report at which zoom each facing's data FIRST appears. If right appears at z15
 * but front/left/360 only appear at z12, then from a z16 request the plugin's
 * 3-step overzoom (reaching only z13) starves them -> "case 1".
 */
public class ParentChainProbe {

    static final String[] FACINGS = {"front", "left", "right", "360"};

    public static void main(String[] args) throws Exception {
        // z16 tiles across a sparse spot (right present, front absent at z15).
        int[][] spots = {
                {52208, 33882}, {52209, 33883}, {52214, 33888}, {52220, 33895},
        };
        PmtilesTileLoader loader = new PmtilesTileLoader();
        for (int[] spot : spots) {
            int x16 = spot[0], y16 = spot[1];
            System.out.println("\n================ z16 tile " + x16 + "/" + y16 + " ================");
            for (String facing : FACINGS) {
                StringBuilder sb = new StringBuilder("  " + String.format("%-6s", facing) + ": ");
                Integer firstZoom = null;
                for (int z = 16; z >= 8; z--) {
                    int shift = 16 - z;
                    int x = x16 >> shift, y = y16 >> shift;
                    String cell;
                    try {
                        List<ImageryFeature> f = loader.loadTileOrNull(facing, z, x, y);
                        if (f == null) cell = "z" + z + "=.";
                        else if (f.isEmpty()) cell = "z" + z + "=0";
                        else { cell = "z" + z + "=" + f.size(); if (firstZoom == null) firstZoom = z; }
                    } catch (Exception e) {
                        cell = "z" + z + "=ERR";
                    }
                    sb.append(cell).append(" ");
                }
                sb.append("  <- first data at z").append(firstZoom == null ? "none" : firstZoom);
                System.out.println(sb);
            }
        }
        loader.close();
    }
}
