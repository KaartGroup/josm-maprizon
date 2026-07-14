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
    private static final String API_BASE = "https://viewer.kaart.com/backend/api/";

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
