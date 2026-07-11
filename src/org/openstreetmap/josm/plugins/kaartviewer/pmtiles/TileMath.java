package org.openstreetmap.josm.plugins.kaartviewer.pmtiles;

/**
 * Standard Web Mercator slippy-map tile math, plus the extra bit PMTiles/MVT
 * decoding needs: converting a tile-local MVT coordinate (0..extent, extent is
 * usually 4096) back to real lon/lat given the tile's z/x/y.
 */
public final class TileMath {

    private TileMath() {
    }

    /** Tile x/y (integer, top-left of tile) containing the given lon/lat at zoom z. */
    public static int[] lonLatToTile(double lon, double lat, int zoom) {
        double n = Math.pow(2, zoom);
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        double latRad = Math.toRadians(clampLat(lat));
        int y = (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
        int max = (int) n - 1;
        return new int[]{clamp(x, 0, max), clamp(y, 0, max)};
    }

    /**
     * Converts a tile-local MVT coordinate into longitude.
     *
     * @param tileX  integer tile column
     * @param zoom   zoom level of the tile
     * @param localX local (feature) x coordinate, 0..extent
     * @param extent the MVT layer's extent (e.g. 4096)
     */
    public static double tileLocalXToLon(int tileX, int zoom, double localX, int extent) {
        double n = Math.pow(2, zoom);
        double fracX = tileX + (localX / extent);
        return fracX / n * 360.0 - 180.0;
    }

    /**
     * Converts a tile-local MVT coordinate into latitude.
     *
     * @param tileY  integer tile row
     * @param zoom   zoom level of the tile
     * @param localY local (feature) y coordinate, 0..extent
     * @param extent the MVT layer's extent (e.g. 4096)
     */
    public static double tileLocalYToLat(int tileY, int zoom, double localY, int extent) {
        double n = Math.pow(2, zoom);
        double fracY = tileY + (localY / extent);
        double yRad = Math.PI * (1.0 - 2.0 * fracY / n);
        return Math.toDegrees(Math.atan(Math.sinh(yRad)));
    }

    private static double clampLat(double lat) {
        // Web Mercator is undefined at the poles; clamp to just short of them.
        return clamp(lat, -85.05112878, 85.05112878);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
