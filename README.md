# Maprizon JOSM Plugin

A JOSM plugin that shows [Maprizon](https://viewer.kaart.com) street-level
imagery sequence coverage as a toggleable map layer, colored by camera facing,
with a one-click "View in Maprizon" action that opens the exact clicked
image in a browser.

## Phase 1 scope

- A toggleable `MaprizonLayer` (Tools menu -> "Maprizon Coverage", or
  `Alt+Shift+K`) that adds/removes an initially empty coverage layer. Toggling
  the layer no longer auto-dumps coverage; you download coverage into it
  explicitly (see next bullet).
- Coverage is downloaded **explicitly and scoped to the current view**,
  JOSM-style, via **Tools menu -> "Download Maprizon coverage (current view)"**
  (shortcut **`Alt+Shift+D`**) or the layer's right-click menu ->
  "Download Maprizon coverage in current view". Downloads **accumulate** -
  fetching a new area adds to what's already shown (tiles already fetched are
  skipped), the way OSM data grows as you download more; the layer menu's
  "Clear downloaded coverage" resets it. The tile zoom is chosen to match the
  on-screen resolution (zoomed in -> per-image detail at z16; zoomed out ->
  generalized sequence lines), clamped to the archive's zoom range (z2-z16).
  "Auto-refresh on pan" is **OFF by default**; a layer-menu toggle re-enables a
  live mode that re-downloads around the view as it changes.
- Coverage is colored by facing (see "Color choices" below) and can be shown
  or hidden per facing from the layer's right-click menu.
- Right-click on the layer in the Layers panel -> "View in Maprizon" opens
  the nearest clicked coverage point's image in your default browser via a
  deep link into viewer.kaart.com.
- Data source: the public, per-facing PMTiles files at
  `https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-{facing}.pmtiles`
  for `facing` in `front`, `left`, `right`, `360`, `still`. No backend changes
  needed; this plugin only reads these files over plain HTTPS range requests.

## What's NOT built yet (explicitly out of scope for Phase 1)

- **Reverse sync (JOSM -> Viewer)**: no way to push edits, tags, or selections
  from JOSM back into Viewer.
- **Changeset attribution**: coverage viewing carries no changeset/authorship
  linkage; this is a read-only visualization layer.
- **Private coverage *lines* on the map**: the coverage layer is still drawn
  only from the *public* per-facing PMTiles. Logging in (see "Optional login"
  below) unlocks viewing private *images/sequences*, but private sequences do
  not yet appear as clickable lines on the map — that needs a viewer-side
  private coverage source (authed bbox→features endpoint or authed PMTiles).
- On-demand, view-scoped coverage download is present but intentionally
  simple (see "Known limitations" below) - it is not a full vector-tile
  rendering engine.

## Build / install

Prerequisites: JDK 17+ and Apache Ant. The plugin compiles to Java 17 bytecode
and targets JOSM 19481+.

**Compile-only dependency — JOSM core.** `lib/josm-custom.jar` (JOSM itself) is
NOT tracked in git — it's provided by the JOSM runtime and only needed to
compile against. Download the current JOSM jar and drop it in as
`lib/josm-custom.jar` before building:

```bash
curl -fSL https://josm.openstreetmap.de/download/josm-tested.jar -o lib/josm-custom.jar
```

```bash
# Build the plugin jar (Maprizon.jar)
ant clean dist

# Install to your local JOSM (macOS: ~/Library/JOSM/plugins/,
# Windows: %APPDATA%/JOSM/plugins/, Linux: ~/.josm/plugins/)
ant install
```

Then restart JOSM. The plugin appears under `Preferences -> Plugins` as
"Maprizon", and a dedicated **Maprizon** menu is added to the menu bar with
"Maprizon Coverage" (toggle layer) and "Download Maprizon coverage" entries.

## Architecture

```
src/org/openstreetmap/josm/plugins/maprizon/
  MaprizonPlugin.java          - main plugin class (org.openstreetmap.josm.plugins.Plugin)
  FacingStyle.java                - per-facing PMTiles URLs + colors + deep-link facing whitelist
  actions/
    ToggleMaprizonLayerAction.java  - JosmAction, adds/removes the (empty) layer
    DownloadMaprizonCoverageAction.java - JosmAction, explicit view-scoped
                                          coverage download (Alt+Shift+D)
  data/
    ImageryFeature.java           - one decoded coverage feature + its Viewer properties
  layer/
    MaprizonLayer.java         - the map layer: explicit view-scoped download +
                                 accumulation/render/context-menu/deep-link
  pmtiles/
    TileMath.java                 - slippy-map tile <-> lon/lat math (incl. MVT local-coord conversion)
    PmtilesTileLoader.java        - real PMTiles fetch + MVT decode (see "PMTiles/MVT decoding" below)

testbed/PmtilesFetchTest.java     - standalone throwaway test proving the fetch+decode path
                                     against the LIVE public tile URL (not part of the built plugin)
```

