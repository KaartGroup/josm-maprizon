# Maprizon JOSM Plugin — Auth & Public/Private Access Plan

**Status update (2026-07-14): the OPTIONAL LOGIN is now BUILT.** A JOSM user with a
Maprizon account can log in (Auth0 Authorization Code + PKCE, loopback redirect) to
view **private imagery** — signed image bytes + private sequences — on top of the
public data. Logged-out remains the default and fully-working state.

**What's built (this branch):**
- `oauth/ViewerAuth.java` — Authorization Code + PKCE (RFC 7636): builds the
  /authorize URL, exchanges the code for tokens, silent refresh, token/email
  persisted in JOSM prefs, login-state listeners, baked-in public client_id
  (`CLIENT_ID` constant, pref override for dev). Bearer JWT is exactly what the
  backend already accepts.
- `oauth/LoginFlow.java` — opens the browser to Auth0 and runs a one-shot
  `http://127.0.0.1:<port>/maprizon-callback` listener (loopback, RFC 8252) that
  captures the redirect — no code typing, no copy-paste. Reached only from the
  layer's **right-click menu → "Log in to Viewer (view private imagery)"** (and
  "Log out" when signed in).
- `io/ViewerApiClient` — now auth-aware: authed `POST /sequence/by-feature` when
  logged in (private sequences), else `/sequence/public/by-feature`; plus
  `resolveImageUrl()` → `POST /images/sign` (Bearer) for private image bytes. A
  null token always falls back to the public path, so anonymous never breaks.
- `gui/MaprizonImageDialog` — resolves each image via signing when logged in;
  clears its cache + reloads on login/logout.

**Verified against the live viewer backend (no server changes needed):**
`POST /backend/api/images/sign` and authed `POST /backend/api/sequence/by-feature`
both exist and are JWT-gated; Auth0 tenant `viewerdevelopment.us.auth0.com`,
audience `https://Viewer/api/authorize`, RS256 (server `flaskr/auth/auth.py`,
`views/Images.py`, `views/Sequence.py`).

**Config (verified 2026-07-14):** tenant **`dev-p6r3cciondp4has2.us.auth0.com`**
(confirmed as prod's tenant via the live `app.maprizon.com/api/auth/login`
redirect), audience `https://Viewer/api/authorize`, baked-in public client_id
`u5qORFUqta4x7NnujJ83QVRIJL50JzOa` (`ViewerAuth.CLIENT_ID`). No client secret.

**ONE remaining prerequisite (Auth0 dashboard — one-time by maintainer):** on that
Native app, register the loopback callback URLs
`http://127.0.0.1:{8765,8766,8767}/maprizon-callback` (currently unregistered — a
probe returns "Callback URL mismatch"), and enable Offline Access on the
`https://Viewer/api/authorize` API for refresh tokens.

**Still NOT built — private COVERAGE on the map:** login unlocks viewing private
images/sequences, but the map's coverage lines still come only from the public
per-facing PMTiles (public imagery). Drawing *private* coverage lines needs a
viewer-side private source (authed bbox→features endpoint or authed PMTiles) —
see open dependency #2 below. This is a separate, later piece.

---

**Original plan (below) — PLAN ONLY (2026-07-13).** Viewer-side dependencies for the
public-access ACL work are pending a conversation with Devin (public-map imagery
reachability + his new timed-workers method); those do NOT affect the login above.

## Guiding requirement (from Chris)

> The plugin, **by default, allows ANY user (no login) to see the PUBLIC data and view
> those images.** **Logging in to view PRIVATE data MUST be possible** — but it is
> strictly opt-in. A logged-out user gets a fully working public experience.

So the plugin has two modes, and **anonymous is the default and the common case**:

| Mode | Trigger | Coverage source | Image bytes |
|---|---|---|---|
| **Anonymous** (default) | no login | public per-facing PMTiles (current) | public images (see resolver below) |
| **Authenticated** (opt-in) | Auth0 login button | public + the user's private/org coverage | public images + private images via signed URLs |

---

## Architecture (mirrors the Mapillary plugin's proven split)

```
oauth/   Auth0 device-authorization flow (same flow the desktop uploader uses)
io/      HTTP client; image URL resolver; sequence/frame-list fetch
cache/   LRU image-bytes cache (smooth sequence walking)
gui/     ImageViewerDialog (docked ToggleDialog side panel) + login state
data/    ImageryFeature (have it) + Sequence frame-list model
layer/   MaprizonLayer coverage (have it)
```

### 1. Auth module (`oauth/`)
- **OAuth 2.0 Device Authorization flow**, identical in shape to the viewer desktop
  uploader (both apps already share Auth0; JWTs are interchangeable per the viewer
  `CLAUDE.md` "Authentication" section).
- Login/logout lives in plugin **Preferences** + a header button in the image panel
  (Mapillary does exactly this). Logged-out is the default state.
