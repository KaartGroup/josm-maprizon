// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.pmtiles;


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
 * One {@link PmtilesArchive} is kept per scope/facing for the lifetime of the
 * loader; call {@link #close()} when the owning layer is destroyed.
 *
 * The bundled {@code ch.poole.geo.pmtiles.Reader} is deliberately NOT used — its
 * tile-id math overflows from z16 up, making the finest baked tiles unreachable.
 * See {@link PmtilesArchive} and {@link TileId#zxyToIndex(int, long, long)}.
 */
public final class PmtilesTileLoader implements AutoCloseable {

    /** Keyed by {@code scope/facing} (not just facing) so the public and an org's
     * private bake are separate readers — logging in/out re-points the tile source
     * to a different, correct reader without any stale-cache surgery. */
    private final Map<String, PmtilesArchive> archivesByFacing = new ConcurrentHashMap<>();

    /**
     * Every tileset scope to read for the current auth state, in priority order.
     *
     * <p>Logged in with an org: the org's private bake {@code {org_id}-{facing}.pmtiles}
     * <b>AND</b> the public bake. Logged out (or no org on the token): the public
     * bake alone.
     *
     * <p><b>The public bake is NOT a subset of the org bake, and this used to
     * return only one scope.</b> The tile-baker scopes an org's archive with
     * {@code f.org_id = :org} and nothing else (tile-baker/orchestrator.py), so it
     * holds that org's imagery — and no other org's. Public imagery from every
     * other org lives only in {@code public_imagery-*}. Serving one scope therefore
     * made logging in DELETE coverage from the map: an area covered by public
     * imagery downloaded fine logged out and reported "no imagery exists in this
     * view" logged in, which is the exact opposite of what a login is for. (The web
     * viewer swaps rather than unions, but it is a per-org product surface; the
     * JOSM plugin is a coverage tool for mappers, where more coverage is the point.)
     *
     * <p>Org first so its finer/private data wins any per-scope tie-break upstream;
     * duplicates where an org's own public imagery appears in both bakes are
     * collapsed by the layer's feature-key dedup. Anonymous is always the safe
     * fallback, so a missing org id can never break the public path.
     */
    public static List<String> currentScopes() {
        ViewerAuth auth = ViewerAuth.getInstance();
        if (auth.isLoggedIn()) {
            String org = auth.orgId();
            if (org != null && !org.isEmpty()) {
                return List.of(org, FacingStyle.PUBLIC_SCOPE);
            }
        }
        return List.of(FacingStyle.PUBLIC_SCOPE);
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
     * <p>Org scope MUST be presigned — those objects are private in Spaces and the
     * range reads carry no {@code Authorization} header, so a query-string
     * signature is the only mechanism. Returns null if signing is unavailable,
     * which the caller turns into a clear IOException rather than a silent 403.
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
                // Signatures changed, so every open archive for this scope is
                // pinned to a URL that is about to stop working. An archive holds
                // its URL for the life of the object, so invalidation means
                // eviction; they are rebuilt lazily against the new URLs.
                evictArchivesForScope(scope);
            } else if (forceResign || signed == null || !scope.equals(signedScope)) {
                // Nothing usable: don't serve another scope's or an expired batch.
                return null;
            }
        }
        return signed == null ? null : signed.urls.get(facing);
    }

    /** Drop every cached archive belonging to {@code scope}. An archive pins its
     * URL, so a re-signed scope must be rebuilt rather than mutated. Nothing to
     * close: reads are per-request connections, not a long-lived channel. Caller
     * must hold the monitor. */
    private void evictArchivesForScope(String scope) {
        String prefix = scope + "/";
        archivesByFacing.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private PmtilesArchive archiveFor(String scope, String facing) throws IOException {
        return archiveFor(scope, facing, false);
    }

    /**
     * @param forceResign re-sign before building the reader even if the cached
     *                    batch still looks current — used after a 403, which is
     *                    how a revoked/rotated signature announces itself.
     */
    private PmtilesArchive archiveFor(String scope, String facing, boolean forceResign) throws IOException {
        String key = scope + "/" + facing;
        if (!forceResign) {
            PmtilesArchive existing = archivesByFacing.get(key);
            if (existing != null) {
                return existing;
            }
        }
        synchronized (this) {
            if (!forceResign) {
                PmtilesArchive existing = archivesByFacing.get(key);
                if (existing != null) {
                    return existing;
                }
            }
            String urlStr = archiveUrlFor(facing, scope, forceResign);
            if (urlStr == null) {
                // Be explicit, and pass on the SERVER's reason. "No signed URL"
                // alone was true but useless: an expired login, an org missing
                // from the token, and a 500 in the signer all read identically,
                // and they need completely different fixes.
                String why = ViewerApiClient.lastSignFailure();
                throw new IOException("no signed URL for " + scope + "/" + facing
                        + (why == null ? " (private tiles need a valid login; see Maprizon > Log in)"
                                       : " — " + why));
            }
            // Re-check AFTER signing: archiveUrlFor may have evicted this key.
            PmtilesArchive existing = archivesByFacing.get(key);
            if (existing != null) {
                return existing;
            }
            PmtilesArchive archive = new PmtilesArchive(urlStr);
            // open() eagerly reads header + directory, so a bad/expired signature
            // throws HERE — before the put, so a failed archive never poisons the
            // cache.
            archive.open();
            archivesByFacing.put(key, archive);
            return archive;
        }
    }

    /**
     * Widest zoom range across every current scope — the floor of the overzoom
     * walk and the ceiling of a tile request. Taken as min-of-mins / max-of-maxes
     * rather than from one scope: the org and public bakes are baked independently
     * (their extents differ), so narrowing to a single scope's header would make
     * the other scope's finest or coarsest tiles unreachable.
     *
     * <p>A scope that cannot be opened is skipped; only if EVERY scope fails does
     * the exception propagate, so an unsignable org bake can't take the public
     * range down with it.
     */
    public int getMinZoom(String facing) throws IOException {
        int min = Integer.MAX_VALUE;
        IOException last = null;
        for (String scope : currentScopes()) {
            try {
                min = Math.min(min, openArchive(scope, facing).minZoom());
            } catch (IOException e) {
                last = e;
            }
        }
        if (min == Integer.MAX_VALUE) {
            throw last != null ? last : new IOException("no readable archive for " + facing);
        }
        return min;
    }

    public int getMaxZoom(String facing) throws IOException {
        int max = Integer.MIN_VALUE;
        IOException last = null;
        for (String scope : currentScopes()) {
            try {
                max = Math.max(max, openArchive(scope, facing).maxZoom());
            } catch (IOException e) {
                last = e;
            }
        }
        if (max == Integer.MIN_VALUE) {
            throw last != null ? last : new IOException("no readable archive for " + facing);
        }
        return max;
    }

    /**
     * Does this facing's archive hold a tile at {@code (z,x,y)} in ANY current
     * scope? Local lookup in the decoded directory — NO network request.
     *
     * <p>Exposed because it changes what callers can do: the fetch path no longer
     * has to request a tile to find out whether it exists, which is what the
     * ancestor walk and the empty-tile ledger were compensating for.
     */
    public boolean hasTile(String facing, int z, int x, int y) throws IOException {
        IOException last = null;
        for (String scope : currentScopes()) {
            try {
                if (openArchive(scope, facing).hasTile(z, x, y)) {
                    return true;
                }
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return false;
    }

    /** Open (or reuse) one scope's archive, re-signing once if its signature died. */
    private PmtilesArchive openArchive(String scope, String facing) throws IOException {
        try {
            return archiveFor(scope, facing);
        } catch (PmtilesArchive.PmtilesForbiddenException e) {
            return archiveFor(scope, facing, true);
        }
    }

    /**
     * Fetches and decodes one tile for one facing. Returns an empty list (not
     * null) if the tile has no data at this z/x/y (sparse coverage) or no
     * "imagery" layer.
     */
    public List<ImageryFeature> loadTile(String scope, String facing, int z, int x, int y) throws IOException {
        List<ImageryFeature> features = loadTileOrNull(scope, facing, z, x, y);
        return features != null ? features : java.util.Collections.emptyList();
    }

    /**
     * Like {@link #loadTile}, but returns {@code null} when the tile does NOT
     * exist in the archive at this z/x/y — as opposed to an empty list for a
     * tile that IS present but carries no "imagery" features. This lets callers
     * distinguish "nothing baked here, try the parent tile" (overzoom) from
     * "tile present, genuinely empty area".
     *
     * <p>Historical note, because a stale comment here sent people the wrong way:
     * this used to say the archives "advertise maxZoom=16 but only bake tiles down
     * to z15". They DO bake z16 — ~700 tiles per facing. They were unreachable
     * because the bundled reader's tile-id math overflowed at z16. See
     * {@link TileId#zxyToIndex(int, long, long)}.
     */
    public List<ImageryFeature> loadTileOrNull(String scope, String facing, int z, int x, int y)
            throws IOException {
        // `archive` is needed further down for the tile-compression byte, so it
        // must outlive the retry — and it may be a DIFFERENT archive after
        // re-signing.
        PmtilesArchive archive;
        byte[] raw;
        try {
            // Both calls are inside the try on purpose: a dead signature can
            // surface either from open() (it eagerly reads header + directory) or
            // from getTile().
            archive = archiveFor(scope, facing);
            raw = archive.getTile(z, x, y);
        } catch (PmtilesArchive.PmtilesForbiddenException e) {
            // The presignature died mid-session (they last 24h and a JOSM session
            // can outlive that, especially across a laptop sleep). Re-sign once and
            // retry. This is now a real HTTP status rather than a substring match
            // on an exception message.
            Logging.info("Maprizon: tile signature expired for " + scope + "/" + facing + ", re-signing");
            archive = archiveFor(scope, facing, true);
            raw = archive.getTile(z, x, y);
        }
        if (raw == null) {
            return null;
        }

        // Decompress if the header says gzip OR the bytes carry the gzip magic
        // (0x1f 0x8b) — never let a mis-declared compression drop a whole tile.
        byte[] mvtBytes = raw;
        boolean gzip = archive.header().tileCompression == PmtilesDirectory.COMPRESSION_GZIP
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

    static byte[] gunzip(byte[] data) throws IOException {
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
        // Nothing to close: archives hold no long-lived channel — each range read
        // opens and disconnects its own connection. Dropping the map is enough.
        archivesByFacing.clear();
    }
}