## PMTiles/MVT decoding - what's real vs. what was verified live

This was the highest-risk part of Phase 1, and it was **fully verified against
the live tile server**, not assumed:

1. Wrote `testbed/PmtilesFetchTest.java`, a standalone `main()` with no
   dependency on the rest of the plugin.
2. Ran it against the real, live URL
   `https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-front.pmtiles`
   over the network (network access to DigitalOcean Spaces was NOT blocked in
   this sandbox).
3. It fetched the PMTiles header (min/max zoom 2-16, world bounds, gzip tile
   compression), located a populated tile, decoded it as MVT, and printed a
   real decoded feature's properties. Actual observed output:

   ```
   Header parsed OK.
     minZoom=2 maxZoom=16
     bounds=[-180.0, -85.0, 180.0, 85.0]
     center=[0.0, 0.0] centerZoom=2
     tileType=1 tileCompression=2
   found populated tile at offset (1,0)
   Fetched tile: 245 raw bytes.
   Gunzipped to: 261 bytes.
   Decoded MVT layers: [imagery]
   Layer 'imagery' feature count: 1
     feature geom type=LineString coord=(767.0, 285.0, NaN) properties={sequence_id=2940, trip_id=779,
       facing=front, upload_batch_id=dca3fb83344bddd564f3736879850abc, vehicle_id=Ipad1, day_id=day13,
       timestamp=2025-11-13T09:43:02}
     -> sequence_id = 2940
   RESULT: SUCCESS
   ```

   Note the low-zoom (z=2) tile's world-scale coverage feature decoded as a
   `LineString`, not a `Point` - the vector tileset generalizes sequences to
   lines at low zoom. `MaprizonLayer`/`PmtilesTileLoader` handle both
   uniformly (a "feature" is just an ordered list of lon/lat vertices; a point
   is simply a 1-vertex case).

4. Since the standalone test fully worked, this exact code path (PMTiles
   fetch -> gunzip -> MVT decode -> lon/lat reprojection) was wired directly
   into `PmtilesTileLoader`, which the real `MaprizonLayer` uses. **This is
   real, working code, not a stub** - it was proven end-to-end before being
   used in the layer.

### Libraries used (fetched directly from Maven Central, no Maven/Gradle used)

No JOSM-plugin-friendly PMTiles or MVT decoder existed in this repo or
CR_PLUGIN, so Maven Central was searched for both container format (PMTiles)
and payload format (MVT) decoders, and both turned out to be directly
fetchable via plain `curl` against `repo1.maven.org` (no dependency manager
needed - jars were downloaded straight into `lib/`):

- `ch.poole.geo.pmtiles-reader:Reader:0.3.6` - a real PMTiles container reader
  (by Simon Poole, a JOSM core/ecosystem developer), including an
  `HttpUrlConnectionChannel` that lets `Reader` do HTTP range-request reads
  directly against a remote URL as if it were a local `FileChannel`. This
  meant a PMTiles reader did **not** need to be hand-written.
- `com.wdtinc:mapbox-vector-tile:3.1.0` (+ its own deps `org.locationtech.jts:jts-core:1.15.1`,
  `com.google.protobuf:protobuf-java:3.5.1`, `org.slf4j:slf4j-api:1.7.25`) -
  decodes the protobuf-based MVT tile format into JTS geometries + a
  properties map per feature.
- `org.jetbrains:annotations:24.0.1` - runtime-scope annotation dependency of
  the PMTiles reader.

All six jars are bundled into `lib/` and into the shipped plugin jar itself
(see "Build system notes" below) so the plugin is self-contained.

## Layer + context-menu API - confirmed via `javap` vs. inferred

Per the task's requirement, JOSM API signatures were verified against the
actual `lib/josm-custom.jar` with `javap`, not guessed from memory or web
search. Confirmed via `javap`:

- `Layer` (abstract class): constructor `Layer(String)`; abstract methods
  implemented by `MaprizonLayer`: `getIcon()` (returns `javax.swing.Icon`),
  `getToolTipText()`, `mergeFrom(Layer)`, `isMergable(Layer)`,
  `visitBoundingBox(BoundingXYVisitor)`, `getInfoComponent()` (returns
  `Object`), and **`getMenuEntries()` returns `javax.swing.Action[]`** (this
  was the one signature detail the task specifically flagged as needing
  verification - confirmed exactly that return type).
