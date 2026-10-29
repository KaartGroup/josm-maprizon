// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.oauth;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import org.openstreetmap.josm.plugins.maprizon.MaprizonLog;
import org.openstreetmap.josm.plugins.maprizon.io.ViewerApiClient;
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
 * <p><b>Credentials live for ONE JOSM session and are never written to disk.</b>
 * Tokens (access + refresh + expiry + email + org) are held in memory on this
 * singleton, so quitting JOSM logs you out and the next session must log in
 * again. That is deliberate: a Maprizon account is used from more than one
 * machine, and a token silently restored from preferences meant a user who
 * thought they were logged out was still holding a live session — two machines
 * stepping on each other with no way to tell from the UI. Within a session the
 * access token is still refreshed silently before expiry using the in-memory
 * refresh token (no user interaction). Tokens persisted by earlier versions are
 * purged from preferences on first use — see {@link #purgePersistedTokens()}.
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
    /**
     * Keys earlier versions used to PERSIST the session. Kept only so
     * {@link #purgePersistedTokens()} can delete them: nothing reads them, and
     * nothing writes them any more.
     */
    private static final String[] LEGACY_TOKEN_PREFS = {
        "maprizon.auth0.accessToken",
        "maprizon.auth0.refreshToken",
        "maprizon.auth0.expiresAt",
        "maprizon.auth0.email",
        "maprizon.auth0.orgId",
    };

    /** Refresh the access token this many seconds before it actually expires. */
    private static final long REFRESH_SKEW_SECONDS = 120;

    private static final ViewerAuth INSTANCE = new ViewerAuth();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * Guards the credential fields below. Deliberately NOT the instance monitor:
     * {@link #getValidAccessToken()} makes a blocking token-refresh call, and the
     * EDT reads {@link #isLoggedIn()} / {@link #email()} to paint menu labels and
     * the layer name. Sharing one lock would freeze the UI for the length of a
     * network round trip (or its timeout). Every critical section here is a few
     * field assignments and never does I/O.
     */
    private final Object stateLock = new Object();
    /** Serializes refreshes so concurrent tile fetches don't each mint a token.
     * Held across HTTP; must never be taken while holding {@link #stateLock}. */
    private final Object refreshLock = new Object();

    // --- session-only credential state. Guarded by stateLock; never written to disk. ---
    private String accessToken = "";
    private String refreshToken = "";
    private long expiresAtEpochSeconds;
    private String email = "";
    private String orgId = "";
    /**
     * Bumped whenever a session ends OR begins (logout, a session Auth0 has
     * ended, a fresh interactive login). A refresh already in flight carries the
     * old generation and its result is DISCARDED — otherwise it would silently
     * re-establish a session the user just logged out of, or overwrite a newer
     * login (possibly another account's) with the previous session's tokens.
     */
    private long sessionGeneration;
    /**
     * Why the session ended WITHOUT the user asking (Auth0 rejected the refresh
     * token, or the access token expired with no refresh token). Null otherwise.
     * Taken once by {@link #takeSessionEndNotice()} so the user is told why their
     * private imagery disappeared instead of it silently going.
     */
    private String sessionEndNotice;

    private ViewerAuth() {
        purgePersistedTokens();
    }

    /**
     * Delete any tokens written to preferences by an earlier version.
     *
     * <p>Without this, upgrading would leave a refresh token sitting in
     * {@code preferences.xml} — unread by this build, but still a live credential
     * on disk. Runs once, when the singleton is first touched.
     */
    private static void purgePersistedTokens() {
        try {
            for (String key : LEGACY_TOKEN_PREFS) {
                Config.getPref().put(key, null); // null removes the entry outright
            }
        } catch (RuntimeException e) {
            // Preferences not initialized (unit tests / very early startup). There
            // is nothing to purge in that case, and this must never break login.
            Logging.debug("Maprizon: could not purge legacy token prefs: " + e);
        }
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

    /** True if we hold credentials (a refresh token, or a still-usable access token).
     * Session-only: always false at the start of a new JOSM session. */
    public boolean isLoggedIn() {
        synchronized (stateLock) {
            return !refreshToken.isEmpty() || !accessToken.isEmpty();
        }
    }

    /** The logged-in user's email, if known (for menu labels). May be empty. */
    public String email() {
        synchronized (stateLock) {
            return email;
        }
    }

    /**
     * The logged-in user's Auth0 organization id (e.g. {@code org_9alzx7S32reIQ86s}),
     * used to fetch the org's private per-facing PMTiles bake ({@code {org_id}-{facing}.pmtiles})
     * exactly as the viewer's {@code useMapTileUrls} does. Empty when unknown /
     * logged out. Back-fills from the current access token's claims if it was not
     * captured at login.
     */
    public String orgId() {
        synchronized (stateLock) {
            if (!orgId.isEmpty()) {
                return orgId;
            }
            String org = orgIdFromJwt(accessToken);
            if (org != null && !org.isEmpty()) {
                orgId = org;
                return org;
            }
            return "";
        }
    }

    /**
     * Which organization claim the current access token actually carries, and
     * whether it has expired — for the diagnostic shown when {@code /tiles/sign}
     * refuses us. Values are not exposed beyond the org id, which the UI already
     * displays; this reports PRESENCE, which is the part that decides the outcome.
     *
     * <p>It matters because the plugin and the server read DIFFERENT claims. This
     * class accepts Auth0's native {@code org_id} or the custom
     * {@code mikro/org_id}, while the server's {@code _require_org_id()} reads
     * {@code org_id} alone. A token carrying only the custom claim therefore looks
     * org-scoped here and org-less there: the plugin asks for private tiles, the
     * server answers 403 "no organization on token", and the user is told nothing.
     * If that is what is happening, this line says so outright.
     */
    public String orgClaimSummary() {
        String jwt;
        long expiresAt;
        synchronized (stateLock) {
            jwt = accessToken;
            expiresAt = expiresAtEpochSeconds;
        }
        if (jwt.isEmpty()) {
            return "no access token";
        }
        JsonObject claims = jwtPayload(jwt);
        if (claims == null) {
            return "token payload unreadable";
        }
        boolean nativeOrg = !claims.getString("org_id", "").isEmpty();
        boolean customOrg = !claims.getString("mikro/org_id", "").isEmpty();
        long now = System.currentTimeMillis() / 1000L;
        return "token org_id=" + (nativeOrg ? "present" : "ABSENT")
                + ", mikro/org_id=" + (customOrg ? "present" : "absent")
                + ", expired=" + (expiresAt > 0 && now >= expiresAt);
    }

    /** Forget all credentials. Fires a state-change so callers can refresh UI/imagery. */
    public void logout() {
        synchronized (stateLock) {
            clearCredentials();
            sessionEndNotice = null; // the user asked; nothing to explain
        }
        // Belt and braces: an install upgrading mid-session may still carry the old
        // on-disk copy, and "log out" must not leave a credential behind anywhere.
        purgePersistedTokens();
        fireChanged();
    }

    /**
     * The reason the session last ended without the user logging out, or null —
     * and clears it, so exactly one listener shows it. For the "you were logged
     * out" notice; the reason itself is already in {@code maprizon.log}.
     */
    public String takeSessionEndNotice() {
        synchronized (stateLock) {
            String notice = sessionEndNotice;
            sessionEndNotice = null;
            return notice;
        }
    }

    /** Caller must hold {@link #stateLock}. */
    private void clearCredentials() {
        accessToken = "";
        refreshToken = "";
        expiresAtEpochSeconds = 0L;
        email = "";
        orgId = "";
        sessionGeneration++;
    }

    /**
     * End a session that can no longer produce a token, unless it has already
     * ended or been replaced by a newer login while we were deciding.
     *
     * @return true if this call ended it — the caller must then
     *         {@link #fireChanged()}, outside every lock.
     */
    private boolean endSessionIfCurrent(long generation, String reason) {
        synchronized (stateLock) {
            if (sessionGeneration != generation) {
                return false;
            }
            clearCredentials();
            sessionEndNotice = reason;
        }
        MaprizonLog.warn("session ended — " + reason);
        return true;
    }

    // ------------------------------------------------------ token accessor

    /**
     * Return a currently-valid access token, refreshing silently if it is
     * expired (or about to be) and a refresh token is available. Returns
     * {@code null} when not logged in or when no valid token can be had —
     * callers MUST treat null as "anonymous" and fall back to the public path,
     * never error.
     *
     * <p>A session that can never produce a token again is ENDED here, exactly
     * as a logout would (credentials cleared, listeners fired): Auth0 rejecting
     * the refresh token, or an expired access token with no refresh token. Left
     * in place, {@link #isLoggedIn()} stayed true, the loader kept asking for
     * the org scope, and every tile and image retried a refresh that could only
     * fail again. A transient failure (network, timeout, 5xx, 429) keeps the
     * session: the next call simply tries again.
     *
     * <p>Blocking (may do a token-refresh HTTP call); call off the EDT.
     */
    public String getValidAccessToken() {
        String access;
        String refresh;
        long expiresAt;
        long generation;
        synchronized (stateLock) {
            access = accessToken;
            refresh = refreshToken;
            expiresAt = expiresAtEpochSeconds;
            generation = sessionGeneration;
            if (isFresh(access, expiresAt)) {
                return access;
            }
        }
        if (refresh.isEmpty()) {
            if (access.isEmpty()) {
                return null;
            }
            if (System.currentTimeMillis() / 1000L < expiresAt) {
                // Inside the refresh skew but still valid, and nothing to refresh
                // it with: use it while it lasts.
                return access;
            }
            if (endSessionIfCurrent(generation, "the access token expired and there is no refresh token")) {
                fireChanged();
            }
            return null;
        }

        String token = null;
        boolean ended = false;
        // One refresh at a time. Held across HTTP, which is why the EDT-facing
        // accessors above use stateLock instead of this.
        synchronized (refreshLock) {
            synchronized (stateLock) {
                // Another thread may have refreshed while we queued here.
                if (isFresh(accessToken, expiresAtEpochSeconds)) {
                    return accessToken;
                }
                if (sessionGeneration != generation) {
                    return null; // logged out (or logged in afresh) while we waited
                }
            }
            try {
                TokenResponse tr = refresh(refresh);
                token = storeTokensIfCurrent(tr, generation) ? tr.accessToken : null;
            } catch (TokenRejectedException e) {
                ended = endSessionIfCurrent(generation, "Auth0 rejected the refresh token ("
                        + e.getMessage() + ")");
            } catch (IOException e) {
                MaprizonLog.warn("token refresh failed, keeping the session to retry: " + e);
            }
        }
        // Outside refreshLock: listeners may call straight back into this class.
        if (ended) {
            fireChanged();
        }
        return token;
    }

    /** Caller must hold {@link #stateLock} for the expiry it passes in. */
    private static boolean isFresh(String access, long expiresAt) {
        return !access.isEmpty()
                && System.currentTimeMillis() / 1000L < expiresAt - REFRESH_SKEW_SECONDS;
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

    /** In-memory only, by design — see the class javadoc. Unconditional: used by
     * {@link #exchangeCode}, where a fresh interactive login IS the new session —
     * so it starts a new generation, and a refresh still in flight from the
     * previous session is discarded instead of overwriting these tokens. */
    private void storeTokens(TokenResponse tr) {
        synchronized (stateLock) {
            sessionGeneration++;
            sessionEndNotice = null;
            applyTokens(tr);
        }
    }

    /**
     * Store a refreshed token unless the session it belongs to has since ended.
     *
     * @return false if the user logged out while the refresh was in flight, in
     *         which case the token is dropped and the session stays ended.
     */
    private boolean storeTokensIfCurrent(TokenResponse tr, long generation) {
        synchronized (stateLock) {
            if (sessionGeneration != generation) {
                Logging.info("Maprizon: discarding refreshed token — the session ended or was replaced mid-refresh");
                return false;
            }
            applyTokens(tr);
            return true;
        }
    }

    /** Caller must hold {@link #stateLock}. */
    private void applyTokens(TokenResponse tr) {
        accessToken = tr.accessToken;
        if (tr.refreshToken != null && !tr.refreshToken.isEmpty()) {
            // Auth0 may or may not rotate the refresh token; only overwrite when present.
            refreshToken = tr.refreshToken;
        }
        expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + tr.expiresIn;
        // Refresh the org id from the new token's claims (authoritative; handles a
        // user who switched orgs). Only overwrite when the claim is present.
        String org = orgIdFromJwt(tr.accessToken);
        if (org != null && !org.isEmpty()) {
            orgId = org;
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
        JsonObject claims = jwtPayload(jwt);
        try {
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

    /** Decoded (UNVERIFIED — see {@link #orgIdFromJwt}) payload of a JWT we were
     * issued ourselves, or null if it is absent or unparseable. */
    private static JsonObject jwtPayload(String jwt) {
        if (jwt == null || jwt.isEmpty()) {
            return null;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            return parse(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            Logging.warn("Maprizon: could not decode token payload: " + e);
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
                    String who = root.getString("email", root.getString("name", ""));
                    synchronized (stateLock) {
                        email = who;
                    }
                }
            } else {
                ViewerApiClient.discardBody(res); // releases the connection
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
        int status = res.getResponseCode();
        if (status != 200) {
            JsonObject err = parse(content);
            String oauthError = err != null ? err.getString("error", "") : "";
            String msg = err != null ? err.getString("error_description", oauthError) : content;
            if (msg == null || msg.isEmpty()) {
                msg = String.valueOf(content);
            }
            if (msg.length() > 200) {
                msg = msg.substring(0, 200) + "…"; // e.g. a proxy's HTML error page
            }
            String detail = "HTTP " + status + ": " + msg;
            // Measured against the live tenant: a dead refresh token is
            // 403 {"error":"invalid_grant","error_description":"Unknown or invalid
            // refresh token."} (testbed/Auth0RefreshShapeProbe). A 4xx carrying an
            // OAuth error body is Auth0 refusing THIS request, which retrying cannot
            // change — except 408 and 429, which are about timing, not the
            // credential. A 4xx WITHOUT one (a captive portal or proxy page) is not
            // Auth0's verdict, so it is treated as transient.
            if (status >= 400 && status < 500 && status != 408 && status != 429
                    && !oauthError.isEmpty()) {
                throw new TokenRejectedException(detail);
            }
            throw new IOException(detail);
        }
        return parse(content);
    }

    /** Auth0 refused the request outright (a 4xx other than 408/429, with an OAuth
     * error body), as opposed to a network, proxy or server failure that may
     * succeed on retry. */
    static final class TokenRejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        TokenRejectedException(String message) {
            super(message);
        }
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
