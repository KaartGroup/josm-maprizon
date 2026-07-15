import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.PmtilesTileLoader;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.TileMath;
import java.util.List;

/** Where do the COARSE front/left/360 features actually plot vs right, for the
 *  z12 tile that covers the user's view (3263,2118) and its z13 children?
 *  If front's z12 bbox != the tile's own geographic extent -> coordinate bug.
 *  If it matches but is sparse/east-only -> data/bake sparsity. */
public class CoordDumpProbe {
    static String bbox(List<ImageryFeature> f) {
        if (f == null) return "NULL(tile absent)";
        if (f.isEmpty()) return "EMPTY";
        double miL=1e9,maL=-1e9,miB=1e9,maB=-1e9; long p=0;
        for (ImageryFeature x: f) for (double[] q: x.getPoints()){miL=Math.min(miL,q[0]);maL=Math.max(maL,q[0]);miB=Math.min(miB,q[1]);maB=Math.max(maB,q[1]);p++;}
        return String.format("n=%d pts=%d lon[%.6f..%.6f] lat[%.6f..%.6f]", f.size(), p, miL, maL, miB, maB);
    }
    static void tileExtent(int z,int x,int y){
        double w=TileMath.tileLocalXToLon(x,z,0,1), e=TileMath.tileLocalXToLon(x,z,1,1);
        double n=TileMath.tileLocalYToLat(y,z,0,1), s=TileMath.tileLocalYToLat(y,z,1,1);
        System.out.printf("   tile %d/%d/%d geographic extent: lon[%.6f..%.6f] lat[%.6f..%.6f]%n",z,x,y,w,e,s,n);
    }
    public static void main(String[] a) throws Exception {
        PmtilesTileLoader L=new PmtilesTileLoader();
        String[] F={"front","left","right","360"};
        System.out.println("=== z12 tile 3263/2118 (covers the whole view) ===");
        tileExtent(12,3263,2118);
        for(String f:F) System.out.printf("  %-6s %s%n", f, bbox(L.loadTileOrNull(f,12,3263,2118)));
        System.out.println("\n=== z13 tile 6526/4236 (west, where front z13=16) ===");
        tileExtent(13,6526,4236);
        for(String f:F) System.out.printf("  %-6s %s%n", f, bbox(L.loadTileOrNull(f,13,6526,4236)));
        System.out.println("\n=== z15 WEST tile 26106/16944 (right present, front absent?) ===");
        tileExtent(15,26106,16944);
        for(String f:F) System.out.printf("  %-6s %s%n", f, bbox(L.loadTileOrNull(f,15,26106,16944)));
        System.out.println("\n=== z15 EAST tile 26108/16944 (front present) ===");
        tileExtent(15,26108,16944);
        for(String f:F) System.out.printf("  %-6s %s%n", f, bbox(L.loadTileOrNull(f,15,26108,16944)));
        L.close();
    }
}
