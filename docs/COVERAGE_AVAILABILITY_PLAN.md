# Coverage Availability — implementation plan

> **Created**: 2026-07-29
> **Status**: Plan, not yet approved
> **Spans**: `josm-maprizon/` (plugin) and `viewer-2-0/` (`tile-baker/`, `server/flaskr/`)

## The problem

A plugin user has no way to discover where imagery exists. Today the only way to
find out is to download a view and see whether anything appears — so in practice
people are told verbally ("the public data is in Cebu"). Both audiences are
affected: public coverage is one small area, and an org user cannot see the shape
of their own fleet's coverage either.

The goal is **pre-download availability**: show where imagery exists, for the
public dataset and for the caller's org, before any coverage download runs.

---

## What was verified (evidence, not assumption)

Measured against the live archives and the current code, 2026-07-29.

### The PMTiles archives are tiny, and fully indexed in one read

| archive | zooms | total tiles | root dir | leaf dirs |
|---|---|---|---|---|
| `public_imagery-front` | z3–16 | 1297 | 3209 B | **0** |
| `public_imagery-left` | z3–16 | 1308 | — | 0 |
| `public_imagery-right` | z3–16 | 1193 | — | 0 |
| `public_imagery-360` | z3–16 | 1284 | — | 0 |
| `public_imagery-still` | z0–13 | 126 | 429 B | 0 |

`leaf_dirs_length = 0` on every archive: the **entire** tile index is in the root
directory. One ~3 KB range read per facing yields every `(z,x,y)` that exists —
about 17 KB for the whole public dataset, with **zero tile decoding**.

`internal_compression = 2` (gzip), `tile_compression = 2`, `tile_type = 1` (MVT),
`clustered = 1`.

### `getBounds()` is unusable — this killed the obvious cheap option

Every archive declares world bounds: `lon[-180..180] lat[-85..85]`. An "extent
box" feature would draw a planet-sized rectangle implying imagery across the
Pacific. **Rejected.** Do not revive it without re-checking this.

### The library gives us most of the directory decode

In `lib/pmtiles-reader-0.3.6.jar`:

- `Hilbert.zxyToIndex(int z, long x, long y)` — **public**
- `VarInt.getVarLong(ByteBuffer)` — **public**
- `Reader.getBounds/getCenter/getMinZoom/getMaxZoom/getMetadata` — public
- `Reader$Directory`, `Reader$DirCache`, `Reader.getZoomOffset`, `Util.decompress`
  — **package-private**, not usable

So we must supply: gunzip (already exists — `PmtilesTileLoader.gunzip`), and the
**inverse** Hilbert (`tile_id → z,x,y`, ~30 lines). The inverse is provably
correct against the library's own forward function — see Verification.

### The baker already computes the coverage grid, and throws it away

`tile-baker/tile_baker/orchestrator.py:176-287` (`_bboxes_for`) runs, per scope,
per night:

```sql
SELECT ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) FROM (
  SELECT ST_Extent(f.geometry::geometry) AS e
  FROM {from_sql} WHERE {scope_sql}{facing_sql} AND f.geometry IS NOT NULL
    AND NOT (abs(ST_X(f.geometry::geometry)) < 0.01
         AND abs(ST_Y(f.geometry::geometry)) < 0.01)
  GROUP BY floor(ST_X(f.geometry::geometry) / :deg),
           floor(ST_Y(f.geometry::geometry) / :deg)
) s
```

That is a per-scope, per-0.25°-cell occupied-extent list. It is converted to
`--bbox` strings for martin-cp and discarded. Adding `count(*)` to it is free
relative to the `ST_Extent` already scanning those rows.

### Live aggregation over `mapbox_features` is not an option