- `AbstractMapViewPaintable` / `MapViewPaintable`: the paint contract is
  `void paint(Graphics2D, MapView, Bounds)` - confirmed by reading both the
  interface and by cross-checking `NoteLayer`/`GpxLayer`'s own `paint(...)`
  override signatures, which match exactly.
- `NoteLayer` was read as the "simple concrete Layer subclass" reference the
  task asked for: it extends a layer base class, implements `MouseListener`
  directly, registers itself via `hookUpMapView()`, and overrides `paint(...)`
  - `MaprizonLayer` follows the same shape (extends `Layer` directly
  rather than `AbstractModifiableLayer`/`GpxLayer`, since coverage is
  read-only and has no save/upload semantics to support).
- `Layer.SeparatorLayerAction.INSTANCE` (a static singleton `Action`) and
  `LayerListDialog.getInstance().createShowHideLayerAction()` /
  `.createDeleteLayerAction()` - all confirmed via `javap`, used in
  `getMenuEntries()` for the standard "show/hide" and "delete layer" entries.
- `JosmAction` constructors, `ImageProvider(String)`, `Shortcut.registerShortcut(...)`,
  `MainApplication.getLayerManager().getLayersOfType(Class)`, `Bounds` methods
  (`extend`, `contains`, `getMin`/`getMax`/`getMinLat` etc.),
  `NavigatableComponent.getPoint(LatLon)` / `getLatLon(int,int)` /
  `getRealBounds()` - all confirmed via `javap` against the jars actually
  compiled against (see the `javap` invocations that were run during
  development; not re-included here for brevity).

Nothing in the shipped code relies on an *inferred/guessed* signature that
wasn't also checked against `lib/josm-custom.jar` - anywhere the CR_PLUGIN
`src_backup` reference pattern was followed (e.g. the overall shape of
`JosmAction` subclasses, `Plugin` subclassing), the actual method signatures
used were still independently confirmed via `javap`, not copied blind.

## Color choices

No existing Kaart color scheme for camera facings was found in either this
repo's plan docs or in CR_PLUGIN, so these were picked fresh for maximum
visual separation against typical JOSM backgrounds (aerial imagery / OSM
line-work):

| Facing | Color | Hex |
|---|---|---|
| front | deep orange | `#E64A19` |
| left | blue | `#1976D2` |
| right | green | `#388E3C` |
| 360 | purple | `#7B1FA2` |
| still | amber | `#FBC02D` |

## Deep link

Built exactly per spec:

```
https://viewer.kaart.com/?sequence_id=<int>&sequence_index=<int>&facing=<front|left|right|360>&trip_id=<id>&upload_batch_id=<id>&feature_timestamp=<ISO ts>#mapHash=16.9/<lat>/<lng>
```

Only `sequence_id` + `lat`/`lng` are required; other fields are included only
when present on the clicked feature. Per spec, the `facing` query parameter
only accepts `front`/`left`/`right`/`360` - **not** `still` - so for a `still`
facing feature the `facing` parameter is simply omitted from the link (every
other field is still included normally).

## Optional login (view private imagery)

By default the plugin is fully anonymous — public coverage + public images, no
login. A JOSM user with a Maprizon account can **optionally** log in to also view
**private** imagery (signed image bytes + private sequences):

- Right-click the layer in the Layers panel → **"Log in to Viewer (view private
  imagery)"**. This runs the OAuth 2.0 **Authorization Code flow with PKCE**
  (RFC 7636) over a **loopback redirect** (RFC 8252): your browser opens to Auth0,
  you approve, and it redirects straight back to a one-shot `http://127.0.0.1`
  listener the plugin runs — **no code to type, no copy-paste**. No client secret
  is stored (the public client_id is baked into the jar). "Log out of Viewer"
  appears once signed in.
- When logged in, sequence resolution uses the authed
  `POST /backend/api/sequence/by-feature` (serves private trips) and image bytes
  are fetched through `POST /backend/api/images/sign` (short-lived pre-signed
  URLs). Logged out, both fall back to the public path automatically.
- Tokens (access + refresh + expiry + email) are persisted via JOSM preferences,
  so login survives restarts; the access token is refreshed silently before expiry.

