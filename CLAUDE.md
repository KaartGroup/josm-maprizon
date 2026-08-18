# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A JOSM plugin (Java 17, plain Ant, no Maven/Gradle) that renders Maprizon
street-level imagery coverage as a JOSM map layer and shows the actual photos in
a docked side panel. Coverage comes from remote **PMTiles** archives read over
HTTP range requests; photos and sequences come from the Maprizon/Viewer Flask
backend at `https://app.maprizon.com/backend/api/`.

## Build / install / run

JOSM core is a compile-only dependency and is **not** in the repo (gitignored) —
fetch it before the first build:

```bash
curl -fSL https://josm.openstreetmap.de/download/josm-tested.jar -o lib/josm-custom.jar
ant clean dist      # -> Maprizon.jar at the repo root
ant install         # copies the jar into the local JOSM plugins dir (mac: ~/Library/JOSM/plugins)
```

`dist` unpacks every jar in `lib/` except `josm-custom.jar` into `build/classes`
and jars them in (no shade plugin under plain Ant) — JTS, mapbox-vector-tile,
protobuf, slf4j, pmtiles-reader. Adding a runtime dependency means dropping the
jar in `lib/`; nothing else.

JOSM only reads plugin jars at startup, so **restart JOSM after every
`ant install`** — otherwise you are testing the previous build. `MaprizonVersion`
reads the running version from the **jar manifest** (which `build.xml` stamps) and
prints it in the download notification and the log precisely so you can tell. It is
read, never hardcoded: the string whose whole job is detecting a stale jar must not
be a hand-synced constant that can itself go stale and confirm a version that is not
running. (This replaced `MaprizonLayer.BUILD_TAG`, which had exactly that failure.)

There is no unit-test framework and no CI. Verification is done with **throwaway
probes in `testbed/`** (gitignored) that run against the live archives:

```bash
javac -cp lib/josm-custom.jar:lib/pmtiles-reader-0.3.6.jar:build/classes -d /tmp/probe testbed/CoverageProbe.java
java  -cp lib/josm-custom.jar:lib/pmtiles-reader-0.3.6.jar:build/classes:/tmp/probe CoverageProbe
```

Each probe's header comment carries its own exact command and states what it
proves. Write a new probe rather than reasoning about tile behavior on paper —
that is the established pattern here, and several of the fixes in git history
exist because a probe contradicted a plausible assumption.

### Runtime log

The plugin writes to `<JOSM user data dir>/maprizon.log` (`MaprizonLog`), beside
`plugins/`. That file, not the console, is where failures land — JOSM keeps no
log of its own and `Notification`s fade. **Never log a credential**: presigned
tile URLs carry their signature in the query string (`PmtilesArchive.redact`),
and tokens must never appear.

### Releasing

Version lives in two hand-synced places — bump both:
`build.xml` (`plugin.version`, the copy that actually drives the manifest) and
`plugin.properties` (documentation only, not read at build time). Everything the
running plugin reports comes from the manifest via `MaprizonVersion`, so there is no
third copy to forget. `PluginsSource-edited.txt` is the JOSM wiki plugin list this
jar's download URL is registered in.

## Work intake (Trello)

Work for this repo is tracked on its own board — **Maprizon/JOSM Plugin**,
https://trello.com/b/FSwceNHE (`6a7a1808cbf3b124fecbbc4d`). It was split out of
the Maprizon/Viewer board on 2026-08-10; do not file JOSM plugin work on the
Viewer board.

Two skills drive it, following the same pattern as the other Kaart projects:

- `/josm-trello-monitor` — scan intake, analyze cards, build the work queue
- `/josm-daily-briefing` — board status, aging, release state

All IDs live in the SSOT at `~/.claude/notification-config.json` under the
`josm-maprizon` key — read them from there rather than hardcoding.

This board is deliberately shorter than the other five, which changes three
things a standard read of it would get wrong:

- Intake is **Feature Requests** and **Fixups** — there is no Other Changes / UI
  Changes / Bug Reports split, and **no guideline cards to skip**.
- There is **no Blocked list**. A blocked card stays where it is with `BLOCKED:`
  starting its name and the `NEED CLARIFYING` label. Strip the prefix when the card
  moves — the Auth0 dashboard prerequisite carried it into Complete and read as
  blocked work for a week.
- There is **no Ready to Deploy**, because shipping here is a release, not a
  deploy: bump the two hand-synced versions, build, and hand-install — the JOSM
  plugin-directory listing is still an open card.

Never auto-post to a card. Draft questions and summaries in chat first; post only
what you're told to post. Before scoping any card, read the "Load-bearing
invariants" below and check `docs/*_PLAN.md` — those plans record measurements
already taken and approaches already rejected (e.g. PMTiles `getBounds()` for
coverage extents), and re-proposing a rejected one is the usual way a cycle gets
wasted here.

## Architecture

Package root: `org.openstreetmap.josm.plugins.maprizon`.