- `gunicorn_config.py` sets `workers = 2` and nothing else — no timeout.
- No `statement_timeout`, no engine options, no pool tuning anywhere.
- `mapbox_features` is millions of rows (`Admin.py:42-44`: "**Nothing here may
  scan it**"). A day of drive capture is 500k rows.
- Two production incidents already: the 2026-07-07 gunicorn-starvation incident
  (`migrations/.../e9f0a1b2c3d5:21-22`) and a full scan that "starved the
  gunicorn pool and took down authenticated tiles" (`core.py:229-234`).

A `GROUP BY ST_SnapToGrid` over `mapbox_features` at low zoom is the exact shape
of query that has taken this app down twice. **Rejected.** Coverage must be
precomputed, never aggregated per request.

### The public-visibility rule is already duplicated five times

`imagery_tiles()` (migration), `sequence_lines` (migration),
`Images._public_img_allowlist`, `Sequence._reject_if_not_public`,
`blur.jobs._scope_conditions` — plus the baker's `_bboxes_for` as a sixth. There
is no shared helper, and `core.py:359-364` warns "all four must agree".

This is the decisive SSOT argument for computing coverage **in the baker**: the
artifact then inherits the *same* predicate as the tiles it describes, and the
two cannot drift. A new Flask query would be a seventh copy.

---

## Architecture

Two tracks. They are independent and complementary; either ships alone.

```
TRACK A  (plugin only, no backend, no deploy)
  PMTiles root directory  ->  set of (z,x,y)  ->  coverage cells + density
  cost: ~3 KB per facing, one range read, no tile decode

TRACK B  (baker + existing sign endpoint; reusable framework)
  _bboxes_for + count(*)  ->  {scope}-coverage.json in Spaces
  consumed by: plugin, web app, anything else
  cost: ~free in the bake; ZERO new API load; ZERO new Flask routes
```

**Track A** gives *presence* (tile exists ⇒ imagery exists) and a density proxy
(count of deep-zoom descendants per coarse cell). It works offline of any
deployment and covers private archives through the signing already implemented.

**Track B** gives true **image counts** per cell and is the reusable artifact.

Recommended order: **A, then B.** A solves the stated user problem in days with
no production risk. B is the framework, and is where the counts come from.

---

## Track A — plugin: availability from the PMTiles directory

### Files (plugin repo)

| file | change |
|---|---|
| `src/.../pmtiles/PmtilesDirectory.java` | **new** — v3 root-directory decode |
| `src/.../pmtiles/TileId.java` | **new** — inverse Hilbert + zoom base math |
| `src/.../pmtiles/PmtilesTileLoader.java` | expose a raw range-read + the archive URL for a facing |
| `src/.../layer/CoverageIndex.java` | **new** — per-scope index: cell → descendant count |
| `src/.../layer/MaprizonLayer.java` | paint the availability layer; menu toggle |
| `src/.../FacingStyle.java` | availability palette entries |

### Steps

1. **`TileId`** — `zoom(tileId)`, `base(z) = (4^z - 1)/3`, and `toZxy(tileId)`
   (inverse Hilbert d2xy). Pure functions, no I/O.
2. **`PmtilesDirectory`** — given the first 127 bytes plus the root-directory
   bytes: gunzip, then decode the v3 entry arrays with `VarInt.getVarLong`
   (delta-coded `tile_id`, `run_length`, `length`, `offset`). Return
   `long[] tileIds` expanded over `run_length`. No network code here — takes
   bytes, returns ids, so it is unit-testable.
3. **`PmtilesTileLoader`** — add `byte[] readRange(String facing, long off, int len)`
   reusing the existing scope/signing resolution, so Track A gets private
   archives for free via the presigned URLs.
4. **`CoverageIndex`** — for one scope: for each facing, read header + root dir,
   decode ids, convert to `(z,x,y)`, and build a map from a chosen display cell
   (z5 or z6) to the number of `maxzoom` tiles beneath it. That count is the
   density channel.
5. **`MaprizonLayer`** — paint filled cells with alpha ramped by density, under
   the coverage lines; label the layer's audience (already done — `254c80d`).
   Fetch the index once on layer add, and again on login/logout (audience
   changed). Menu item: "Show/hide imagery availability".

### Cost

Header + root dir per facing = ~3.3 KB; five facings ≈ 17 KB and 10 HTTP range
requests, once per session per audience. No tile decode. Negligible next to a
single coverage download.

### Verification

- **Inverse Hilbert is provable, not spot-checked.** For every id decoded from a
  real archive, assert `Hilbert.zxyToIndex(z, x, y) == id` using the library's
  own public forward function. That is a round-trip proof over ~1300 real
  entries per facing, not a sample.
- Decode the public `front` archive and assert the id count equals the header's
  `tile_entries` (1297) — the header independently states the answer.
- Assert every decoded `(z,x,y)` is within `[min_zoom, max_zoom]` from the header.
- Assert a decoded z-N cell containing Cebu is present, and one in the mid-Pacific
  is absent.
- `testbed/` is the established place for throwaway probes; a `CoverageProbe`
  there can print the index for eyeballing before any UI exists.

### Risks

- **No unit-test harness in this repo.** `build.xml` has no test target. The
  round-trip assertions above therefore live in a `testbed/` probe unless we add
  JUnit — worth deciding, since `PmtilesDirectory`/`TileId` are pure functions
  and are exactly what deserves tests. Recommend adding a minimal JUnit target
  as part of this work.
- v3 spec drift: pinned by `lib/pmtiles-reader-0.3.6.jar`; if the baker's
  `pmtiles convert` version changes the directory encoding, the decode breaks.
  Mitigate by validating `spec_version == 3` from the header and failing soft
  (skip the availability layer, log once) rather than breaking coverage download.
- Density is *tile* density, not image density. A cell with one dense drive and a
  cell with ten sparse ones can look alike. Track B fixes this; the UI copy must
  not overclaim ("imagery available here", not "1,200 images here").

---

## Track B — baker artifact + existing sign endpoint

**Planned separately, in the repo where its code lives:**
`viewer-2-0/docs/COVERAGE_SUMMARY_ARTIFACT_PLAN.md`.

Summary of what it provides to this track, so Track A can be built against it
without re-reading that document:

- An object per scope at `tiles/{scope}-coverage.json`, beside the tilesets.
- Public scope is `public-read` (fetch bare); org scope is `private` and its URL
  arrives as a new top-level `coverageUrl` key on the existing
  `/backend/api/tiles/sign` response — no new endpoint.
- Payload carries `schema`, `scope`, `generated_at`, `cell_deg`, and per-facing
  `{ total, cells: [[lon_cell, lat_cell, count], …] }`.
- It reports **image counts**; Track A reports **tile presence**. They will not
  agree cell-for-cell (a tile can exist for a single image), so UI copy must not
  present them as the same number.

Track A does not depend on Track B and must not block on it.

---

## SSOT discipline for this work

1. **The visibility predicate is not re-implemented.** Track B derives from the
   baker's existing scope SQL. If a shared helper is ever introduced, it belongs
   in `flaskr/` for gates 3+4 (Python) — gates 1+2 are raw SQL in migrations and
   cannot import it. Note that limit rather than pretending to unify all five.
2. **`cell_deg` has one home**: `Config.cell_deg` (`config.py:23`), already
   env-driven (`TILE_BBOX_CELL_DEG`). It is *published in the artifact* so
   consumers never hardcode 0.25.
3. **`generated_at` is the server's timestamp**, carried in the artifact — no
   client-side staleness estimate, same reasoning as not mirroring the sign TTL.
4. **Facing list**: the baker's `FACINGS` (`orchestrator.py:81`) is authoritative
   and already includes `drone`; the artifact carries whatever it baked. The
   plugin reads keys present rather than asserting its own five.

## File collision list

Plugin repo (`josm-maprizon`), Track A:
`pmtiles/PmtilesDirectory.java` (new), `pmtiles/TileId.java` (new),
`pmtiles/PmtilesTileLoader.java`, `layer/CoverageIndex.java` (new),
`layer/MaprizonLayer.java`, `FacingStyle.java`, `build.xml` (if JUnit added),
`testbed/CoverageProbe.java` (new, gitignored).

Viewer repo (`viewer-2-0`), Track B:
`tile-baker/tile_baker/orchestrator.py`, `tile-baker/tile_baker/coverage.py`
(new), `tile-baker/tests/test_coverage_summary.py` (new),
`server/flaskr/views/Tiles.py`, `server/flaskr/test/test_tiles_sign.py`.

No overlap between tracks. Nothing in `client/` unless we also surface coverage
in the web app (explicitly out of scope here — the artifact makes it easy later).

## Rough sizing

| phase | scope | estimate |
|---|---|---|
| A1 | `TileId` + `PmtilesDirectory` + round-trip proof | 1 day |
| A2 | `CoverageIndex` + range-read plumbing | 0.5 day |
| A3 | Paint + toggle + audience refresh | 1 day |
| B1 | `count(*)` + `coverage.py` + tests | 1 day |
| B2 | Early emit + upload + ACL test | 0.5 day |
| B3 | `coverageUrl` in `/sign` + plugin consumption of counts | 0.5 day |

A alone: ~2.5 days and shippable. A+B: ~4.5 days.

## Open questions for the owner

1. **Add a JUnit target to the plugin?** `TileId`/`PmtilesDirectory` are pure
   functions and the highest-value test targets in the repo, but there is no test
   harness today and every plugin fix so far has shipped compile-verified only.
2. **Display cell size.** z5 (~1250 km) reads as "which region", z6/z7 as "which
   metro". Suggest z6, configurable.
3. **Does the web app want this too?** The artifact is audience-agnostic; wiring
   it into the viewer's map is a small follow-on and would make "where is our
   coverage" answerable without JOSM.
