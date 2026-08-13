// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.io;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.plugins.maprizon.MaprizonLog;
import org.openstreetmap.josm.plugins.maprizon.MaprizonVersion;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.HttpClient;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Asks Maprizon whether a newer plugin build exists, and says so once per
 * session if one does.
 *
 * <p><b>Why the plugin has to ask at all.</b> JOSM updates plugins from its own
 * plugin directory, and this plugin is not listed there yet — installation is by
 * hand, so JOSM has nothing to compare against and users sit on whatever jar
 * they copied in months ago. Until the listing lands, the only way a user learns
 * a new build exists is if we tell them.
 *
 * <p><b>Advisory, never a gate.</b> The uploader's equivalent check can hard-block
 * with HTTP 426, because uploading a bad file to a shared dataset is worth
 * stopping. Nothing here justifies that: this plugin shows imagery next to
 * someone's editing session, and refusing to draw coverage because a build is a
 * point release behind would interrupt mapping to solve a problem the user does
 * not have. So every outcome except "a newer version exists" is silent, and even
 * that is a dismissible notification.
 *
 * <p><b>Silence on failure is deliberate and total.</b> No network, a captive
 * portal, a 500, a body we cannot parse — all log and stop. A version check that
 * nags when the server is down teaches people to ignore it, and the one time it
 * matters they will.
 *
 * <p><b>No credentials, ever.</b> The request carries no {@code Authorization}
 * header. The server exempts this path from JWT precisely so a stale build can
 * be told it is stale without first proving who it is (see the uploader's
 * {@code api/version_gate.py}), and a logged-out user needs the answer just as
 * much as a logged-in one.
 *
 * <h2>Server contract</h2>
 * <pre>
 *   POST /backend/api/upload/check_uploader_version      (no auth header)
 *   {"version": "1.0.20", "product": "josm-maprizon", "platform": "mac"}
 *
 *   200 {"product": "josm-maprizon", "status": "ok", …}  // nothing to say
 *   426 {"product":         "josm-maprizon",            // REQUIRED, see below
 *        "current_version": "1.1.0",                    // or latest_version /
 *                                                       //    required_version
 *        "download_url":    "https://…/Maprizon.jar",   // optional
 *        "message":         "…"}                        // optional
 * </pre>
 *
 * <p>The existing gate compares versions for EXACT equality and 426s on any
 * difference, so a client NEWER than the configured version is also told it is
 * "out of date". That is right for the uploader, which must pin a build. Here it
 * is handled by only ever speaking when the server's version is numerically
 * newer than the running one — a plugin ahead of the env var stays silent rather
 * than inviting a downgrade.</p>
 *
 * <p>The response MUST echo {@code "product": "josm-maprizon"}. Anything that does
 * not is ignored — including a 426 — because the endpoint is shared with the
 * uploaders and an unkeyed answer is about a different product. Until the server
 * does that, this check is silent by design.</p>
 *
 * <p><b>Why the echo is mandatory rather than merely nice.</b> Measured against
 * the live endpoint on 2026-08-13, sending this plugin's real version and
 * product:
 *
 * <pre>
 *   -&gt; {"version":"1.0.20","product":"josm-maprizon","platform":"mac"}
 *   &lt;- 426 {"current_version":"4.9",
 *            "message":"Uploader is out of date. Please upgrade to version 4.9"}
 * </pre>
 *
 * The server ignored {@code product} and judged the plugin against the
 * UPLOADER's minimum version. Acting on that would tell every plugin user
 * running 1.0.x to upgrade to 4.9 — a release that does not exist for this
 * product — and send them looking for a download that is not there. Requiring
 * the echo turns that from a false alarm into silence.
 */
public final class PluginUpdateCheck {

    private static final String ENDPOINT =
            "https://app.maprizon.com/backend/api/upload/check_uploader_version";

    /** Identifies THIS product to the server's version gate, so the plugin and
     * the two uploaders can be gated independently. */
    public static final String PRODUCT_ID = "josm-maprizon";

    /** Short, so a hung server costs seconds at startup and nothing else. */
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    /** Preference holding the version a user has already been told about, so the
     * same release is not announced on every JOSM launch until they upgrade. */
    private static final String PREF_DISMISSED = "maprizon.update.lastNotifiedVersion";

    /** Lets a user (or a test) turn the check off entirely. */
    private static final String PREF_ENABLED = "maprizon.update.checkEnabled";

    private PluginUpdateCheck() {
    }