- Caches the JWT; refreshes before expiry. **When no token is present the whole plugin
  operates anonymously — no code path may hard-require a token.**

### 2. Image URL resolver (`io/`) — the key abstraction
Given a feature's raw `img` URL (already carried in the PMTiles point features, read via
`ImageryFeature.getImg()`), produce a fetchable URL. Resolution order:

1. **If logged in:** `POST {VIEWER}/backend/api/images/sign` with `Authorization: Bearer <jwt>`
   → `{ url, expires_at }`; GET that. Cache until ~10 min before `expires_at`
   (mirrors the frontend `useSecureImageUrl`). Works for private *and* public images.
2. **If anonymous (default):** use the **public resolver** (see dependency below).
3. **Fallback:** raw `img` GET (`signed || raw`, exactly like `useSecureImageUrl.js:81`).

> **The public resolver is deliberately pluggable**, because how anonymous users reach
> public image bytes is a *viewer-side* decision still open (Devin, tomorrow):
> - **If viewer option (b)** — publish flips objects to `public-read`: the public
>   resolver is a **plain HTTP GET** on the raw `img` URL. Zero API calls.
> - **If viewer option (c)** — a JWT-exempt `/images/sign/public` gated to `trips.public`:
>   the public resolver **calls that endpoint (no auth)** and GETs the signed URL.
>
> Either way the plugin change is confined to one class. Build P1 against (b)/raw with a
> clean seam so swapping to (c) is a one-file change.

### 3. Sequence resolution (`io/`)
Tiles are decimated (representative grid per zoom; sequence lines carry `img=NULL`), so to
walk every frame, resolve the clicked point's `sequence_id`+`trip_id`+`facing` to the full
ordered frame list:
- **Anonymous:** `POST /backend/api/sequence/public/by-feature` (JWT-exempt, gated to
  `trips.public`).
- **Authenticated:** `POST /backend/api/sequence/by-feature` (Bearer JWT; serves private).

### 4. Image viewer panel (`gui/`)
- A docked JOSM **`ToggleDialog`** showing the resolved JPEG + metadata (facing, sequence,
  index, timestamp, heading).
- Click a coverage point → resolve → display; highlight the active frame on the map.
- **Prev/Next** through the sequence + optional **walk mode** (auto-advance on interval),
  matching Mapillary. Arrow keys move both the map highlight and the panel image.

### 5. Coverage layer (`layer/`) — mostly built
- Anonymous: public PMTiles (done). **Still needs the #3 overzoom fix** so zoomed-in
  clicking hits real data (archive header lies: max=16, real deepest tile = z15).
- Authenticated: additionally surface the user's private coverage. **Source TBD** — the
  public PMTiles only contain `trips.public` imagery; a private/org coverage source
  (authed tiles or an authed feature query) needs confirming (dependency, below).

---

## Build phasing (once approved — NOT yet)

- **P0 (unblocked now):** land the #3 overzoom fix; commit the already-built styling +
  selection fixes. This makes the public coverage layer solid regardless of auth.
- **P1 — anonymous public image viewer:** ToggleDialog panel + sequence walk, using the
  public resolver (raw GET) + public sequence endpoint. Delivers the whole "click → see the
  image in JOSM" UX for public data with **no login**. This is the core of what Chris wants.
- **P2 — opt-in login for private data:** Auth0 device flow + `/images/sign` (private
  images) + authed sequence endpoint + private coverage source. Login button in prefs;
  everything in P1 keeps working logged-out.

---

## Open dependencies to settle with Devin (viewer side — not plugin work)

1. **How anonymous users reach PUBLIC image bytes.** Confirmed today: marking a trip public
   (`modify_trip`, `Project.py:417`) only flips the `Trip.public` DB boolean — it does
   **not** change S3 object ACLs. So privately-uploaded footage, even once published, is
   **not** anonymously fetchable (raw `img` → 403). Currently-visible public imagery works
   only because it predates the private-upload switch. Needs option **(b)** per-object ACL
   flip on publish (via Devin's timed-workers) **or** option **(c)** public signing endpoint.
   → Decides the plugin's public resolver (raw GET vs public-sign call).
2. **Private coverage source for authenticated mode** — is there an authed PMTiles / feature
   endpoint that returns private (non-public) coverage, or does the plugin stay public-only
   for coverage and just add private *image viewing*?
3. **Migrate the viewer-side ACL-flip job to Devin's new timed-worker method** (if option b).

## What is NOT changing
- No viewer/server code from the plugin side.
- No bucket-level or directory-level permission changes — ACL work (if any) is per-object,
  enumerated from `mapbox_features` per `trip_id` (NOT by S3 prefix — the
  `Images/{country}/{city}/{date}/{facing}/` path can mix multiple trips), and it lives on
  the viewer side, owned by Devin's timed-workers.
