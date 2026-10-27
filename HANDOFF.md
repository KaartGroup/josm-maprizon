# Handoff — Maprizon JOSM plugin

State as of **2026-10-13**, updated **2026-10-27** (release 1.0.27, plugin-list listing), written for whoever picks this repo up cold. The
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

Current version: **1.0.27** (`build.xml` / `plugin.properties`, commit `b49e625`,
2026-10-20). 1.0.23–1.0.25 were local builds that never shipped.

## Build, run, test from a clean machine

Needs a JDK 17+ (verified with JDK 21) and Apache Ant (`brew install ant`). Then:

```bash
curl -fSL https://josm.openstreetmap.de/download/josm-tested.jar -o lib/josm-custom.jar
ant clean dist      # -> Maprizon.jar at the repo root (gitignored)
ant install         # copies it into the local JOSM plugins dir
```

Verified 2026-10-27 from a fresh worktree: builds clean, manifest says
`Plugin-Version: 1.0.27`, `Plugin-Mainversion: 19481`.

Restart JOSM after every install (it reads plugin jars only at startup). There
are **no unit tests and no CI** — verification is throwaway probes in `testbed/`
(gitignored, so they exist only on Chris's machine; see CLAUDE.md for the
pattern) plus a live run in JOSM. Failures go to `<JOSM user data dir>/maprizon.log`.

## How a release reaches users

There are three distribution channels, plus the server's version gate. As of
2026-10-27 GitHub and the JOSM list serve 1.0.27; **the version gate still says
1.0.26**, and the Spaces zip was not checked:

| Channel | What it serves now | How it is updated |
|---|---|---|
| Maprizon web app download link (`GET /backend/api/upload/uploader_downloads`, env var `MAPRIZON_JOSM_PLUGIN_URL` on the api; client fallback in viewer `client/src/config.js`) | `Maprizon.jar.zip` on the Kaart DigitalOcean Spaces CDN (`kaart.sfo3.cdn.digitaloceanspaces.com/Uploader/`). Which version it holds was **not checked** on 2026-10-27. | Zip the jar, upload to Spaces, update the env var if the path changes. **DO Spaces access needed.** |
| GitHub Releases (`releases/latest/download/Maprizon.jar`) | **v1.0.27 (2026-10-20)**, marked Latest | `gh release create vX.Y.Z Maprizon.jar` |
| Official JOSM plugin list (Preferences → Plugins) | **Listed.** Verified 2026-10-27: the live `PluginsSource` wiki page has a "by Kaart" entry, and JOSM's plugin index serves `Plugin-Version: 1.0.27` | Automatic: JOSM follows `releases/latest`, so step 4's GitHub release is the update. See below. |

Release steps:

1. Bump `plugin.version` in **both** `build.xml` (drives the manifest) and
   `plugin.properties` (documentation only).
2. `ant clean dist`, install, restart JOSM, and smoke-test public coverage,
   image panel, and login.
3. Commit as `Release X.Y.Z`; push to `main`.
4. Publish the jar: upload `Maprizon.jar.zip` to Spaces (what the web app hands
   out) **and** create a GitHub release with `Maprizon.jar` attached (what the
   JOSM plugin list would point at).
5. Raise `JOSM_MAPRIZON_VERSION` on the api (DigitalOcean App Platform env var).
   The in-plugin update check (`io/PluginUpdateCheck`) calls
   `POST /backend/api/upload/check_uploader_version` with
   `product: "josm-maprizon"` and only nags when the server's number is newer.
   **Not done for 1.0.27:** live 2026-10-27 the server still answers `status: ok`
   for 1.0.26 and `current_version: "1.0.26"` (a 426 "out of date") for 1.0.27.
   The plugin ignores that 426 because 1.0.26 is older than it, so nothing breaks,
   but 1.0.26 users are not told 1.0.27 exists until the variable is raised.

### The official JOSM plugin list

Done by the "external plugin" route: the JOSM wiki page
`https://josm.openstreetmap.de/wiki/PluginsSource` has a "by Kaart" entry (contact
dev@kaart.com, plus a repository line) pointing at
`https://github.com/KaartGroup/josm-maprizon/releases/latest/download/Maprizon.jar`.
JOSM re-reads that page about every 10 minutes, so each new GitHub release reaches
the list on its own. `PluginsSource-edited.txt` in this repo was the local draft of
that edit; the live entry differs from it slightly (it adds "or open an issue on
GitHub" and the repository line), and the wiki page is now the copy that counts.
Who submitted it, and with which OSM account, is not recorded here.

## Coupling to the Maprizon API

Base URL is hardcoded: `https://app.maprizon.com/backend/api/` (`io/ViewerApiClient`).

| Endpoint | Auth | Used for |
|---|---|---|
| `POST sequence/public/by-feature`, `POST sequence/by-feature` | none / Bearer | frames of a clicked track |
| `GET sequence/public/adjacent/{id}/{dir}`, `GET sequence/adjacent/...` | none / Bearer | walking across sequence boundaries |
| `POST sequence/public/nearest`, `POST sequence/nearest` | none / Bearer | camera switching |
| `POST images/public/sign`, `POST images/sign` | none / Bearer | photo URLs: org imagery comes back presigned; published imagery comes back as its unsigned `maprizon-public` CDN URL (since Maprizon's publication v2, 2026-10-19; no plugin change was needed) |
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

- No open PRs. All pushed branches (`feat/direction-of-travel-arrows`,
  `feat/obj-info-link-and-update-check`, `feature/shareable-image-links`,
  `fix/download-scope-overfetch`, `docs/handoff-2026-10-13`) are fully merged into
  `main` and can be ignored.
- `JOSM_MAPRIZON_VERSION` on the api is still 1.0.26 (release step 5, above).
- The Spaces zip behind the web app's download link was not checked for 1.0.27.
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
- **JOSM wiki / OSM account** — to change the `PluginsSource` entry. Find out which
  account made the existing edit; if it is personal, plan to move to a Kaart one.
- **Trello** — the Maprizon/JOSM Plugin board, https://trello.com/b/FSwceNHE.
- `testbed/` probes live only on Chris's machine; ask for a copy if you want them.
