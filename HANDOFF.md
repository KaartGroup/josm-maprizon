# Handoff — Maprizon JOSM plugin

State as of **2026-10-13**, written for whoever picks this repo up cold. The
day-to-day developer guide (architecture, load-bearing invariants, probes) is
[CLAUDE.md](CLAUDE.md); the user-facing guide is [README.md](README.md). This file
covers what those two don't: where things live, how a release actually reaches
users, what is unfinished, and what access you need.

## What it is

A JOSM plugin (Java 17, plain Ant) that draws Maprizon street-level imagery
coverage as a map layer and shows the photos in a docked panel. Public imagery
works with no account; an optional Auth0 login adds the user's organization's
private imagery. Repo is **public**: `github.com/KaartGroup/josm-maprizon`,
default branch `main`. License GPL-2.0-or-later.

Current version: **1.0.30** (`build.xml` / `plugin.properties`, the `Release 1.0.30`
commit, 2026-10-30). 1.0.23–1.0.25 were local builds that never shipped.

## Build, run, test from a clean machine

Needs a JDK 17+ (verified with JDK 21) and Apache Ant (`brew install ant`). Then:

```bash
curl -fSL https://josm.openstreetmap.de/download/josm-tested.jar -o lib/josm-custom.jar
ant clean dist      # -> Maprizon.jar at the repo root (gitignored)
ant install         # copies it into the local JOSM plugins dir
```

Verified 2026-10-30 (JDK 21, `release="17"`, compile jar JOSM r19613 — the build
refuses anything older than `plugin.main.version`): builds clean, manifest says
`Plugin-Version: 1.0.30`, `Plugin-Mainversion: 19481`, classes are Java 17 bytecode.

Restart JOSM after every install (it reads plugin jars only at startup). There
are **no unit tests and no CI** — verification is throwaway probes in `testbed/`
(gitignored, so they exist only on Chris's machine; see CLAUDE.md for the
pattern) plus a live run in JOSM. Failures go to `<JOSM user data dir>/maprizon.log`.

## How a release reaches users

There are three distribution channels, and **each is updated separately** on every release:

| Channel | What it serves now | How it is updated |
|---|---|---|
| Maprizon web app download link (`GET /backend/api/upload/uploader_downloads`, env var `MAPRIZON_JOSM_PLUGIN_URL` on the api; client fallback in viewer `client/src/config.js`) | `Maprizon.jar.zip` on the Kaart DigitalOcean Spaces CDN (`kaart.sfo3.cdn.digitaloceanspaces.com/Uploader/`) | Zip the jar, upload to Spaces, update the env var if the path changes. **DO Spaces access needed.** |
| GitHub Releases (`releases/latest/download/Maprizon.jar`) | v1.0.29 (2026-10-29); JOSM's plugin list verified 2026-10-30 to serve 1.0.29 from it | `gh release create vX.Y.Z Maprizon.jar` |
| Official JOSM plugin list (Preferences → Plugins) | **Listed** — the GitHub `latest` jar. Verified 2026-10-30: `PluginsSource` has a "by Kaart" entry and `josm.openstreetmap.de/plugin` serves the 1.0.29 manifest. | Automatic: JOSM re-reads the wiki page and follows GitHub `latest`. |

Release steps:

1. Bump `plugin.version` in **both** `build.xml` (drives the manifest) and
   `plugin.properties` (documentation only).
2. `ant clean dist`, install, restart JOSM, and smoke-test public coverage,
   image panel, and login.
3. Commit as `Release X.Y.Z`; push to `main`.
4. Publish the jar: upload `Maprizon.jar.zip` to Spaces (what the web app hands
   out) **and** create a GitHub release with `Maprizon.jar` attached (what the
   JOSM plugin list points at — it goes live to JOSM users as soon as it is
   `latest`).
