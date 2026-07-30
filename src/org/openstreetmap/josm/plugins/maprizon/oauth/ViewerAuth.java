// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.oauth;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Optional Auth0 login for the Maprizon plugin, so a JOSM user with a Maprizon
 * account can view PRIVATE imagery (signed image bytes + private sequences) in
 * addition to the public data. Login is strictly opt-in — the whole plugin works
 * anonymously with no token, and nothing here is on any anonymous code path.
 *
 * <p>Uses the OAuth 2.0 <b>Authorization Code flow with PKCE</b> (RFC 7636) over a
 * <b>loopback redirect</b> (RFC 8252) — the recommended flow for a native desktop
 * client. There is no client secret: the plugin ships a <i>public</i> client_id
 * baked in ({@link #CLIENT_ID}), spins up a one-shot {@code http://127.0.0.1}
 * listener, opens the browser to Auth0, and the browser redirects straight back
 * with the authorization code. The user types nothing. The resulting Bearer JWT
 * is exactly what the viewer backend already accepts ({@code flaskr/auth/auth.py}:
 * Auth0 JWKS verify, audience {@link #AUDIENCE}).
 *
 * <p>Tokens (access + refresh + expiry + email) are persisted via JOSM
 * preferences so login survives restarts; the access token is refreshed silently
 * before expiry using the stored refresh token (no user interaction).
 *
 * @see LoginFlow for the interactive loopback half of the flow.
 */
public final class ViewerAuth {

    // --- Auth0 tenant. This MUST match the tenant prod app.maprizon.com validates
    // against: verified 2026-07-14 by inspecting the live site's login redirect
    // (app.maprizon.com/api/auth/login → dev-p6r3cciondp4has2.us.auth0.com,
    // audience https://Viewer/api/authorize). NB the server config.py *default* is
    // "viewerdevelopment", but prod overrides it to the tenant below via env. ---
    public static final String DOMAIN = "dev-p6r3cciondp4has2.us.auth0.com";
    public static final String AUDIENCE = "https://Viewer/api/authorize";
    /** offline_access is required for a refresh token (silent re-auth). */
    public static final String SCOPE = "openid profile email offline_access";

    /**
     * Public client_id of the Maprizon "Native" Auth0 application (PKCE, no
     * secret). This is a public identifier — safe to ship in the jar, exactly
     * like the Mapillary JOSM plugin bakes in its own OAuth client. Set once from
     * the Auth0 dashboard; users never see or enter it.
     *
     * <p>A dev can override it via the {@code maprizon.auth0.clientId} preference
     * without a rebuild.
     */
    private static final String CLIENT_ID = "u5qORFUqta4x7NnujJ83QVRIJL50JzOa";

    private static final String AUTHORIZE_URL = "https://" + DOMAIN + "/authorize";
    private static final String TOKEN_URL = "https://" + DOMAIN + "/oauth/token";
    private static final String USERINFO_URL = "https://" + DOMAIN + "/userinfo";

    // --- JOSM preference keys ---
    private static final String PREF_CLIENT_ID = "maprizon.auth0.clientId"; // optional dev override
    private static final String PREF_ACCESS = "maprizon.auth0.accessToken";
    private static final String PREF_REFRESH = "maprizon.auth0.refreshToken";
    private static final String PREF_EXPIRES_AT = "maprizon.auth0.expiresAt"; // epoch seconds
    private static final String PREF_EMAIL = "maprizon.auth0.email";
    private static final String PREF_ORG_ID = "maprizon.auth0.orgId"; // Auth0 org (org_xxx)

    /** Refresh the access token this many seconds before it actually expires. */
    private static final long REFRESH_SKEW_SECONDS = 120;

    private static final ViewerAuth INSTANCE = new ViewerAuth();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ViewerAuth() {
    }

    public static ViewerAuth getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------- listeners

    /** Register a callback fired (on the calling thread) whenever login state changes. */
    public void addLoginStateListener(Runnable r) {
        listeners.add(r);
    }

    public void removeLoginStateListener(Runnable r) {
        listeners.remove(r);
    }

    private void fireChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                Logging.warn("Maprizon: login-state listener failed: " + e);
            }
        }
    }

    // --------------------------------------------------------------- config

    /** The Auth0 Native-app client_id: the baked-in constant, or a dev pref override. */
    public String clientId() {
        if (!CLIENT_ID.isEmpty()) {
            return CLIENT_ID;
        }
        return Config.getPref().get(PREF_CLIENT_ID, "").trim();
    }

    public boolean isConfigured() {
        return !clientId().isEmpty();
    }

    // ---------------------------------------------------------- login state

    /** True if we hold credentials (a refresh token, or a still-usable access token). */
    public boolean isLoggedIn() {
        return !Config.getPref().get(PREF_REFRESH, "").isEmpty()
                || !Config.getPref().get(PREF_ACCESS, "").isEmpty();
    }

    /** The logged-in user's email, if known (for menu labels). May be empty. */
    public String email() {
        return Config.getPref().get(PREF_EMAIL, "");
    }

    /**
     * The logged-in user's Auth0 organization id (e.g. {@code org_9alzx7S32reIQ86s}),
     * used to fetch the org's private per-facing PMTiles bake ({@code {org_id}-{facing}.pmtiles})
     * exactly as the viewer's {@code useMapTileUrls} does. Empty when unknown /
     * logged out. Back-fills from the stored access token's claims on first use, so
     * it also works for sessions that logged in before this feature existed.
     */
    public String orgId() {
        String cached = Config.getPref().get(PREF_ORG_ID, "");
        if (!cached.isEmpty()) {
            return cached;
        }
        String org = orgIdFromJwt(Config.getPref().get(PREF_ACCESS, ""));
        if (org != null && !org.isEmpty()) {
            Config.getPref().put(PREF_ORG_ID, org);
            return org;
        }
        return "";
    }

    /** Forget all credentials. Fires a state-change so callers can refresh UI/imagery. */
    public void logout() {
        Config.getPref().put(PREF_ACCESS, "");
        Config.getPref().put(PREF_REFRESH, "");
        Config.getPref().put(PREF_EXPIRES_AT, null);
        Config.getPref().put(PREF_EMAIL, "");
        Config.getPref().put(PREF_ORG_ID, "");
        fireChanged();
    }

    // ------------------------------------------------------ token accessor

    /**
     * Return a currently-valid access token, refreshing silently if it is
     * expired (or about to be) and a refresh token is available. Returns
     * {@code null} when not logged in or when a refresh fails — callers MUST
     * treat null as "anonymous" and fall back to the public path, never error.
     *
     * <p>Blocking (may do a token-refresh HTTP call); call off the EDT.
     */
    public synchronized String getValidAccessToken() {
        String access = Config.getPref().get(PREF_ACCESS, "");
        long expiresAt = Config.getPref().getLong(PREF_EXPIRES_AT, 0L);
        long now = System.currentTimeMillis() / 1000L;

        if (!access.isEmpty() && now < expiresAt - REFRESH_SKEW_SECONDS) {
            return access;
        }

        String refresh = Config.getPref().get(PREF_REFRESH, "");
        if (refresh.isEmpty()) {
            return access.isEmpty() ? null : access;
        }

        try {
            TokenResponse tr = refresh(refresh);
            storeTokens(tr);
            return tr.accessToken;
        } catch (IOException e) {
            Logging.warn("Maprizon: token refresh failed, treating as logged out: " + e);
            return null;
        }
    }

    // -------------------------------------------------------- PKCE helpers

    /** A fresh high-entropy code verifier (also reused for the CSRF {@code state}). */
    public static String randomUrlToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The S256 code challenge for a verifier: base64url(SHA-256(verifier)). */
    public static String codeChallengeS256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Build the Auth0 /authorize URL for a PKCE loopback login. */
    public String buildAuthorizeUrl(String redirectUri, String state, String codeChallenge) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", clientId());
        q.put("redirect_uri", redirectUri);
        q.put("scope", SCOPE);
        q.put("audience", AUDIENCE);
        q.put("state", state);
        q.put("code_challenge", codeChallenge);
        q.put("code_challenge_method", "S256");
        return AUTHORIZE_URL + "?" + new String(encodeForm(q), StandardCharsets.UTF_8);
    }

    /**
     * Exchange an authorization code (+ PKCE verifier) for tokens and persist
     * them. On success fires a login-state change. Throws on any failure.
     */
    public void exchangeCode(String code, String codeVerifier, String redirectUri) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", clientId());
        form.put("code", code);
        form.put("code_verifier", codeVerifier);
        form.put("redirect_uri", redirectUri);

        JsonObject root = postForm(TOKEN_URL, form);
        if (root == null || !root.containsKey("access_token")) {
            throw new IOException("token exchange returned no access_token");
        }
        TokenResponse tr = TokenResponse.from(root);
        storeTokens(tr);
        fetchAndStoreEmail(tr.accessToken);
        fireChanged();
    }

    // ------------------------------------------------------------- internals

    private TokenResponse refresh(String refreshToken) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId());
        form.put("refresh_token", refreshToken);

        JsonObject root = postForm(TOKEN_URL, form);
        if (root == null || !root.containsKey("access_token")) {
            throw new IOException("refresh returned no access_token");
        }
        return TokenResponse.from(root);
    }

    private void storeTokens(TokenResponse tr) {
        Config.getPref().put(PREF_ACCESS, tr.accessToken);
        if (tr.refreshToken != null && !tr.refreshToken.isEmpty()) {
            // Auth0 may or may not rotate the refresh token; only overwrite when present.
            Config.getPref().put(PREF_REFRESH, tr.refreshToken);
        }
        long expiresAt = System.currentTimeMillis() / 1000L + tr.expiresIn;
        Config.getPref().putLong(PREF_EXPIRES_AT, expiresAt);
        // Refresh the org id from the new token's claims (authoritative; handles a
        // user who switched orgs). Only overwrite when the claim is present.
        String org = orgIdFromJwt(tr.accessToken);
        if (org != null && !org.isEmpty()) {
            Config.getPref().put(PREF_ORG_ID, org);
        }
    }

    /**
     * Read a claim from a JWT payload WITHOUT verifying the signature — we only read
     * claims from our own already-obtained token to derive the org id (Auth0
     * {@code org_id}, or the {@code mikro/org_id} custom claim as a fallback). The
     * token was issued to us over TLS by Auth0; this is not a security decision.
     * Returns null if the token is absent/unparseable or carries no org claim.
     */
    private static String orgIdFromJwt(String jwt) {
        if (jwt == null || jwt.isEmpty()) {
            return null;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonObject claims = parse(new String(payload, StandardCharsets.UTF_8));
            if (claims == null) {
                return null;
            }
            String org = claims.getString("org_id", "");
            if (org.isEmpty()) {
                org = claims.getString("mikro/org_id", "");
            }
            return org.isEmpty() ? null : org;
        } catch (RuntimeException e) {
            Logging.warn("Maprizon: could not read org_id from token: " + e);
            return null;
        }
    }

    private void fetchAndStoreEmail(String accessToken) {
        try {
            HttpClient.Response res = HttpClient
                    .create(new URL(USERINFO_URL))
                    .setHeader("Authorization", "Bearer " + accessToken)
                    .setHeader("Accept", "application/json")
                    .connect();
            if (res.getResponseCode() == 200) {
                JsonObject root = parse(res.fetchContent());
                if (root != null) {
                    String email = root.getString("email", root.getString("name", ""));
                    Config.getPref().put(PREF_EMAIL, email);
                }
            }
        } catch (IOException | RuntimeException e) {
            Logging.warn("Maprizon: /userinfo lookup failed (non-fatal): " + e);
        }
    }

    private static JsonObject postForm(String url, Map<String, String> form) throws IOException {
        HttpClient.Response res = HttpClient
                .create(new URL(url), "POST")
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .setHeader("Accept", "application/json")
                .setRequestBody(encodeForm(form))
                .connect();
        String content = res.fetchContent();
        if (res.getResponseCode() != 200) {
            JsonObject err = parse(content);
            String msg = err != null ? err.getString("error_description", err.getString("error", content)) : content;
            throw new IOException("HTTP " + res.getResponseCode() + ": " + msg);
        }
        return parse(content);
    }

    private static byte[] encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static JsonObject parse(String content) {
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

    /** Parsed Auth0 token response. */
    public static final class TokenResponse {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresIn;

        TokenResponse(String accessToken, String refreshToken, long expiresIn) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
        }

        static TokenResponse from(JsonObject root) {
            return new TokenResponse(
                    root.getString("access_token", ""),
                    root.containsKey("refresh_token") ? root.getString("refresh_token", "") : null,
                    root.containsKey("expires_in") ? root.getInt("expires_in") : 3600L);
        }
    }
}