**One-time Auth0 setup (dashboard, not code — done by the maintainer, never the
user):** a **Native** Auth0 application in the **`dev-p6r3cciondp4has2.us.auth0.com`**
tenant (the tenant prod `viewer.kaart.com` validates against — verified via the live
login redirect), with these **Allowed Callback URLs** registered (one per loopback
port the plugin may bind):

```
http://127.0.0.1:8765/maprizon-callback
http://127.0.0.1:8766/maprizon-callback
http://127.0.0.1:8767/maprizon-callback
```

Its **public client_id is baked into the plugin** (`ViewerAuth.CLIENT_ID` —
currently `u5qORFUqta4x7NnujJ83QVRIJL50JzOa`) — end users never see or enter it.
(A dev can override via the `maprizon.auth0.clientId` preference without a
rebuild.) Enable **Allow Offline Access** on the `https://Viewer/api/authorize`
API so refresh tokens are issued.

**Scope note:** login unlocks *viewing* private images/sequences, but private
coverage *lines* are not yet drawn on the map (the coverage layer reads only the
public PMTiles). That's a separate, viewer-side follow-up.

## Known limitations / honest gaps (read before relying on this for real work)

- **Coverage download is on-demand and intentionally simple.** Coverage is
  fetched only when you explicitly ask for it (Tools -> "Download Maprizon
  coverage (current view)" / `Alt+Shift+D`, or the layer menu's
  "Download Maprizon coverage in current view"), scoped to the current view.
  The tile zoom is chosen to **match the on-screen resolution** (zoomed in ->
  per-image detail at z16; zoomed out -> generalized sequence lines), clamped
  to the archive's actual min/max zoom (z2-z16) - replacing the old
  "~6 tiles across" heuristic and coarsen-to-min-zoom loop that could collapse
  to world zoom. Downloads **accumulate**: a new download adds to what's
  already loaded and tiles already fetched are skipped (the layer menu's
  "Clear downloaded coverage" resets everything). There is an **"area too
  large" guard** - if the current view would need more than 256 new tiles
  (summed across enabled facings) the download is refused with a prompt to
  zoom in and try again, instead of coarsening; 256 is a tunable cap.
  "Auto-refresh on pan" is **OFF by default**; a layer-menu toggle re-enables
  a live mode that re-downloads around the view as it changes, using the same
  scoped-download path. This is a real, working strategy, but it is not a
  production-grade vector tile renderer (no tile-level caching across zoom
  changes, no partial/incremental redraw, no cancellation of an in-flight
  load if the view moves again mid-fetch).
- **Nearest-point search for "View in Maprizon"** is a simple linear scan
  over currently-loaded, currently-enabled features using planar (not
  geodesic) distance - fine at city scale, not exact at large view extents.
- **No antimeridian/dateline handling** in the tile-range computation.
- The bundled placeholder icon (`images/maprizon.png`) is a small,
  deliberately simple generated PNG (a blue circle + bar, meant to evoke a
  camera lens) - **not polished art**. Swap it out before any real release.
- Dependency jars are bundled by unzipping their classes directly into the
  plugin jar at `ant dist` time (see below) rather than via a proper shaded
  build - functionally fine, but means `Maprizon.jar` is ~2.4 MB instead
  of a few KB.

## Build system notes

- `build.xml`/`build-common.xml`/`plugin.properties` are adapted from the
  sibling `CR_PLUGIN` repo's Ant setup. Removed: the `graphview.jar`
  dependency fileset and `plugin.requires` property (this plugin has no such
  dependency).
- `plugin.main.version` was left at CR_PLUGIN's own value, `18000`, per the
  task instructions (they matched, so no discrepancy to flag).
- Like CR_PLUGIN, `plugin.properties` is **not** read by `build.xml` at build
  time - both files declare the same values independently (this mirrors
  CR_PLUGIN's own structure exactly; keep them in sync by hand if you change
  one).
- Because there's no Maven/Gradle here, the `dist` target has a `bundle-deps`
  step that unzips every third-party jar in `lib/` (**except**
  `josm-custom.jar`, which JOSM supplies at runtime and must not be bundled)
  directly into `build/classes` before jarring, producing one self-contained
  `Maprizon.jar`.
- `ant clean dist` was actually run during development and produces a clean
  build with zero compile errors (only expected `-source 8`/`-target 8`
  "obsolete" deprecation warnings from the JDK 21 compiler, and one
  `new URL(String)` deprecation warning in `PmtilesTileLoader`). `ant install`
  was also actually run and verified to copy the jar to
  `~/Library/JOSM/plugins/Maprizon.jar` on macOS (that verification copy
  was removed afterward - this is a development check, not a real
  installation).
