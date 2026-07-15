package org.openstreetmap.josm.plugins.maprizon.pmtiles;

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
import org.openstreetmap.josm.plugins.maprizon.FacingStyle;
import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.tools.Logging;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Fetches and decodes tiles from the public per-facing Maprizon PMTiles
 * archives, over plain HTTP range requests (no local file, no server-side help
 * needed).
 *
 * This is the real, verified fetch+decode path: it was validated stand-alone
 * (see testbed/PmtilesFetchTest.java) against the live
 * https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-front.pmtiles
 * before being wired in here - see this repo's README for the actual output of
 * that run.
 *
 * One {@link Reader} (and the {@link FileChannel} it wraps) is kept open per
 * facing for the lifetime of the loader; call {@link #close()} when the owning
 * layer is destroyed.
 */
public final class PmtilesTileLoader implements AutoCloseable {

    private final Map<String, Reader> readersByFacing = new ConcurrentHashMap<>();

    private Reader readerFor(String facing) throws IOException {
        Reader existing = readersByFacing.get(facing);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = readersByFacing.get(facing);
            if (existing != null) {
                return existing;
            }
            URL url = new URL(FacingStyle.pmtilesUrlFor(facing));
            FileChannel channel = new HttpUrlConnectionChannel(url);
            Reader reader = new Reader(channel);
            readersByFacing.put(facing, reader);
            return reader;
        }
    }

    public byte getMinZoom(String facing) throws IOException {
        return readerFor(facing).getMinZoom();
    }

    public byte getMaxZoom(String facing) throws IOException {
        return readerFor(facing).getMaxZoom();
    }

    /**
     * Fetches and decodes one tile for one facing. Returns an empty list (not
     * null) if the tile has no data at this z/x/y (sparse coverage) or no
     * "imagery" layer.
     */
    public List<ImageryFeature> loadTile(String facing, int z, int x, int y) throws IOException {
        List<ImageryFeature> features = loadTileOrNull(facing, z, x, y);
        return features != null ? features : java.util.Collections.emptyList();
    }

    /**
     * Like {@link #loadTile}, but returns {@code null} when the tile does NOT
     * exist in the archive at this z/x/y — as opposed to an empty list for a
     * tile that IS present but carries no "imagery" features. This lets callers
     * distinguish "nothing baked here, try the parent tile" (overzoom) from
     * "tile present, genuinely empty area", which matters because the archives
     * advertise maxZoom=16 in their header but only bake tiles down to z15.
     */
    public List<ImageryFeature> loadTileOrNull(String facing, int z, int x, int y) throws IOException {
        Reader reader = readerFor(facing);
        byte[] raw = reader.getTile(z, x, y);
        if (raw == null) {
            return null;
        }

        // Decompress if the header says gzip OR the bytes carry the gzip magic
        // (0x1f 0x8b) — never let a mis-declared compression drop a whole tile.
        byte[] mvtBytes = raw;
        boolean gzip = reader.getTileCompression() == Constants.COMPRESSION_GZIP
                || (raw.length >= 2 && (raw[0] & 0xFF) == 0x1f && (raw[1] & 0xFF) == 0x8b);
        if (gzip) {
            mvtBytes = gunzip(raw);
        }

        GeometryFactory gf = new GeometryFactory();
        JtsMvt mvt;
        try (InputStream is = new ByteArrayInputStream(mvtBytes)) {
            mvt = MvtReader.loadMvt(is, gf, new TagKeyValueMapConverter());
        }

        // Prefer the expected "imagery" layer, but if it is absent fall back to
        // EVERY layer in the tile, so no facing is silently emptied because its
        // layer happens to be named differently.
        List<JtsLayer> layers = new ArrayList<>();
        JtsLayer named = mvt.getLayer(FacingStyle.VECTOR_LAYER_NAME);
        if (named != null) {
            layers.add(named);
        } else {
            layers.addAll(mvt.getLayers());
        }
        if (layers.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<ImageryFeature> features = new ArrayList<>();
        for (JtsLayer layer : layers) {
            int extent = layer.getExtent();
            for (Geometry geom : layer.getGeometries()) {
                // Split multi-part geometry (MultiLineString / GeometryCollection /
                // MultiPoint) into its parts so several sequences packed into one
                // MVT feature don't collapse into a single feature with a bogus
                // connecting line — getCoordinates() would flatten them together.
                Object userData = geom.getUserData();
                @SuppressWarnings("unchecked")
                Map<String, Object> props = userData instanceof Map
                        ? (Map<String, Object>) userData : new HashMap<>();
                int parts = geom.getNumGeometries();
                for (int gi = 0; gi < parts; gi++) {
                    Geometry part = geom.getGeometryN(gi);
                    List<double[]> points = toLonLat(part, z, x, y, extent);
                    if (points.isEmpty()) {
                        continue;
                    }
                    features.add(new ImageryFeature(points, props, facing));
                }
            }
        }
        return features;
    }

    private List<double[]> toLonLat(Geometry geom, int z, int x, int y, int extent) {
        Coordinate[] coords = geom.getCoordinates();
        List<double[]> out = new ArrayList<>(coords.length);
        for (Coordinate c : coords) {
            double lon = TileMath.tileLocalXToLon(x, z, c.x, extent);
            double lat = TileMath.tileLocalYToLat(y, z, c.y, extent);
            out.add(new double[]{lon, lat});
        }
        return out;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    @Override
    public void close() {
        for (Map.Entry<String, Reader> e : readersByFacing.entrySet()) {
            try {
                e.getValue().close();
            } catch (IOException ex) {
                Logging.warn("Maprizon: failed to close PMTiles reader for facing " + e.getKey() + ": " + ex.getMessage());
            }
        }
        readersByFacing.clear();
    }
}
