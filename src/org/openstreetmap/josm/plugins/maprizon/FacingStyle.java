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