```
MaprizonPlugin      entry point; builds a top-level "Maprizon" menu, registers MaprizonImageDialog per map frame
 actions/           JosmActions: toggle layer, download current view, diagnostics, help
 layer/MaprizonLayer   the JOSM Layer: download planning, paint, click->select, right-click menu (2.1k lines, the core)
 pmtiles/           PmtilesArchive + PmtilesDirectory + TileId + TileMath + PmtilesTileLoader (fetch & decode)
 io/ViewerApiClient    backend calls: sequences, image signing, tile presigning, diagnostics probes
 oauth/             ViewerAuth (PKCE token state) + LoginFlow (loopback listener)
 gui/               MaprizonImageDialog (docked photo panel) + PanoramaPanel (360 viewer)
 data/ImageryFeature   one decoded coverage feature + its Viewer properties
 FacingStyle        the five facings, their colors, and the PMTiles URL scheme
```

### Data flow

`DownloadMaprizonCoverageAction` → `MaprizonLayer.downloadCurrentView()` → on the
single `maprizon-tile-loader` thread, plan a tile range at the **screen-matched
zoom** → `PmtilesTileLoader.loadTileOrNull(scope, facing, z, x, y)` → HTTP range
read from the archive → gunzip → MVT decode (JTS) → `ImageryFeature`s in lon/lat →
merged onto the EDT and painted.

### Load-bearing invariants

These are the things that were each a user-visible bug at some point; the
javadoc at each site explains why in detail. Read it before changing behavior.

- **Never use `ch.poole.geo.pmtiles.Reader`.** Its `Hilbert.zxyToIndex` truncates
  to `int`, so every tile from z16 up is unreachable. `PmtilesArchive` +
  `TileId` replace it. `TileId`'s inverse Hilbert is validated by round-tripping
  through the library's forward function over real archives (`testbed/CoverageProbe.java`) —
  do not "simplify" it without re-running that.
- **Follow leaf directories.** A root entry with `runLength == 0` is a pointer to
  a leaf directory, not "no tile". Public bakes are small enough to have none, so
  root-only lookups looked correct while large org bakes read as empty.
- **Scopes are a union, not a swap.** Logged in with an org, the loader reads the
  org bake *and* `public_imagery-*` (`PmtilesTileLoader.currentScopes()`). The
  baker scopes an org archive by `org_id` only, so serving one scope made logging
  in *delete* coverage. Every cache key is scope-qualified.
- **Org tilesets are private; public ones are not.** Public archives are fetched
  from their plain URL; org archives only work through `ViewerApiClient.signedTileUrls()`,
  used **verbatim** (the signature is in the query string). A 403 is a typed
  `PmtilesForbiddenException` that triggers exactly one re-sign + retry.
- **Login is strictly optional and never persisted.** Tokens live in memory on
  the `ViewerAuth` singleton for one JOSM session; `purgePersistedTokens()`
  deletes anything an older build wrote to preferences. Every authenticated path
  falls back to the public path on failure rather than erroring — which is why
  `ShowMaprizonDiagnosticsAction` and `ViewerApiClient.lastSignFailure()` exist:
  the fallback is correct but makes causes indistinguishable from outside.
- **Every facing renders at every zoom.** Zoom thresholds must not decide which
  facings load — a zoom gate was the whole of the "only 360 shows up" report.
  `USABLE_ZOOM` now only gates opt-in auto-refresh. Request zoom is screen-matched,
  so a zoomed-out view costs the same tile count; `MAX_TILES_PER_DOWNLOAD` is the
  actual bandwidth guard, and it says so out loud instead of silently dropping data.
- **Level of detail is per-feature, not layer-wide.** Coarse geometry stops
  drawing only where a *finer request over its own ground* succeeded
  (`finerDataFetchedOver`, keyed on `ImageryFeature.getRequestZoom()`, never the
  decoded `sourceZoom`). A layer-wide "finest zoom" number hid shallow-baked
  facings permanently.
- **Overzoom walks up and picks the finest good-enough level.** `loadWithOverzoom`
  collects every present ancestor, then keeps the deepest whose clipped count is
  ≥ `FINE_ENOUGH_FRACTION` of the best — a plain "most features wins" rule drags
  fine geometry down to a decimated coarse parent. Results are clipped to the
  *requested* tile, never to the view, which is what makes the `loadedTileKeys`
  ledger truthful.
- **Threading.** All paint/menu/merge work is on the EDT; all network work is on
  the loader thread, `maprizon-diagnostics`, or the dialog's pool. `ViewerAuth`
  deliberately uses a separate `stateLock` (field writes only, never I/O) from its
  `refreshLock` (held across HTTP) so the EDT never blocks on a token refresh.

### Cross-repo coupling

The plugin mirrors the `viewer-2-0` web client deliberately: facing colors match
`MapComponents.js`/`sequenceLayerStyles.js`, the scope choice mirrors
`useMapTileUrls.js`, and the sign-refresh buffer mirrors its `SIGN_REFRESH_BUFFER_MS`.
Server-owned values (notably the signing TTL in `server/flaskr/views/Tiles.py`) are
deliberately **not** mirrored — `expiresAt` is always taken from the response.
`docs/` holds the design plans, including per-doc status headers.
