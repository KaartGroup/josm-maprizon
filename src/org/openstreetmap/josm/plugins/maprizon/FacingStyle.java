// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon;

import java.awt.Color;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-facing constants: the list of supported camera facings, the public PMTiles
 * URL for each, and a distinct display color for each.
 *
 * Per-facing display colors, matched EXACTLY to the Viewer web app's map layer
 * (client/src/.../MapComponents.js FACING_CONFIG + sequenceLayerStyles.js) so
 * coverage reads the same in JOSM as in the viewer:
 *   front -> white   #FFFFFF
 *   left  -> red     #FF0000
 *   right -> green   #00FF00
 *   360   -> purple  #9900FF
 *   still -> amber   #FBC02D   (viewer has no still map layer; kept distinct here)
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

    private static final Map<String, Color> COLORS = new LinkedHashMap<>();
    static {
        COLORS.put(FRONT, new Color(0xFF, 0xFF, 0xFF));
        COLORS.put(LEFT, new Color(0xFF, 0x00, 0x00));
        COLORS.put(RIGHT, new Color(0x00, 0xFF, 0x00));
        COLORS.put(FACING_360, new Color(0x99, 0x00, 0xFF));
        COLORS.put(STILL, new Color(0xFB, 0xC0, 0x2D));
    }

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
        Color c = COLORS.get(facing);
        return c != null ? c : Color.GRAY;
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
