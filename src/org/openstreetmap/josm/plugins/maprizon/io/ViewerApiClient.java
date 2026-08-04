// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.io;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.plugins.maprizon.data.ImageryFeature;
import org.openstreetmap.josm.plugins.maprizon.oauth.ViewerAuth;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Talks to the Kaart Viewer backend, transparently anonymous OR authenticated:
 * <ul>
 *   <li><b>Logged out (default):</b> hits the JWT-exempt
 *       {@code /sequence/public/by-feature} (server-gated to {@code trips.public}),
 *       and uses raw public image URLs — no login required.</li>
 *   <li><b>Logged in (opt-in):</b> hits the authed {@code /sequence/by-feature}
 *       (serves private sequences) with a Bearer token, and resolves image bytes
 *       via {@code POST /images/sign} so private imagery loads.</li>
 * </ul>
 * The choice is made per call from {@link ViewerAuth#getValidAccessToken()};
 * a null token always falls back to the public path, so nothing here can break
 * the anonymous experience.
 */
public final class ViewerApiClient {

    /** Production viewer backend base — mirrors {@code client/src/config.js} API_ENDPOINT. */
    private static final String API_BASE = "https://app.maprizon.com/backend/api/";

    /** HTTP timeouts so a slow/unresponsive backend can never hang the loader
     * thread indefinitely (which wedged the whole image dialog on "Loading…"). */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    /**
     * One batch of presigned per-facing PMTiles URLs, plus the instant they stop
     * working. Immutable; {@code null} is never a member.
     *
     * <p>SSOT note: {@link #expiresAtEpochSeconds} is the SERVER's number, taken
     * verbatim from the response. The signing TTL lives in exactly one place —
     * {@code _TILES_SIGN_TTL_SECONDS} in {@code server/flaskr/views/Tiles.py} —
     * and is deliberately NOT mirrored here. A client-side copy of a server TTL
     * is precisely the drift that breaks silently when the server value changes.
     */
    public static final class SignedTileUrls {
        /** facing -> fully-qualified presigned https URL. Use VERBATIM: the
         * signature is embedded in the query string, so rebuilding or
         * re-encoding the URL invalidates it. */
        public final Map<String, String> urls;
        /** Epoch SECONDS (not millis — the server sends seconds; see Tiles.py). */
        public final long expiresAtEpochSeconds;

        SignedTileUrls(Map<String, String> urls, long expiresAtEpochSeconds) {
            // Actually unmodifiable, not just documented as such: this instance is
            // cached and read from the tile-loader thread, and a caller mutating
            // the shared map would corrupt another facing's signature.
            this.urls = java.util.Collections.unmodifiableMap(urls);
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        }
    }

    /**
     * Why the last {@link #signedTileUrls()} call produced nothing — the HTTP
     * status and the server's own words, plus which org claim the token actually
     * carries.
     *
     * <p>This exists because the failure is otherwise undiagnosable from outside.
     * Every failure path here returns {@code null} on purpose so the caller can
     * fall back to public tiles, and for a long time the reason went only to a
     * debug log that is off by default. The user-visible symptom of all of them is
     * identical — "private imagery doesn't load" — while the causes (expired
     * token, org missing from the token, unbaked tileset, server error) need
     * completely different fixes.
     */
    private static volatile String lastSignFailure;

    /** @return the reason the last sign attempt failed, or null if none has. */
    public static String lastSignFailure() {
        return lastSignFailure;
    }

    /**
     * Fetch presigned URLs for the logged-in user's per-org baked PMTiles.
     *
     * <p>Why this exists: the per-org tilesets became PRIVATE in Spaces on
     * 2026-07-22 (viewer commit "making tiles private", which flipped the
     * tile-baker's upload ACL to {@code private} for every non-public scope and
     * added this endpoint). Fetching {@code {org_id}-{facing}.pmtiles} directly
     * has returned HTTP 403 ever since, which is why logged-in downloads stopped
     * working entirely while anonymous ones kept succeeding.
     *
     * <p>The org is derived server-side FROM THE TOKEN — we deliberately send no
     * org parameter, and the server ignores one if supplied, so a client can
     * never ask for another org's tiles.
     *
     * <p>One call returns every facing; never call it per-facing.
     *
     * <p>Follows this class's convention: never throws. Returns {@code null} on
     * any failure (not logged in, network, non-200, malformed body) so callers
     * fall back to the public path rather than erroring.
     *
     * <p>Blocking — may perform a token refresh. Call OFF the EDT.
     */
    public static SignedTileUrls signedTileUrls() {
        String token = ViewerAuth.getInstance().getValidAccessToken();
        if (token == null) {
            lastSignFailure = "no usable access token (login expired or refresh failed)";
            Logging.warn("Maprizon: " + lastSignFailure);
            return null; // anonymous: caller uses the public, unsigned bake
        }
        try {
            HttpClient.Response res = HttpClient
                    .create(new URL(API_BASE + "tiles/sign"))
                    .setHeader("Authorization", "Bearer " + token)
                    .setHeader("Accept", "application/json")
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setReadTimeout(READ_TIMEOUT_MS)
                    .connect();
            if (res.getResponseCode() != 200) {
                // Two different error shapes reach here: the auth layer returns
                // {"code","description"} (401/403) while the endpoint itself
                // returns {"error"} (403 no-org, 500 signing failure). BOTH are
                // reported verbatim now — "403 no organization on token" and
                // "401 token expired" are opposite problems and used to be the
                // same silent fallback.
                String body = "";
                try {
                    body = String.valueOf(res.fetchContent());
                } catch (IOException ignored) {
                    // The status is the useful part; a body we cannot read is not
                    // worth failing the diagnosis over.
                }
                JsonObject err = parseOrNull(body);
                String detail = err == null ? body
                        : err.getString("description", err.getString("error", body));
                lastSignFailure = "tiles/sign returned HTTP " + res.getResponseCode()
                        + (detail == null || detail.isEmpty() ? "" : ": " + detail)
                        + " [" + ViewerAuth.getInstance().orgClaimSummary() + "]";
                Logging.warn("Maprizon: " + lastSignFailure);
                return null;
            }
            try (JsonReader reader = Json.createReader(
                    new ByteArrayInputStream(res.fetchContent().getBytes(StandardCharsets.UTF_8)))) {
                JsonObject root = reader.readObject();
                JsonObject urlsObj = root.getJsonObject("urls");
                if (urlsObj == null) {
                    lastSignFailure = "tiles/sign response had no \"urls\" object";
                    Logging.warn("Maprizon: " + lastSignFailure);
                    return null;
                }
                Map<String, String> urls = new HashMap<>();
                for (String facing : urlsObj.keySet()) {
                    String signed = urlsObj.getString(facing, null);
                    if (signed != null && !signed.isEmpty()) {
                        urls.put(facing, signed);
                    }
                }
                if (urls.isEmpty()) {
                    lastSignFailure = "tiles/sign returned no usable URLs";
                    Logging.warn("Maprizon: " + lastSignFailure);
                    return null;
                }
                // The server signs every facing it knows about (currently six,
                // including "drone"); this plugin only asks for the five in
                // FacingStyle.ALL_FACINGS. Extra keys are kept rather than
                // filtered — harmless, and it means adding a facing to the
                // plugin needs no change here.
                // Read once — a second getJsonNumber() call would re-resolve the
                // key and, if it were ever a non-number, throw from a different
                // line than the one that checked it.
                JsonNumber expiresNum = root.getJsonNumber("expiresAt");
                long expiresAt = expiresNum != null ? expiresNum.longValue() : 0L;
                if (expiresAt <= 0L) {
                    lastSignFailure = "tiles/sign response had no usable expiresAt";
                    Logging.warn("Maprizon: " + lastSignFailure);
                    return null;
                }
                lastSignFailure = null; // success
                return new SignedTileUrls(urls, expiresAt);
            }
        } catch (IOException | RuntimeException e) {
            lastSignFailure = "tiles/sign request failed: " + e;
            Logging.warn("Maprizon: " + lastSignFailure + ", falling back to public tiles");
            return null;
        }
    }

    /** The ordered frames of a sequence plus the index of the clicked frame. */
    public static final class SequenceResult {
        public final List<ImageryFeature> frames;
        public final int clickedIndex;

        SequenceResult(List<ImageryFeature> frames, int clickedIndex) {
            this.frames = frames;
            this.clickedIndex = clickedIndex;
        }
    }

    private ViewerApiClient() {
    }

    /**
     * Resolve the full ordered frame list for the sequence a clicked coverage
     * point belongs to (tiles are decimated, so the clicked tile only has a
     * subset of the sequence). When logged in, uses the authed endpoint (serves
     * private sequences); otherwise the public one. Returns {@code null} on any
     * failure (non-public trip while logged out, missing ids, network) so
     * callers can fall back to just the clicked feature.
     */
    public static SequenceResult fetchSequence(ImageryFeature clicked) {
        String sequenceId = clicked.getSequenceId();
        String tripId = clicked.getTripId();
        String facing = clicked.getFacing();
        if (sequenceId == null || tripId == null || facing == null) {
            return null;
        }
        String token = ViewerAuth.getInstance().getValidAccessToken();
        String endpoint = token != null ? "sequence/by-feature" : "sequence/public/by-feature";
        try {
            JsonObjectBuilder props = Json.createObjectBuilder();
            addNumberOrString(props, "sequence_id", sequenceId);
            props.add("trip_id", tripId);
            props.add("facing", facing);
            if (clicked.getImg() != null) {
                props.add("img", clicked.getImg());
            }
            if (clicked.getSequenceIndex() != null) {
                addNumberOrString(props, "sequence_index", clicked.getSequenceIndex());
            }
            if (clicked.getUploadBatchId() != null) {
                props.add("upload_batch_id", clicked.getUploadBatchId());
            }

            JsonObject feature = Json.createObjectBuilder()
                    .add("type", "Feature")
                    .add("properties", props)
                    .build();
            byte[] payload = Json.createObjectBuilder()
                    .add("feature", feature)
                    .build()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);

            HttpClient client = HttpClient
                    .create(new URL(API_BASE + endpoint), "POST")
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Accept", "application/json")
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setReadTimeout(READ_TIMEOUT_MS)
                    .setRequestBody(payload);
            if (token != null) {
                client.setHeader("Authorization", "Bearer " + token);
            }
            HttpClient.Response res = client.connect();

            if (res.getResponseCode() != 200) {
                Logging.warn("Maprizon: " + endpoint + " returned HTTP " + res.getResponseCode());
                return null;
            }

            String content = res.fetchContent();
            try (JsonReader reader = Json.createReader(
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))) {
                JsonObject root = reader.readObject();
                int clickedIndex = root.getInt("clicked_index", 0);
                JsonArray feats = root.getJsonArray("features");
                List<ImageryFeature> frames = new ArrayList<>();
                if (feats != null) {
                    for (JsonValue v : feats) {
                        ImageryFeature f = toFeature((JsonObject) v, facing);
                        if (f != null) {
                            frames.add(f);
                        }
                    }
                }
                if (frames.isEmpty()) {
                    return null;
                }
                if (clickedIndex < 0 || clickedIndex >= frames.size()) {
                    clickedIndex = 0;
                }
                return new SequenceResult(frames, clickedIndex);
            }
        } catch (IOException | RuntimeException e) {
            Logging.warn("Maprizon: fetchPublicSequence failed: " + e);
            return null;
        }
    }

    /**
     * The sequence adjacent to {@code boundaryFrame} in {@code direction}
     * ({@code "next"} or {@code "previous"}), so walking a drive can continue past
     * the end of one sequence instead of dead-ending.
     *
     * <p>Sequence ids are assigned in drive order, and the server navigates to the
     * NEAREST id in the requested direction rather than exactly ±1 — that is what
     * heals gaps left by deleted sequences instead of stranding the user at one.
     *
     * <p>{@code upload_batch_id} is sent deliberately: same-facing traversal must
     * be scoped to one upload batch or the same ground comes back duplicated
     * across batches. (The opposite rule applies to cross-camera queries, which
     * must NOT filter by it — see the project's conventions.)
     *
     * <p>The boundary frame's position and timestamp are sent too. The server uses
     * them for a proximity check on the candidate, which is what stops a
     * completely unrelated sequence elsewhere in the trip being served as
     * "adjacent" when internal GPS timestamps are unreliable.
     *
     * <p>Returns {@code null} for "there is nothing adjacent" — a 404 is the normal
     * answer at the end of a drive, not an error — and for any failure, so callers
     * simply stay where they are.
     *
     * <p>Blocking. Call OFF the EDT.
     */
    public static List<ImageryFeature> fetchAdjacentSequence(ImageryFeature boundaryFrame,
                                                             String direction) {
        if (boundaryFrame == null || !("next".equals(direction) || "previous".equals(direction))) {
            return null;
        }
        String sequenceId = boundaryFrame.getSequenceId();
        String tripId = boundaryFrame.getTripId();
        String facing = boundaryFrame.getFacing();
        if (sequenceId == null || tripId == null || facing == null) {
            return null;
        }
        String token = ViewerAuth.getInstance().getValidAccessToken();
        String base = token != null ? "sequence/adjacent/" : "sequence/public/adjacent/";
        try {
            StringBuilder qs = new StringBuilder();
            qs.append(base).append(enc(sequenceId)).append('/').append(direction)
              .append("?trip_id=").append(enc(tripId))
              // Server lowercases this itself, but sending it normalized keeps the
              // request readable in logs and matches what the web client sends.
              .append("&facing=").append(enc(facing.toLowerCase(Locale.ROOT)));
            if (boundaryFrame.getUploadBatchId() != null) {
                qs.append("&upload_batch_id=").append(enc(boundaryFrame.getUploadBatchId()));
            }
            List<double[]> pts = boundaryFrame.getPoints();
            if (pts != null && !pts.isEmpty()) {
                double[] p = pts.get(0);
                qs.append("&boundary_lng=").append(p[0]).append("&boundary_lat=").append(p[1]);
            }
            if (boundaryFrame.getTimestamp() != null) {
                qs.append("&boundary_timestamp=").append(enc(boundaryFrame.getTimestamp()));
            }

            HttpClient client = HttpClient
                    .create(new URL(API_BASE + qs))
                    .setHeader("Accept", "application/json")
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setReadTimeout(READ_TIMEOUT_MS);
            if (token != null) {
                client.setHeader("Authorization", "Bearer " + token);
            }
            HttpClient.Response res = client.connect();

            int code = res.getResponseCode();
            if (code == 404) {
                // End of the drive, or the candidate was rejected as too far from
                // the boundary. Both are legitimate "nothing there".
                return null;
            }
            if (code != 200) {
                Logging.warn("Maprizon: adjacent sequence returned HTTP " + code);
                return null;
            }
            try (JsonReader reader = Json.createReader(new ByteArrayInputStream(
                    res.fetchContent().getBytes(StandardCharsets.UTF_8)))) {
                JsonObject root = reader.readObject();
                JsonArray feats = root.getJsonArray("features");
                if (feats == null) {
                    return null;
                }
                List<ImageryFeature> frames = new ArrayList<>();
                for (JsonValue v : feats) {
                    ImageryFeature f = toFeature((JsonObject) v, facing);
                    if (f != null) {
                        frames.add(f);
                    }
                }
                return frames.isEmpty() ? null : frames;
            }
        } catch (IOException | RuntimeException e) {
            Logging.warn("Maprizon: fetchAdjacentSequence failed: " + e);
            return null;
        }
    }

    /** Parse a JSON object, or null if the text is absent/not an object. Used for
     * ERROR bodies, where the shape varies by which layer rejected the request. */
    private static JsonObject parseOrNull(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try (JsonReader reader = Json.createReader(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))) {
            return reader.readObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            return s; // UTF-8 is always present; unreachable in practice
        }
    }

    /**
     * Resolve a stored image URL to fetchable bytes. When logged in, mints a
     * short-lived pre-signed URL via {@code POST /images/sign} (works for private
     * <i>and</i> public imagery). When logged out — or on any failure — returns
     * the raw URL unchanged, so public imagery still loads and nothing errors.
     *
     * <p>Blocking (network); call off the EDT.
     */
    public static String resolveImageUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return rawUrl;
        }
        String token = ViewerAuth.getInstance().getValidAccessToken();
        if (token == null) {
            return rawUrl; // anonymous: public bytes straight from the raw URL
        }
        try {
            byte[] payload = Json.createObjectBuilder()
                    .add("img_url", rawUrl)
                    .build()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            HttpClient.Response res = HttpClient
                    .create(new URL(API_BASE + "images/sign"), "POST")
                    .setHeader("Authorization", "Bearer " + token)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Accept", "application/json")
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setReadTimeout(READ_TIMEOUT_MS)
                    .setRequestBody(payload)
                    .connect();
            if (res.getResponseCode() != 200) {
                // 403 = withheld/not-servable; anything else = transient. Fall back
                // to raw (which may itself 403 for private, surfaced to the user).
                Logging.warn("Maprizon: images/sign returned HTTP " + res.getResponseCode());
                return rawUrl;
            }
            try (JsonReader reader = Json.createReader(
                    new ByteArrayInputStream(res.fetchContent().getBytes(StandardCharsets.UTF_8)))) {
                JsonObject root = reader.readObject();
                String signed = root.getString("url", null);
                return signed != null && !signed.isEmpty() ? signed : rawUrl;
            }
        } catch (IOException | RuntimeException e) {
            Logging.warn("Maprizon: resolveImageUrl failed, using raw URL: " + e);
            return rawUrl;
        }
    }

    /** sequence_id / sequence_index are numeric in the DB; send them as numbers
     * when parseable so the backend's typed query matches, else fall back to string. */
    private static void addNumberOrString(JsonObjectBuilder b, String key, String value) {
        try {
            b.add(key, Long.parseLong(value.trim()));
        } catch (NumberFormatException nfe) {
            b.add(key, value);
        }
    }

    private static ImageryFeature toFeature(JsonObject feat, String fallbackFacing) {
        JsonObject props = feat.getJsonObject("properties");
        if (props == null) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        for (String k : props.keySet()) {
            JsonValue val = props.get(k);
            switch (val.getValueType()) {
                case STRING:
                    map.put(k, ((JsonString) val).getString());
                    break;
                case NUMBER:
                    map.put(k, ((JsonNumber) val).numberValue());
                    break;
                case TRUE:
                    map.put(k, Boolean.TRUE);
                    break;
                case FALSE:
                    map.put(k, Boolean.FALSE);
                    break;
                default:
                    // skip null / nested array / object
                    break;
            }
        }

        double lon = 0;
        double lat = 0;
        JsonObject geom = feat.getJsonObject("geometry");
        if (geom != null) {
            JsonArray coords = geom.getJsonArray("coordinates");
            if (coords != null && coords.size() >= 2) {
                lon = coords.getJsonNumber(0).doubleValue();
                lat = coords.getJsonNumber(1).doubleValue();
            }
        }
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{lon, lat});

        String facing = map.containsKey("facing") ? String.valueOf(map.get("facing")) : fallbackFacing;
        return new ImageryFeature(points, map, facing);
    }
}
