import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.PmtilesTileLoader;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.TileMath;
import java.util.*;
/** Simulate the FIX: fetch at z15 (real max), clip to VIEW, deepest-nonzero overzoom
 *  fallback for absent tiles. Expect front/left/360 to jump from ~40 to ~1000s. */
public class Z15Probe {
    static final String[] F={"front","left","right","360"};
    static final double VW=106.817343, VE=106.839638, VS=-6.153014, VN=-6.135669;
    static PmtilesTileLoader L=new PmtilesTileLoader();
    static List<ImageryFeature> clipView(List<ImageryFeature> fs){List<ImageryFeature> k=new ArrayList<>();
        for(ImageryFeature f:fs){for(double[] p:f.getPoints())if(p[0]>=VW&&p[0]<=VE&&p[1]>=VS&&p[1]<=VN){k.add(f);break;}}return k;}
    static String key(ImageryFeature f){List<double[]> p=f.getPoints();double[] a=p.get(0),z=p.get(p.size()-1);
        return p.size()+"|"+Math.round(a[0]*1e7)+","+Math.round(a[1]*1e7)+"|"+Math.round(z[0]*1e7)+","+Math.round(z[1]*1e7);}
    public static void main(String[] a) throws Exception {
        int Z=15;
        int[] nw=TileMath.lonLatToTile(VW,VN,Z), se=TileMath.lonLatToTile(VE,VS,Z);
        int x0=Math.min(nw[0],se[0]),x1=Math.max(nw[0],se[0]),y0=Math.min(nw[1],se[1]),y1=Math.max(nw[1],se[1]);
        System.out.printf("z15 view tile range x[%d..%d] y[%d..%d]%n",x0,x1,y0,y1);
        for(String fac:F){
            Set<String> seen=new HashSet<>(); int cnt=0; Map<String,List<ImageryFeature>> cache=new HashMap<>();
            for(int tx=x0;tx<=x1;tx++)for(int ty=y0;ty<=y1;ty++){
                int az=Z,ax=tx,ay=ty; List<ImageryFeature> pick=null;
                for(int s=0;s<=6&&az>=2;s++){
                    String kk=fac+"/"+az+"/"+ax+"/"+ay; List<ImageryFeature> fs;
                    if(cache.containsKey(kk))fs=cache.get(kk);else{fs=L.loadTileOrNull(fac,az,ax,ay);cache.put(kk,fs);}
                    if(fs!=null){List<ImageryFeature> cv=clipView(fs); if(!cv.isEmpty()){pick=cv;break;}}
                    az--;ax>>=1;ay>>=1;
                }
                if(pick!=null)for(ImageryFeature f:pick)if(seen.add(key(f)))cnt++;
            }
            System.out.printf("%-6s  FIX(fetch z15 + clip-to-view)=%d%n", fac, cnt);
        }
        L.close();
    }
}
