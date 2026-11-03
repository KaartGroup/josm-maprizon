// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Build gate, run by {@code build.xml} before compiling: fails when the JOSM jar
 * we compile against is OLDER than the plugin's declared {@code plugin.main.version}.
 *
 * <p>Compiling against an older JOSM than the one the plugin claims to need means
 * an API added in between cannot be checked, and the jar cannot stand in for the
 * oldest supported JOSM. It drifted silently once (r19439 against a declared
 * 19481). Run as a single-file program — {@code java CheckJosmRevision.java
 * <josm.jar> <minRevision>} — because Ant has no numeric comparison and the JDK
 * no longer ships a script engine for {@code <script>}.
 */
public class CheckJosmRevision {
    public static void main(String[] args) throws IOException {
        String jar = args[0];
        int min = Integer.parseInt(args[1].trim());
        int revision;
        try (JarFile jf = new JarFile(jar)) {
            ZipEntry entry = jf.getEntry("REVISION");
            if (entry == null) {
                fail(jar + " has no REVISION entry, so its JOSM version cannot be checked."
                        + " Fetch josm-tested.jar as CLAUDE.md's build step says.");
                return;
            }
            String text;
            try (InputStream in = jf.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher m = Pattern.compile("(?m)^Revision:\\s*(\\d+)").matcher(text);
            if (!m.find()) {
                fail(jar + "'s REVISION entry carries no 'Revision:' line.");
                return;
            }
            revision = Integer.parseInt(m.group(1));
        }
        if (revision < min) {
            fail(jar + " is JOSM r" + revision + ", older than plugin.main.version " + min
                    + ". Re-fetch it: curl -fSL https://josm.openstreetmap.de/download/"
                    + "josm-tested.jar -o " + jar);
            return;
        }
        System.out.println("JOSM compile jar is r" + revision + " (minimum " + min + ")");
    }

    private static void fail(String message) {
        System.err.println("JOSM revision check FAILED: " + message);
        System.exit(1);
    }
}
