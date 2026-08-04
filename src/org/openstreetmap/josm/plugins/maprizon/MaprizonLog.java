// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon;

import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A durable, on-disk record of what this plugin actually did and what failed.
 *
 * <p><b>Why a file.</b> JOSM keeps no log of its own, {@code Logging.warn} goes to a
 * console nobody is watching, {@code Logging.debug} is off by default, and a
 * {@code Notification} fades after a few seconds. Every failure the plugin has
 * produced has therefore had to be re-triggered and screenshotted before it could
 * be read — which is a slow way to fix a bug, and it loses the failures that occur
 * once and never repeat. The whole diagnostic history now lands here instead, and
 * can simply be opened.
 *
 * <p>Location: {@code <JOSM user data dir>/maprizon.log} — beside {@code plugins/},
 * so it sits next to the jar it describes. Capped at {@link #MAX_BYTES}; when it
 * grows past that the file is restarted rather than rotated, because the recent
 * lines are the ones that matter and an unbounded log in someone's profile is its
 * own bug.
 *
 * <p>Never throws: a diagnostic aid that breaks the thing it is diagnosing is
 * worse than no diagnostic aid. Failures to write are swallowed after one warning.
 *
 * <p><b>Never log a credential.</b> Presigned tile URLs carry their signature in
 * the query string; log {@code PmtilesArchive.redact}-style values, never a raw
 * signed URL, and never a token.
 */
public final class MaprizonLog {

    private static final long MAX_BYTES = 1024 * 1024;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Set once if writing fails, so a broken path cannot produce one warning per
     * tile for the rest of the session. */
    private static volatile boolean disabled;

    private MaprizonLog() {
    }

    /** The log file, or null if the location cannot be determined. */
    public static Path file() {
        try {
            java.io.File dir = Config.getDirs().getUserDataDirectory(false);
            return dir == null ? null : dir.toPath().resolve("maprizon.log");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Record a line, and mirror it to JOSM's own logging at info level. */
    public static void info(String message) {
        Logging.info("Maprizon: " + message);
        append("INFO ", message);
    }

    /** Record a failure, and mirror it to JOSM's own logging at warning level. */
    public static void warn(String message) {
        Logging.warn("Maprizon: " + message);
        append("WARN ", message);
    }

    private static synchronized void append(String level, String message) {
        if (disabled) {
            return;
        }
        Path path = file();
        if (path == null) {
            disabled = true;
            return;
        }
        try {
            if (Files.exists(path) && Files.size(path) > MAX_BYTES) {
                Files.delete(path);
            }
            String line = ZonedDateTime.now().format(STAMP) + "  " + level + " " + message
                    + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            disabled = true;
            Logging.warn("Maprizon: cannot write " + path + " (" + e + "); "
                    + "further plugin logging goes to JOSM's console only");
        }
    }
}
