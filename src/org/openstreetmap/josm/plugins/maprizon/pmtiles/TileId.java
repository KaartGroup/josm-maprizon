// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.pmtiles;

/**
 * PMTiles tile-id ↔ (z, x, y) conversion.
 *
 * <p>PMTiles addresses every tile in an archive by a single {@code tile_id}: zoom
 * levels occupy contiguous, ascending id ranges, and within a zoom the id is the
 * tile's position along a Hilbert curve. That ordering is what makes a PMTiles
 * directory compressible, and it is why a directory can be decoded into "which
 * tiles exist" without touching tile data.
 *
 * <p><b>Why this class exists.</b> The bundled pmtiles-reader exposes only the
 * FORWARD direction, {@link ch.poole.geo.pmtiles.Hilbert#zxyToIndex(int, long, long)}.
 * Its {@code Reader$Directory}, {@code Reader.getZoomOffset} and
 * {@code Util.decompress} are package-private, so the inverse is unavailable. We
 * need the inverse to turn a decoded directory (a list of ids) back into map
 * coordinates.
 *
 * <p><b>Why that is safe to hand-roll.</b> Because the library ships the forward
 * function, the inverse is not a leap of faith: every id we decode can be pushed
 * back through {@code Hilbert.zxyToIndex} and compared. That turns correctness
 * into an assertion over real data rather than a code review — see
 * {@code testbed/CoverageProbe.java}. Do not "simplify" this file without
 * re-running that round-trip.
 *
 * <p>All methods are pure and allocation-light; no I/O.
 */
public final class TileId {

    /** PMTiles v3 addresses at most z0..z24 (the spec's zoom ceiling). Beyond
     * this, {@link #base(int)} would overflow the meaningful range and a tile id
     * could not be represented, so callers should treat a higher zoom as
     * malformed input rather than clamping silently. */
    public static final int MAX_ZOOM = 24;

    private TileId() {
    }

    /**
     * First tile id of zoom {@code z} — the count of all tiles in zooms below it.
     *
     * <p>{@code (4^z - 1) / 3}, i.e. 1 + 4 + 16 + … + 4^(z-1). Computed as a loop
     * rather than {@code Math.pow} so it stays exact integer arithmetic: at z24
     * the value is ~9.4e13 and a double would already be approximating.
     */
    public static long base(int z) {
        if (z < 0 || z > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom out of range: " + z);
        }
        long acc = 0;
        long levelTiles = 1;
        for (int i = 0; i < z; i++) {
            acc += levelTiles;
            levelTiles *= 4;
        }
        return acc;
    }

    /**
     * The zoom level a tile id belongs to.
     *
     * <p>Linear scan up from z0. It is bounded by {@link #MAX_ZOOM} (25 iterations
     * worst case) and runs once per directory entry, so a closed form would buy
     * nothing measurable and would be harder to check by eye.
     */
    public static int zoomFor(long tileId) {
        if (tileId < 0) {
            throw new IllegalArgumentException("negative tile id: " + tileId);
        }
        for (int z = 0; z < MAX_ZOOM; z++) {
            if (tileId < base(z + 1)) {
                return z;
            }
        }
        return MAX_ZOOM;
    }

    /**
     * Inverse of {@link ch.poole.geo.pmtiles.Hilbert#zxyToIndex(int, long, long)}.
     *
     * @return {@code {z, x, y}}
     */
    public static long[] toZxy(long tileId) {
        int z = zoomFor(tileId);
        long d = tileId - base(z);
        long n = 1L << z;           // tiles per axis at this zoom
        long x = 0;
        long y = 0;
        // Standard Hilbert d2xy: walk the curve one quadrant at a time, from the
        // smallest square upward, rotating/reflecting the accumulated point as
        // each quadrant is resolved.
        for (long s = 1; s < n; s <<= 1) {
            long rx = 1 & (d / 2);
            long ry = 1 & (d ^ rx);
            // rotate the quadrant
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    y = s - 1 - y;
                }
                long t = x;
                x = y;
                y = t;
            }
            x += s * rx;
            y += s * ry;
            d /= 4;
        }
        return new long[]{z, x, y};
    }

    /**
     * Hilbert index of a tile WITHIN its zoom level — the long-safe replacement
     * for {@link ch.poole.geo.pmtiles.Hilbert#zxyToIndex(int, long, long)}.
     *
     * <p><b>The library's version is broken and must not be used.</b> It
     * accumulates into an {@code int} and narrows on every iteration
     * ({@code ladd; l2i; istore}) before widening the result back to {@code long}
     * at return, so the declared type hides a wrap above {@code 2^31 - 1}. Since
     * z16 holds {@code 4^16 == 2^32} tiles, every index in the upper half of z16
     * and everything deeper comes back negative. Still present in upstream 0.3.7
     * ({@code int d = 0;}), so upgrading the jar does not fix it.
     *
     * <p>Measured consequence before this existed: on the live
     * {@code public_imagery-right} archive, z16 tile 55345/30833 has true index
     * 3464507560 and the library returned −830459736, so {@code Reader.getTile}
     * looked up a nonexistent id and returned null for all ~700 z16 tiles that
     * are actually baked. The plugin silently served z15 as its finest detail.
     *
     * <p>Identical algorithm, {@code long} accumulator. Verified two ways: it
     * agrees with the library exactly for z ≤ 15 (where the library is correct),
     * and round-trips against {@link #toZxy(long)} at every zoom.
     */
    public static long zxyToIndex(int z, long x, long y) {
        long n = 1L << z;
        long d = 0;
        long rx;
        long ry;
        for (long s = n / 2; s > 0; s /= 2) {
            rx = (x & s) > 0 ? 1 : 0;
            ry = (y & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            // rotate
            if (ry == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    y = s - 1 - y;
                }
                long t = x;
                x = y;
                y = t;
            }
        }
        return d;
    }

    /** Absolute PMTiles tile id for {@code (z,x,y)} — {@link #base(int)} plus the
     * in-zoom Hilbert index. Inverse of {@link #toZxy(long)}. */
    public static long toTileId(int z, long x, long y) {
        return base(z) + zxyToIndex(z, x, y);
    }

    /**
     * Longitude/latitude of a tile's north-west corner, in degrees (WGS84).
     *
     * <p>Standard slippy-map inverse. Latitude uses the Web Mercator inverse, so
     * this is only meaningful for {@code y} inside the tile grid at that zoom.
     *
     * @return {@code {lon, lat}}
     */
    public static double[] tileNorthWestLonLat(int z, long x, long y) {
        double n = Math.pow(2.0, z);
        double lon = x / n * 360.0 - 180.0;
        double latRad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n)));
        return new double[]{lon, Math.toDegrees(latRad)};
    }

    /**
     * The ancestor of {@code (z,x,y)} at {@code targetZoom}.
     *
     * <p>Used to fold a deep tile into the coarse display cell that will represent
     * it, which is how tile presence becomes a density number: count how many deep
     * tiles fold into each coarse cell.
     *
     * @return {@code {x, y}} at {@code targetZoom}
     */
    public static long[] ancestorAt(int z, long x, long y, int targetZoom) {
        if (targetZoom > z) {
            throw new IllegalArgumentException(
                    "targetZoom " + targetZoom + " is deeper than source zoom " + z);
        }
        int shift = z - targetZoom;
        return new long[]{x >> shift, y >> shift};
    }
}
