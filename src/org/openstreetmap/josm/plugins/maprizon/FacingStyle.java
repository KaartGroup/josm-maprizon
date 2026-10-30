// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon;

import org.openstreetmap.josm.spi.preferences.Config;

import java.awt.Color;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-facing constants: the list of supported camera facings, the public PMTiles
 * URL for each, and a distinct display color for each.
 *
 * Per-facing display colors, copied from the web app's single source of truth,
 * {@code client/src/theme/colorPalettes.js} (viewer-2-0, checked 2026-10-30), so
 * coverage reads the same in JOSM as in the app. Two palettes, as in the app:
 * <pre>
 *            standard          colourblind (Okabe–Ito)
 *   front    #FFFFFF white     #FFFFFF white
 *   left     #FF0000 red       #E69F00 orange
 *   right    #00FF00 green     #0072B2 blue
 *   360      #9900FF purple    #CC79A7 reddish purple
 *   still    #FF8C00 orange    #F0E442 yellow     (the app's stillMarker)
 * </pre>
 * This is a COPY, so it can drift: {@code still} had drifted to #FBC02D before
 * it was re-synced. Check colorPalettes.js when the app's palette changes.
 * Outlines stay black/white in both: an outline encodes shape, not category.
 * White front is only visible against a black outline (see MaprizonLayer paint),
 * mirroring the viewer's black outline circle under each point.
 */
public final class FacingStyle {

    public static final String FRONT = "front";
    public static final String LEFT = "left";
    public static final String RIGHT = "right";
    public static final String FACING_360 = "360";
    public static final String STILL = "still";

    /** All facings for which a public per-facing PMTiles file exists. */
    public static final List<String> ALL_FACINGS = Arrays.asList(FRONT, LEFT, RIGHT, FACING_360, STILL);

    /**
     * Facing values accepted by the Viewer deep-link's "facing" query parameter.
     * Per spec, "still" is NOT one of the accepted values there, so it is
     * deliberately excluded from this set - the deep link is built without a
     * facing parameter for "still" features.
     */
    public static final List<String> DEEP_LINK_FACINGS = Arrays.asList(FRONT, LEFT, RIGHT, FACING_360);

    private static final Map<String, Color> STANDARD = new LinkedHashMap<>();
    private static final Map<String, Color> COLORBLIND = new LinkedHashMap<>();
    static {
        STANDARD.put(FRONT, new Color(0xFF, 0xFF, 0xFF));
        STANDARD.put(LEFT, new Color(0xFF, 0x00, 0x00));
        STANDARD.put(RIGHT, new Color(0x00, 0xFF, 0x00));
        STANDARD.put(FACING_360, new Color(0x99, 0x00, 0xFF));
        STANDARD.put(STILL, new Color(0xFF, 0x8C, 0x00));

        COLORBLIND.put(FRONT, new Color(0xFF, 0xFF, 0xFF));
        COLORBLIND.put(LEFT, new Color(0xE6, 0x9F, 0x00));
        COLORBLIND.put(RIGHT, new Color(0x00, 0x72, 0xB2));
        COLORBLIND.put(FACING_360, new Color(0xCC, 0x79, 0xA7));
        COLORBLIND.put(STILL, new Color(0xF0, 0xE4, 0x42));
    }

    /** JOSM preference holding the palette choice. A display setting, so it is
     * kept across sessions, as the app keeps its colourblind theme. */
    private static final String PREF_COLORBLIND = "maprizon.colorblind";

    private static final String TILES_ENDPOINT = "https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles";

    /** Scope prefix for anonymous visitors — the public bake. Authenticated users
     * read their org's bake ({@code <org_id>-<facing>.pmtiles}) instead, which is
     * the SAME shape but a DIFFERENT (and more complete) file — the viewer does
     * exactly this in useMapTileUrls.
     *
     * <p>Access differs, though, and that difference is load-bearing: the public
     * bake is public-read, while org bakes are PRIVATE and reachable only via
     * presigned URLs. See {@link #pmtilesUrlFor(String, String)}. */
    public static final String PUBLIC_SCOPE = "public_imagery";

    /** Name of the single vector layer present inside every per-facing PMTiles archive. */
    public static final String VECTOR_LAYER_NAME = "imagery";

    private FacingStyle() {
    }

    public static Color colorFor(String facing) {
        Color c = (isColorBlind() ? COLORBLIND : STANDARD).get(facing);
        return c != null ? c : Color.GRAY;
    }

    /** True when the colourblind-safe palette is selected. */
    public static boolean isColorBlind() {
        try {
            return Config.getPref().getBoolean(PREF_COLORBLIND, false);
        } catch (RuntimeException e) {
            return false; // preferences not initialised (probes, early startup)
        }
    }

    /** Select the palette; callers repaint. */
    public static void setColorBlind(boolean on) {
        Config.getPref().putBoolean(PREF_COLORBLIND, on);
    }

    /** Public bake URL (anonymous). */
    public static String pmtilesUrlFor(String facing) {
        return pmtilesUrlFor(facing, PUBLIC_SCOPE);
    }

    /**
     * Per-facing PMTiles URL for a given scope.
     *
     * <p><b>Only fetchable for {@link #PUBLIC_SCOPE}.</b> Per-org tilesets became
     * PRIVATE in Spaces on 2026-07-22 (viewer commit "making tiles private"), so
     * the URL this builds for an org scope is well-formed but returns HTTP 403.
     * Org archives must instead go through
     * {@code ViewerApiClient.signedTileUrls()}, whose presigned URLs are used
     * verbatim — the signature is in the query string, so anything rebuilt here
     * would be invalid by construction.
     *
     * <p>The scope parameter is retained rather than hard-coded to public
     * because {@code PmtilesTileLoader} decides scope in exactly one place and
     * passes it through; this method simply no longer claims to serve private
     * scopes.
     */
    public static String pmtilesUrlFor(String facing, String scope) {
        String s = (scope == null || scope.trim().isEmpty()) ? PUBLIC_SCOPE : scope.trim();
        return TILES_ENDPOINT + "/" + s + "-" + facing + ".pmtiles";
    }
}
