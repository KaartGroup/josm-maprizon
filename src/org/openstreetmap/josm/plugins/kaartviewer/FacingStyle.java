package org.openstreetmap.josm.plugins.kaartviewer;

import java.awt.Color;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-facing constants: the list of supported camera facings, the public PMTiles
 * URL for each, and a distinct display color for each.
 *
 * Color choices (Phase 1, no existing color scheme found in the Viewer web app or
 * in CR_PLUGIN to reuse, so these were picked fresh for clear visual separation
 * against typical JOSM backgrounds - aerial imagery and OSM data):
 *   front -> deep orange  #E64A19
 *   left  -> blue         #1976D2
 *   right -> green        #388E3C
 *   360   -> purple       #7B1FA2
 *   still -> amber        #FBC02D
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
        COLORS.put(FRONT, new Color(0xE6, 0x4A, 0x19));
        COLORS.put(LEFT, new Color(0x19, 0x76, 0xD2));
        COLORS.put(RIGHT, new Color(0x38, 0x8E, 0x3C));
        COLORS.put(FACING_360, new Color(0x7B, 0x1F, 0xA2));
        COLORS.put(STILL, new Color(0xFB, 0xC0, 0x2D));
    }

    private static final String TILES_BASE = "https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-";

    /** Name of the single vector layer present inside every per-facing PMTiles archive. */
    public static final String VECTOR_LAYER_NAME = "imagery";

    private FacingStyle() {
    }

    public static Color colorFor(String facing) {
        Color c = COLORS.get(facing);
        return c != null ? c : Color.GRAY;
    }

    public static String pmtilesUrlFor(String facing) {
        return TILES_BASE + facing + ".pmtiles";
    }
}
