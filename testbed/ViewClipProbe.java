import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.PmtilesTileLoader;
import org.openstreetmap.josm.plugins.maprizon.pmtiles.TileMath;
import java.util.*;

/** Exact view from the diag run. Compare CURRENT clip-to-fine-tile vs proposed
 *  clip-to-VIEW (deepest level with any feature in view), per facing, deduped. */
public class ViewClipProbe {
    static final String[] F={"front","left","right","360"};
    // view bbox from ~/maprizon-diag.log
    static final double VW=106.817343, VE=106.839638, VS=-6.153014, VN=-6.135669;
    static final int Z=16, X0=52213,X1=52217,Y0=33887,Y1=33890, STEPS=6, MINZ=2;
    static PmtilesTileLoader L=new PmtilesTileLoader();

    static boolean inView(ImageryFeature f){
        for(double[] p:f.getPoints()) if(p[0]>=VW&&p[0]<=VE&&p[1]>=VS&&p[1]<=VN) return true;
        return false;
    }
    static List<ImageryFeature> clipTile(List<ImageryFeature> fs,int z,int x,int y){
        double w=TileMath.tileLocalXToLon(x,z,0,1),e=TileMath.tileLocalXToLon(x,z,1,1);
        double n=TileMath.tileLocalYToLat(y,z,0,1),s=TileMath.tileLocalYToLat(y,z,1,1);
        List<ImageryFeature> k=new ArrayList<>();
        for(ImageryFeature f:fs){double a=1e9,b=-1e9,c=1e9,d=-1e9;
            for(double[] p:f.getPoints()){a=Math.min(a,p[0]);b=Math.max(b,p[0]);c=Math.min(c,p[1]);d=Math.max(d,p[1]);}
            if(b>=w&&a<=e&&d>=s&&c<=n) k.add(f);}
        return k;
    }
    static List<ImageryFeature> clipView(List<ImageryFeature> fs){
        List<ImageryFeature> k=new ArrayList<>(); for(ImageryFeature f:fs) if(inView(f)) k.add(f); return k;
    }
    static String key(String fac,ImageryFeature f){List<double[]> p=f.getPoints();double[] a=p.get(0),z=p.get(p.size()-1);
        return fac+"|"+p.size()+"|"+Math.round(a[0]*1e7)+","+Math.round(a[1]*1e7)+"|"+Math.round(z[0]*1e7)+","+Math.round(z[1]*1e7);}

    public static void main(String[] a) throws Exception {
        for(String fac:F){
            Set<String> seenFine=new HashSet<>(), seenView=new HashSet<>();
            int fine=0, view=0;
            Map<String,List<ImageryFeature>> cache=new HashMap<>();
            for(int tx=X0;tx<=X1;tx++) for(int ty=Y0;ty<=Y1;ty++){
                // walk fine->coarse
                int az=Z,ax=tx,ay=ty; List<ImageryFeature> bestFine=new ArrayList<>(), bestView=null;
                for(int s=0;s<=STEPS&&az>=MINZ;s++){
                    String kk=fac+"/"+az+"/"+ax+"/"+ay; List<ImageryFeature> fs;
                    if(cache.containsKey(kk)) fs=cache.get(kk); else {fs=L.loadTileOrNull(fac,az,ax,ay); cache.put(kk,fs);}
                    if(fs!=null){
                        List<ImageryFeature> cf=clipTile(fs,Z,tx,ty); if(cf.size()>bestFine.size()) bestFine=cf;
                        if(bestView==null){ List<ImageryFeature> cv=clipView(fs); if(!cv.isEmpty()) bestView=cv; } // deepest-nonzero view
                    }
                    az--;ax>>=1;ay>>=1;
                }
                for(ImageryFeature f:bestFine) if(seenFine.add(key(fac,f))) fine++;
                if(bestView!=null) for(ImageryFeature f:bestView) if(seenView.add(key(fac,f))) view++;
            }
            System.out.printf("%-6s  CURRENT(clip-to-fine-tile)=%-5d   FIX(clip-to-view,deepest-nonzero)=%d%n", fac, fine, view);
        }
        L.close();
    }
}