    /**
     * Run the check on a background daemon thread. Returns immediately; JOSM's
     * startup must not wait on a network round trip.
     */
    public static void runInBackground() {
        if (!Config.getPref().getBoolean(PREF_ENABLED, true)) {
            return;
        }
        if (!MaprizonVersion.isReleaseBuild()) {
            // A dev build has no meaningful number to compare, and telling a
            // developer their working copy is out of date is noise.
            return;
        }
        Thread t = new Thread(PluginUpdateCheck::check, "maprizon-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void check() {
        String running = MaprizonVersion.current();
        try {
            String body = Json.createObjectBuilder()
                    .add("version", running)
                    .add("product", PRODUCT_ID)
                    .add("platform", platformId())
                    .build()
                    .toString();

            HttpClient.Response res = HttpClient
                    .create(new URL(ENDPOINT), "POST")
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Accept", "application/json")
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setReadTimeout(READ_TIMEOUT_MS)
                    .setRequestBody(body.getBytes(StandardCharsets.UTF_8))
                    .connect();

            int code = res.getResponseCode();
            String content = res.fetchContent();
            // 200 and 426 both carry a usable body; anything else is not an
            // answer about our version, so it is not treated as one.
            if (code != 200 && code != 426) {
                MaprizonLog.info("update check: HTTP " + code + " — skipped");
                return;
            }

            String latest = null;
            String downloadUrl = null;
            String message = null;
            String product = null;
            if (content != null && !content.trim().isEmpty()) {
                try (JsonReader r = Json.createReader(
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))) {
                    JsonObject o = r.readObject();
                    product = string(o, "product");
                    // Three spellings accepted, so the server side needs no new
                    // vocabulary: `current_version` is what the existing gate
                    // already returns with its 426 (flaskr/views/Upload.py), and
                    // the other two are the names a purpose-built response would
                    // more naturally use.
                    latest = string(o, "latest_version");
                    if (latest == null) {
                        latest = string(o, "required_version");
                    }
                    if (latest == null) {
                        latest = string(o, "current_version");
                    }
                    downloadUrl = string(o, "download_url");
                    message = string(o, "message");
                }
            }

            // THE VERDICT MUST BE ADDRESSED TO US, and this is not a theoretical
            // precaution. Measured against the live endpoint on 2026-08-13:
            //
            //   POST {"version":"1.0.20","product":"josm-maprizon", ...}
            //   -> 426 {"current_version":"4.9",
            //           "message":"Uploader is out of date. Please upgrade to
            //                      version 4.9"}
            //
            // The server ignored `product` and judged this plugin against the
            // UPLOADER's minimum version. Acting on that would tell every plugin
            // user running 1.0.x to upgrade to "4.9" — a release that does not
            // exist for this product. So an answer that does not name our product
            // is not an answer about us, and is dropped.
            //
            // This deliberately leaves the check INERT until the server keys on
            // `product` and echoes it back. That is the correct failure mode: no
            // notification is strictly better than a confident wrong one, and the
            // day the server starts echoing `product` this begins working with no
            // change here.
            if (!PRODUCT_ID.equals(product)) {
                MaprizonLog.info("update check: response not attributed to "
                        + PRODUCT_ID + " (product=" + product + ", http " + code
                        + ") — ignoring, the server is not product-aware yet");
                return;
            }

            if (!MaprizonVersion.comparable(latest)) {
                // Includes the "426 with no version named" case: the server wants
                // an upgrade but has not said to what, which is not something a
                // user can act on, so saying it would only worry them.
                MaprizonLog.info("update check: no comparable version in response (running "
                        + running + ")");
                return;
            }
            if (MaprizonVersion.compare(running, latest) >= 0) {
                MaprizonLog.info("update check: running " + running + ", latest " + latest
                        + " — up to date");
                return;
            }

            MaprizonLog.info("update check: running " + running + ", newer version "
                    + latest + " is available");
            announce(latest, downloadUrl, message);
        } catch (Exception e) {
            // Offline, proxied, DNS-blocked, malformed — all the same answer.
            MaprizonLog.info("update check unavailable (" + e + ")");
        }
    }

    /** Tell the user once per new version, on the EDT. */
    private static void announce(String latest, String downloadUrl, String message) {
        String alreadyTold = Config.getPref().get(PREF_DISMISSED, "");
        if (latest.equals(alreadyTold)) {
            return; // same release, already announced in an earlier session
        }
        Config.getPref().put(PREF_DISMISSED, latest);

        StringBuilder html = new StringBuilder("<html><b>Maprizon plugin update</b><br>");
        html.append("You are running ").append(MaprizonVersion.current())
            .append("; ").append(latest).append(" is available.<br>");
        if (message != null && !message.isEmpty()) {
            html.append(escape(message)).append("<br>");
        }
        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            html.append("Download: ").append(escape(downloadUrl)).append("<br>");
        }
        html.append("Copy the jar into your JOSM plugins folder and restart JOSM.")
            .append("</html>");

        SwingUtilities.invokeLater(() -> {
            if (MainApplication.getMainFrame() == null) {
                return;
            }
            Notification n = new Notification(html.toString());
            n.setIcon(JOptionPane.INFORMATION_MESSAGE);
            n.setDuration(Notification.TIME_LONG);
            n.show();
        });
    }

    private static String string(JsonObject o, String key) {
        if (o == null || !o.containsKey(key) || o.isNull(key)) {
            return null;
        }
        try {
            return o.getString(key);
        } catch (ClassCastException e) {
            return String.valueOf(o.get(key));
        }
    }

    /** Short platform name, matching the uploader's vocabulary. */
    private static String platformId() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            return "mac";
        }
        if (os.contains("win")) {
            return "windows";
        }
        return "linux";
    }

    /** The server's text lands in an HTML notification; do not let it inject markup. */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