5. Raise `JOSM_MAPRIZON_VERSION` on the api (DigitalOcean App Platform env var).
   The in-plugin update check (`io/PluginUpdateCheck`) calls
   `POST /backend/api/upload/check_uploader_version` with
   `product: "josm-maprizon"` and only nags when the server's number is newer.
   Measured 2026-10-30: the server still held **1.0.26**, so no JOSM user had been
   told about 1.0.27–1.0.29. The server's check is an exact match
   (`server/flaskr/views/Upload.py`: `user_version != uploader_version`), so it
   also tells any NEWER build it is "out of date"; the plugin ignores that
   (`compare(running, latest) >= 0`), the desktop uploader does not.

### The official JOSM plugin list

Listed via the "external plugin" route: a "by Kaart" entry on the JOSM wiki page
`https://josm.openstreetmap.de/wiki/PluginsSource` (contact dev@kaart.com)
points at
`https://github.com/KaartGroup/josm-maprizon/releases/latest/download/Maprizon.jar`.
JOSM re-reads that page about every 10 minutes, so a GitHub release reaches the
list as soon as it is `latest` — cut releases deliberately. Changing the entry
(contact, URL) needs a JOSM wiki (OSM) account.

## Coupling to the Maprizon API

Base URL is hardcoded: `https://app.maprizon.com/backend/api/` (`io/ViewerApiClient`).

| Endpoint | Auth | Used for |
|---|---|---|
| `POST sequence/public/by-feature`, `POST sequence/by-feature` | none / Bearer | frames of a clicked track |
| `GET sequence/public/adjacent/{id}/{dir}`, `GET sequence/adjacent/...` | none / Bearer | walking across sequence boundaries |
| `POST sequence/public/nearest`, `POST sequence/nearest` | none / Bearer | camera switching |
| `POST images/public/sign`, `POST images/sign` | none / Bearer | signed photo URLs (image bucket is private) |
| `GET tiles/sign` | Bearer | presigned URLs for the org's private PMTiles |
| `GET tiles/inspect` | Bearer | Login Diagnostics |
| `POST upload/check_uploader_version` | none | update check |

Public coverage is read straight from
`https://viewer-tiles.sfo3.digitaloceanspaces.com/tiles/public_imagery-{facing}.pmtiles`
(`FacingStyle`). Login is Auth0 Authorization Code + PKCE on tenant
`dev-p6r3cciondp4has2.us.auth0.com`, audience `https://Viewer/api/authorize`,
native-app client id baked into `oauth/ViewerAuth` (public, not a secret), with
loopback callbacks `http://127.0.0.1:{8765,8766,8767}/maprizon-callback`
registered on that Auth0 application. Changing any of those endpoints, the tile
naming, or the Auth0 app breaks the plugin for every installed copy — there is no
server-side switch.

## In flight / known gaps

- Open PRs: see `gh pr list` (a snapshot here goes stale). Old pushed branches
  (`feat/direction-of-travel-arrows`, `feat/obj-info-link-and-update-check`,
  `feature/shareable-image-links`, `fix/download-scope-overfetch`) are fully
  merged into `main` and can be ignored.
- `docs/COVERAGE_AVAILABILITY_PLAN.md` — "where is there imagery?" overview;
  plan only, not approved.
- `docs/VIEWER_OSM_PLUGIN_PLAN.md` — longer-term two-way JOSM↔Maprizon bridge;
  planning only.

## Access a successor needs (ACCESS TRANSFER NEEDED)

- **GitHub** — write access to `KaartGroup/josm-maprizon` to push and cut releases.
- **DigitalOcean** — Spaces write access to the `kaart` bucket (`Uploader/`) to
  publish the jar, and App Platform access to set `JOSM_MAPRIZON_VERSION` /
  `MAPRIZON_JOSM_PLUGIN_URL` on the api.
- **Auth0** — admin on tenant `dev-p6r3cciondp4has2` to manage the plugin's
  native application (callback URLs, offline access).
- **JOSM wiki / OSM account** — to change the Kaart entry on `PluginsSource`.
- **Trello** — the Maprizon/JOSM Plugin board, https://trello.com/b/FSwceNHE.
- `testbed/` probes live only on Chris's machine; ask for a copy if you want them.
