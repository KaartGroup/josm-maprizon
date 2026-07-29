// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
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
import org.openstreetmap.josm.plugins.maprizon.io.ViewerApiClient;
import org.openstreetmap.josm.plugins.maprizon.io.ViewerApiClient.SignedTileUrls;
import org.openstreetmap.josm.plugins.maprizon.oauth.ViewerAuth;
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

    /** Keyed by {@code scope/facing} (not just facing) so the public and an org's
     * private bake are separate readers — logging in/out re-points the tile source
     * to a different, correct reader without any stale-cache surgery. */
    private final Map<String, Reader> readersByFacing = new ConcurrentHashMap<>();

    /**
     * Tileset scope for the current auth state: the logged-in user's Auth0 org id
     * (its private bake {@code {org_id}-{facing}.pmtiles}) when available, else the
     * public bake. Mirrors the viewer's {@code useMapTileUrls}. Anonymous is always
     * the safe fallback, so a missing org id can never break the public path.
     */
    private static String currentScope() {
        ViewerAuth auth = ViewerAuth.getInstance();
        if (auth.isLoggedIn()) {
            String org = auth.orgId();
            if (org != null && !org.isEmpty()) {
                return org;
            }
        }
        return FacingStyle.PUBLIC_SCOPE;
    }

    /**
     * Re-sign this long BEFORE the signature actually expires, so a range read
     * already in flight cannot land on a dead URL. Mirrors the web client's
     * {@code SIGN_REFRESH_BUFFER_MS} in {@code useMapTileUrls.js}.
     *
     * <p>This is the only signing-related duration defined on the client, and
     * deliberately so: it is a client POLICY (how early to refresh), not a copy
     * of the server's TTL. The expiry itself always comes from the server's
     * {@code expiresAt}.
     */
    private static final long SIGN_REFRESH_BUFFER_MS = 5L * 60L * 1000L;

    /** Last successful batch of presigned org-tile URLs, or null when anonymous
     * / not yet signed / last attempt failed. Guarded by {@code this}. */
    private SignedTileUrls signed;
    /** Scope the cached {@link #signed} batch belongs to, so switching orgs (or
     * logging out) can never serve another scope's signatures. Guarded by {@code this}. */
    private String signedScope;

    /**
     * Resolve the archive URL for a facing under the current scope.
     *
     * <p>Public scope is unsigned: {@code public_imagery-{facing}.pmtiles} is
     * public-read and fetched directly, exactly as the web client does it.
     *
     * <p>Org scope MUST be presigned — those objects are private in Spaces, and
     * {@link HttpUrlConnectionChannel} can only set a {@code Range} header (it
     * has no hook for {@code Authorization}), so a query-string signature is the
     * only mechanism available. Returns null if signing is unavailable, which
     * the caller turns into a clear IOException rather than a silent 403.
     *
     * <p>Caller must hold the monitor on {@code this}.
     */
    private String archiveUrlFor(String facing, String scope, boolean forceResign) {
        if (FacingStyle.PUBLIC_SCOPE.equals(scope)) {
            return FacingStyle.pmtilesUrlFor(facing, scope);
        }
        long nowMs = System.currentTimeMillis();
        boolean stale = signed == null
                || !scope.equals(signedScope)
                // expiresAt is epoch SECONDS (server-side); convert before comparing.
                || (signed.expiresAtEpochSeconds * 1000L) - nowMs <= SIGN_REFRESH_BUFFER_MS;
        if (forceResign || stale) {
            SignedTileUrls fresh = ViewerApiClient.signedTileUrls();
            if (fresh != null) {
                signed = fresh;
                signedScope = scope;
                // Signatures changed, so every open reader for this scope is
                // pinned to a URL that is about to stop working. There is no way
                // to re-point a Reader (its channel holds a final URL), so drop
                // them and let them be rebuilt lazily against the new URLs.
                evictReadersForScope(scope);
            } else if (forceResign || signed == null || !scope.equals(signedScope)) {
                // Nothing usable: don't serve another scope's or an expired batch.
                return null;
            }
        }
        return signed == null ? null : signed.urls.get(facing);
    }

    /** Close and drop every cached reader belonging to {@code scope}. Readers are
     * immutable around their URL, so invalidation means eviction — and the
     * channel must be closed or it leaks. Caller must hold the monitor. */
    private void evictReadersForScope(String scope) {
        String prefix = scope + "/";
        for (Map.Entry<String, Reader> e : readersByFacing.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            try {
                e.getValue().close();
            } catch (IOException ioe) {
                Logging.warn("Maprizon: closing stale tile reader failed: " + ioe.getMessage());
            }
            readersByFacing.remove(e.getKey());
        }
    }

    private Reader readerFor(String facing) throws IOException {
        return readerFor(facing, false);
    }

    /**
     * @param forceResign re-sign before building the reader even if the cached
     *                    batch still looks current — used after a 403, which is
     *                    how a revoked/rotated signature announces itself.
     */
    private Reader readerFor(String facing, boolean forceResign) throws IOException {
        String scope = currentScope();
        String key = scope + "/" + facing;
        if (!forceResign) {
            Reader existing = readersByFacing.get(key);
            if (existing != null) {
                return existing;
            }
        }
        synchronized (this) {
            if (!forceResign) {
                Reader existing = readersByFacing.get(key);
                if (existing != null) {
                    return existing;
                }
            }
            String urlStr = archiveUrlFor(facing, scope, forceResign);
            if (urlStr == null) {
                // Be explicit. Previously this path fetched an unsigned private
                // URL and died on an opaque "HTTP response code: 403", which is
                // what users saw as "download is just broken".
                throw new IOException("no signed URL available for " + scope + "/" + facing
                        + " (private tiles need a valid login; see Maprizon > Log in)");
            }
            // Re-check AFTER signing: archiveUrlFor may have evicted this key.
            Reader existing = readersByFacing.get(key);
            if (existing != null) {
                return existing;
            }
            URL url = new URL(urlStr);
            FileChannel channel = new HttpUrlConnectionChannel(url);
            // Reader's ctor eagerly reads the header, so a bad/expired signature
            // throws HERE — before the put, so a failed archive never poisons
            // the cache.
            Reader reader = new Reader(channel);
            readersByFacing.put(key, reader);
            return reader;
        }
    }

    /**
     * True when an IOException from the tile path is an expired/invalid signature
     * rather than a genuine absence.
     *
     * <p>The pmtiles library exposes no status code — {@code HttpUrlConnectionChannel}
     * calls {@code getInputStream()} without checking, so the status survives only
     * inside the JDK's message. Matching is therefore unavoidable, but it is
     * anchored to the JDK's exact wording:
     * {@code "Server returned HTTP response code: 403 for URL: <url>"}.
     *
     * <p>Anchoring matters. A bare {@code contains("403")} also matches the URL
     * tail — and these URLs are PRESIGNED, so they carry a long
     * {@code X-Amz-Signature} hex string that contains "403" roughly one time in
     * twenty. That would classify ordinary 404s as expired signatures and fire a
     * useless re-sign for every missing tile, which in sparse areas is most of
     * them.
     *
     * <p>404 is deliberately excluded: the baker skips facings with no data, so a
     * valid signature over a missing key means "no imagery here", not "bad auth".
     * (404 arrives as FileNotFoundException, whose message is only the URL, so it
     * cannot match this pattern anyway.)
     */
    private static boolean isForbidden(IOException e) {
        String m = e.getMessage();
        return m != null && m.contains("response code: 403");
    }

    public byte getMinZoom(String facing) throws IOException {
        try {
            return readerFor(facing).getMinZoom();
        } catch (IOException e) {
            if (!isForbidden(e)) {
                throw e;
            }
            return readerFor(facing, true).getMinZoom();
        }
    }

    public byte getMaxZoom(String facing) throws IOException {
        try {
            return readerFor(facing).getMaxZoom();
        } catch (IOException e) {
            if (!isForbidden(e)) {
                throw e;
            }
            return readerFor(facing, true).getMaxZoom();
        }
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
        // `reader` is needed further down for getTileCompression(), so it must
        // outlive the retry — and it may be a DIFFERENT reader after re-signing.
        Reader reader;
        byte[] raw;
        try {
            // Both calls are inside the try on purpose: a dead signature can
            // surface either from the Reader constructor (it eagerly reads the
            // archive header) or from getTile().
            reader = readerFor(facing);
            raw = reader.getTile(z, x, y);
        } catch (IOException e) {
            // A 403 here means the presignature died mid-session (they last 24h,
            // and a JOSM session can outlive that, especially across a laptop
            // sleep). Re-sign once and retry; anything else propagates to the
            // caller's existing per-tile handling.
            if (!isForbidden(e)) {
                throw e;
            }
            Logging.info("Maprizon: tile signature expired for " + facing + ", re-signing");
            reader = readerFor(facing, true);
            raw = reader.getTile(z, x, y);
        }
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
                    // Stamp the actual decoded zoom (this is the overzoom-resolved
                    // ancestor level when called from loadWithOverzoom), so the layer
                    // can suppress coarse geometry once its fine replacement loads.
                    features.add(new ImageryFeature(points, props, facing, z));
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
