// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon;

/**
 * The running plugin version, and the one place anything reads it from.
 *
 * <p><b>Read from the JAR's manifest, never hardcoded.</b> The version is
 * declared once in {@code build.xml} ({@code plugin.version}), which stamps
 * {@code Plugin-Version} into the manifest, which JOSM parses into
 * {@link org.openstreetmap.josm.plugins.PluginInformation#version} and hands to
 * the plugin at load. That value lands here.
 *
 * <p>It used to be a {@code BUILD_TAG} constant in the layer, hand-synced with
 * {@code build.xml} and {@code plugin.properties}. Three copies of one number is
 * two too many, and the copy that mattered — the one printed in the download
 * notification so you can tell WHICH build JOSM actually loaded — was the one
 * most likely to be stale, which makes it worse than useless: it would confirm
 * the new build while the old jar was running. Now it cannot disagree with the
 * manifest, because it IS the manifest.
 *
 * <p>{@link #DEV} is what you see running from an IDE or a jar built without the
 * manifest attribute, and it deliberately does not look like a release number so
 * nobody reports it as one. The update check treats it as "do not compare".
 */
public final class MaprizonVersion {

    /** Shown when the manifest carries no version — an unpackaged/dev run. */
    public static final String DEV = "dev";

    private static volatile String current = DEV;

    private MaprizonVersion() {
    }

    /** Record the version JOSM read from our manifest. Called once, at load. */
    static void set(String version) {
        current = (version == null || version.trim().isEmpty()) ? DEV : version.trim();
    }

    /** The running plugin version, e.g. {@code "1.0.20"}, or {@link #DEV}. */
    public static String current() {
        return current;
    }

    /** True when the running build has a real release version to compare. */
    public static boolean isReleaseBuild() {
        return !DEV.equals(current);
    }

    /**
     * Compare two dot-separated version strings numerically:
     * negative when {@code a} is older than {@code b}, positive when newer, 0 when
     * equal.
     *
     * <p>Numeric per component, NOT lexicographic — {@code "1.0.20"} is newer than
     * {@code "1.0.9"}, which a string compare gets backwards, and this plugin has
     * already shipped a 1.0.9 and a 1.0.20. Missing components count as 0, so
     * {@code "1.1"} equals {@code "1.1.0"}. A component that is not a number makes
     * the comparison unusable rather than guessed at — see {@link #comparable}.
     */
    public static int compare(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int ai = component(as, i);
            int bi = component(bs, i);
            if (ai != bi) {
                return ai < bi ? -1 : 1;
            }
        }
        return 0;
    }

    /** Is this string something {@link #compare} can actually reason about? */
    public static boolean comparable(String v) {
        if (v == null || v.trim().isEmpty() || DEV.equals(v)) {
            return false;
        }
        for (String part : v.trim().split("\\.")) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int component(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
