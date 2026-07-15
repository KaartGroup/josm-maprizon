import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.PmtilesTileLoader;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.TileMath;
import java.util.*;

/** Per requested tile, compare strategies for picking the overzoom level, all
 *  clipped to the VIEW and deduped per facing:
 *   A deepest-nonzero  B densest(max view-clip)  C coarsest-present(lowest z tile). */
public class ViewClipProbe2 {
    static final String[] F={"front","left","right","360"};
    static final double VW=106.817343, VE=106.839638, VS=-6.153014, VN=-6.135669;
    static final int Z=16, X0=52213,X1=52217,Y0=33887,Y1=33890, STEPS=6, MINZ=2;
    static PmtilesTileLoader L=new PmtilesTileLoader();
    static List<ImageryFeature> clipView(List<ImageryFeature> fs){List<ImageryFeature> k=new ArrayList<>();
        for(ImageryFeature f:fs){boolean in=false;for(double[] p:f.getPoints())if(p[0]>=VW&&p[0]<=VE&&p[1]>=VS&&p[1]<=VN){in=true;break;}if(in)k.add(f);}return k;}
    static String key(String fac,ImageryFeature f){List<double[]> p=f.getPoints();double[] a=p.get(0),z=p.get(p.size()-1);
        return fac+"|"+p.size()+"|"+Math.round(a[0]*1e7)+","+Math.round(a[1]*1e7)+"|"+Math.round(z[0]*1e7)+","+Math.round(z[1]*1e7);}
    public static void main(String[] a) throws Exception {
        for(String fac:F){
            Set<String> sA=new HashSet<>(), sB=new HashSet<>(), sC=new HashSet<>(); int cA=0,cB=0,cC=0;
            Map<String,List<ImageryFeature>> cache=new HashMap<>();
            for(int tx=X0;tx<=X1;tx++) for(int ty=Y0;ty<=Y1;ty++){
                int az=Z,ax=tx,ay=ty;
                List<ImageryFeature> depNZ=null, densest=new ArrayList<>(), coarsest=null;
                for(int s=0;s<=STEPS&&az>=MINZ;s++){
                    String kk=fac+"/"+az+"/"+ax+"/"+ay; List<ImageryFeature> fs;
                    if(cache.containsKey(kk)) fs=cache.get(kk); else {fs=L.loadTileOrNull(fac,az,ax,ay); cache.put(kk,fs);}
                    if(fs!=null){ List<ImageryFeature> cv=clipView(fs);
                        if(depNZ==null && !cv.isEmpty()) depNZ=cv;      // deepest nonzero
                        if(cv.size()>densest.size()) densest=cv;         // densest
                        coarsest=cv;                                     // keep last present = coarsest
                    }
                    az--;ax>>=1;ay>>=1;
                }
                if(depNZ!=null) for(ImageryFeature f:depNZ) if(sA.add(key(fac,f))) cA++;
                for(ImageryFeature f:densest) if(sB.add(key(fac,f))) cB++;
                if(coarsest!=null) for(ImageryFeature f:coarsest) if(sC.add(key(fac,f))) cC++;
            }
            System.out.printf("%-6s  deepest-nonzero=%-5d  densest=%-5d  coarsest-present=%d%n", fac, cA, cB, cC);
        }
        L.close();
    }
}
