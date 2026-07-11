# Viewer OSM Plugin — Architecture & Feature Plan

> **Created**: 2026-02-10
> **Status**: Planning / Discussion
> **Supersedes**: `OSM_DATA_LAYER_IMPLEMENTATION_PLAN.md` (roads-only scope expanded)

---

## Vision

A bidirectional bridge between Viewer.kaart.com and JOSM, consisting of:

1. **Viewer-side integration** (JavaScript) — OSM data import with filtering, JOSM remote control, real-time sync
2. **JOSM plugin** (Java) — custom remote control commands, event listeners that push state back to Viewer, Viewer imagery layer inside JOSM
3. **Viewer backend support** (Flask) — WebSocket endpoint for real-time sync, optional Overpass query proxy/cache

The goal is to make Viewer a first-class companion tool for OSM editing — not an editor itself, but an imagery reference platform that talks natively to JOSM.

---

## Part 1: OSM Data Layer (All Feature Types)

### Correction from Previous Plan

The original plan (`OSM_DATA_LAYER_IMPLEMENTATION_PLAN.md`) scoped the download to `highway=*` only. **This is too narrow.** Mappers routinely need to reference buildings, land use, waterways, POIs, power infrastructure, boundaries, and more when validating against imagery. The data layer must support importing **any OSM feature type** with a filter UI.

### Overpass Query Architecture

The Overpass API supports filtering by any tag combination, any element type (node/way/relation), with regex matching, geographic bounding, set operations, and recursion. This gives us full flexibility.

**Filter UI — Two Modes:**

#### Quick Presets (Default)
Checkboxes for common feature categories, any combination selectable:

| Preset | Overpass Filter | Geometry |
|--------|----------------|----------|
| Roads & Paths | `way["highway"]` | Lines |
| Buildings | `way["building"]` | Polygons |
| Land Use | `way["landuse"]` | Polygons |
| Waterways | `way["waterway"]` | Lines |
| Railways | `way["railway"]` | Lines |
| Power Lines | `way["power"="line"]` | Lines |
| Amenities/POIs | `nwr["amenity"]` | Points/Polygons |
| Shops | `nwr["shop"]` | Points/Polygons |
| Natural Features | `nwr["natural"]` | Points/Lines/Polygons |
| Boundaries | `relation["boundary"="administrative"]` | Relations |
| Addresses | `nwr["addr:housenumber"]` | Points |

#### Custom Query (Advanced)
A text input where power users can write raw Overpass QL. Pre-populated with the current bbox. Similar to Overpass Turbo's query box but embedded in the Viewer tools modal.

```
[out:json][timeout:60][bbox:{{south}},{{west}},{{north}},{{east}}];
(
  way["highway"]["surface"="unpaved"];
  way["highway"]["surface"="gravel"];
);
out geom;
```

### Download Flow (Revised)

```
┌─────────────────────────────────────────────────────────────────┐
│                    OSM Download Modal                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Current view: 0.12 sq deg  ✓ Within limits                    │
│                                                                  │
│  ┌─ Quick Presets ──────────────────────────────────────────┐   │
│  │ [x] Roads & Paths    [ ] Railways     [ ] Shops         │   │
│  │ [ ] Buildings         [ ] Power Lines  [ ] Natural       │   │
│  │ [ ] Land Use          [ ] Amenities    [ ] Boundaries    │   │
│  │ [ ] Waterways         [ ] Addresses                      │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  [ ] Advanced: Custom Overpass query                             │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ (text area, hidden unless checked)                        │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  [Cancel]                                    [Download]          │
└─────────────────────────────────────────────────────────────────┘
```

### Rendering

Downloaded OSM data rendered as a GeoJSON source on the Mapbox map with distinct styling per feature type:

| Feature Type | Color | Style |
|-------------|-------|-------|
| Roads | Orange | Solid lines, width by classification |
| Buildings | Red | Filled polygons, 50% opacity |
| Waterways | Blue | Dashed lines |
| Land Use | Green | Filled polygons, 30% opacity |
| POIs/Amenities | Purple | Circle markers |
| Power | Yellow | Thin dashed lines |
| Railways | Dark gray | Dashed lines with tick marks |
| Other | White | Default styling |

### Feature Inspection

Click any downloaded OSM feature to see its tags in a popup:

```
┌────────────────────────────────────┐
│ way/123456789                      │
│ highway = residential              │
│ name = Main Street                 │
│ surface = asphalt                  │
│ lanes = 2                          │
│ maxspeed = 30                      │
│                                    │
│ [Open in JOSM]  [View on OSM.org] │
└────────────────────────────────────┘
```

### Technical Notes

- **Library**: `osmtogeojson` (browser-compatible, ~100KB gzipped) for Overpass JSON → GeoJSON conversion
- **Rate limiting**: Client-side 5-second minimum between Overpass requests
- **Area limit**: 0.25 sq deg per download (same as JOSM/OSM API)
- **Incremental**: Multiple downloads accumulate; track downloaded bboxes
- **Session-only**: Data clears on page reload (IndexedDB persistence is a future option)
- **Performance**: Limit to ~50k features with warning, use geometry simplification for dense areas

---

## Part 2: JOSM Remote Control Integration (Web → JOSM)

JOSM's remote control server listens on `http://127.0.0.1:8111/` with full CORS support (`Access-Control-Allow-Origin: *`). Any web page can send commands via `fetch()`.

### Viewer → JOSM Commands

| Action in Viewer | JOSM Command | What Happens |
|-----------------|--------------|--------------|
| "Open in JOSM" button | `/load_and_zoom?left=&right=&top=&bottom=` | JOSM downloads OSM data for Viewer's current viewport |
| Click OSM feature → "Edit in JOSM" | `/load_object?objects=w123456&select=w123456` | JOSM downloads + selects that specific object |
| "Add Viewer Imagery to JOSM" | `/imagery?type=tms&url=...&title=Maprizon` | Adds Viewer's tile layer as imagery background in JOSM |
| "Send OSM Data to JOSM" | `/load_data?data=...&new_layer=true` | Sends downloaded Overpass data directly into JOSM |
| Pre-fill changeset metadata | `changeset_source=Maprizon imagery&changeset_comment=...` | Attribution and context for edits |

### JOSM Connection Status

Persistent indicator in Viewer's toolbar:

```
JOSM: ● Connected (v19xxx)     ← green dot, polls /version every 10s
JOSM: ○ Not detected           ← gray dot, link to setup instructions
```

### Implementation

```javascript
// client/src/hooks/josm/useJOSMConnection.js
export function useJOSMConnection() {
  const [connected, setConnected] = useState(false);
  const [version, setVersion] = useState(null);

  useEffect(() => {
    const check = async () => {
      try {
        const res = await fetch('http://127.0.0.1:8111/version');
        const data = await res.json();
        setConnected(true);
        setVersion(data.josm);
      } catch {
        setConnected(false);
        setVersion(null);
      }
    };
    check();
    const interval = setInterval(check, 10000);
    return () => clearInterval(interval);
  }, []);

  const loadAndZoom = async (bbox, options = {}) => { ... };
  const loadObject = async (osmId) => { ... };
  const addImagery = async (tileUrl, title) => { ... };
  const sendData = async (osmXml) => { ... };

  return { connected, version, loadAndZoom, loadObject, addImagery, sendData };
}
```

---

## Part 3: Reverse Control (JOSM → Viewer)

This is the harder direction. JOSM's remote control is receive-only — it doesn't push state outward. Three approaches, in order of complexity:

### Approach A: Polling `/export` (No Plugin Required)

Viewer polls `http://127.0.0.1:8111/export` periodically to get JOSM's current layer data as OSM XML. Useful for syncing what JOSM is looking at, but crude and limited.

**Pros**: No JOSM plugin needed, works immediately
**Cons**: Polling overhead, no event-driven updates, can't detect viewport changes or selections

### Approach B: Lightweight JOSM Plugin with HTTP Callbacks (Recommended)

A small Java plugin that:
1. Registers custom remote control commands (Viewer → JOSM)
2. Listens for JOSM events and POSTs updates to a Viewer endpoint (JOSM → Viewer)

**Custom Remote Control Commands** (registered by plugin):

| Command | Purpose |
|---------|---------|
| `/viewer?action=show_image&img_url=...` | Make JOSM tell Viewer to display a specific image |
| `/viewer?action=zoom&lat=X&lon=X&zoom=Z` | Sync Viewer's viewport to JOSM's position |
| `/viewer?action=show_sequence&sequence_id=X&trip_id=X` | Open a specific sequence in Viewer |

**Event Listeners** (plugin pushes to Viewer via HTTP POST):

| JOSM Event | What Plugin Sends | Viewer Reacts |
|------------|-------------------|---------------|
| Viewport change (pan/zoom) | `POST /api/josm/viewport {bbox}` | Viewer pans map to match |
| Object selected | `POST /api/josm/selection {osm_ids}` | Viewer highlights features on map |
| Edit made (node moved, tag changed) | `POST /api/josm/edit {changeset_diff}` | Viewer updates OSM overlay in real-time |
| Layer changed | `POST /api/josm/layer {layer_info}` | Viewer adjusts context |

**Communication Flow:**

```
┌──────────────────┐                    ┌──────────────────┐
│  Viewer (Browser) │ ── fetch() ──────→ │  JOSM (Desktop)  │
│                    │    port 8111       │                    │
│  React + Mapbox   │ ←── HTTP POST ──── │  Kaart Plugin     │
│                    │    port 3000/API   │  (Java)           │
└──────────────────┘                    └──────────────────┘
```

The Viewer backend exposes a simple WebSocket or SSE endpoint that the JOSM plugin POSTs to, and the Viewer frontend subscribes to for real-time updates.

### Approach C: WebSocket Bridge (Most Responsive)

Same as Approach B but the JOSM plugin establishes a persistent WebSocket connection to the Viewer backend instead of individual HTTP POSTs. Lower latency, true real-time sync.

**Recommendation**: Start with **Approach A** (polling, no plugin) for the MVP, then build **Approach B** (plugin with HTTP callbacks) as the full solution. Approach C is an optimization of B.

---

## Part 4: JOSM Plugin — "Maprizon" Plugin

A dedicated JOSM plugin (distributed via JOSM plugin repository or direct download) that makes JOSM a native companion to Viewer.

### Plugin Features

1. **Viewer Imagery Layer**
   - Adds Viewer's imagery coverage as a toggleable layer in JOSM
   - Shows sequence lines colored by camera facing (like Mapillary plugin)
   - Click a sequence point → opens that image in Viewer (sends command to browser)

2. **Custom Remote Control Handlers**
   - Extends JOSM's remote control so Viewer web app can trigger plugin-specific actions
   - Similar to how Mapillary plugin registers `/photo?photo=Mapillary/{id}`

3. **Bidirectional Sync**
   - Viewport sync (optional, toggle-able): JOSM pan/zoom → Viewer follows, and vice versa
   - Selection sync: select an OSM object in JOSM → Viewer highlights nearest imagery

4. **Context Menu Integration**
   - Right-click any OSM object in JOSM → "View in Maprizon" → opens nearest imagery
   - Right-click → "View imagery coverage" → shows what Viewer imagery exists for this area

5. **Changeset Source Attribution**
   - Automatically adds `source=Maprizon imagery` to changesets when the Viewer imagery layer is active

### Plugin Technical Stack

- **Language**: Java (JOSM plugin requirement)
- **Build**: Gradle (standard JOSM plugin build system)
- **Dependencies**: JOSM core API, HTTP client for callbacks
- **Distribution**: JOSM plugin repository listing or direct JAR download from Kaart

---

## Part 5: Additional Crossover Features

### 5.1 OSM Feature Overlay on Street-Level Imagery
Render downloaded OSM road/building geometries **projected onto the 360° image viewer**, not just on the map. Mappers could visually verify that the OSM road centerline matches the actual road visible in the image. Requires projecting geographic coordinates to the equirectangular image space using the camera's position and heading.

**Value**: Directly answers "does the map match reality?" without switching between map and image.

### 5.2 Coverage-Guided Editing
Show in JOSM (or on OSM.org via an overlay) which areas have Viewer imagery coverage, with highlighting for areas where imagery exists but OSM data is sparse, outdated, or missing. Essentially a "map this area, we have fresh imagery" heatmap.

**Value**: Directs mapping effort to where it's most useful.

### 5.3 Tag Suggestion from Imagery Context
When a mapper is looking at an image in Viewer and has an OSM feature selected in JOSM, Viewer could suggest tags based on visual context — e.g., "this road appears to have `surface=asphalt`, `lanes=2`". Initially manual (mapper clicks "suggest tags"), eventually AI-assisted.

**Value**: Speeds up tagging by pre-filling values the mapper can confirm or reject.

### 5.4 OSM Notes Integration
View and create OSM Notes directly from the Viewer interface. Click a location on the map or in the image → "Create OSM Note" → note is linked to the Viewer image URL for context. Existing notes in the area shown as markers.

**Value**: Mappers can flag issues while reviewing imagery without switching to JOSM or OSM.org.

### 5.5 Before/After Temporal Comparison
When Viewer has multiple capture dates for the same area, show a split or slider comparison. Mappers can identify real-world changes (new construction, demolished buildings, road changes) that need to be reflected in OSM.

**Value**: Change detection for human mappers — what's different between captures?

### 5.6 Measurement Tools → OSM Tags
Distance and area measurement tools in the image viewer that translate to OSM-appropriate tags. Measure road width → suggests `width=X`. Measure building height → suggests `height=X`. Measure between features → suggests `distance=X`.

**Value**: Quantitative data extraction from imagery directly into OSM tags.

### 5.7 Collaborative Task Areas
Divide a Viewer project into grid cells (like HOT Tasking Manager). Mappers claim cells, edit in JOSM with Viewer imagery, and mark cells complete. Real-time status visible in Viewer showing which areas are mapped, in progress, or need review.

**Value**: Organized mapping campaigns using Viewer imagery as the primary reference.

### 5.8 Osmose/QA Issue Overlay
Load quality assurance issues from Osmose, KeepRight, or JOSM validator as a layer in Viewer, linking each issue to the nearest imagery. Mapper clicks issue → sees the problem location in street-level view → opens in JOSM to fix.

**Value**: QA verification with visual context instead of guessing from map data alone.

### 5.9 Export OSM-Ready GeoJSON
Export downloaded+filtered OSM data from Viewer as GeoJSON files that can be imported into JOSM, QGIS, or other tools. Include Viewer image references as properties so downstream tools know which images cover each feature.

**Value**: Data portability for workflows that don't use the live JOSM bridge.

### 5.10 Offline/Field Mode
Cache downloaded OSM data + Viewer imagery tiles for offline use on tablets in the field. Mappers can survey areas without internet, then sync notes/observations when back online.

**Value**: Supports field mapping workflows in areas with poor connectivity.

---

## Implementation Phases

| Phase | Scope | Depends On |
|-------|-------|------------|
| **1** | OSM data layer with filter UI (all feature types) | Nothing — standalone |
| **2** | JOSM remote control (Viewer → JOSM) | Phase 1 |
| **3** | Feature inspection popups with "Open in JOSM" | Phases 1 + 2 |
| **4** | JOSM plugin MVP (custom commands + imagery layer) | Phase 2 |
| **5** | Bidirectional sync (JOSM → Viewer callbacks) | Phase 4 |
| **6** | Additional features (5.1–5.10) prioritized by user demand | Phases 1-5 |

---

## Key Architectural Decisions to Make

1. **JOSM plugin language**: Must be Java (JOSM requirement). Who builds/maintains it?
2. **Sync mechanism**: HTTP polling vs. WebSocket vs. SSE for JOSM→Viewer communication
3. **Overpass query caching**: Client-only, or proxy through Viewer backend to avoid rate limits?
4. **OSM data persistence**: Session-only (current plan) vs. IndexedDB vs. backend cache per project?
5. **Plugin distribution**: JOSM plugin repository (broader reach) vs. direct download (faster iteration)?

---

## References

- [JOSM Remote Control API](https://wiki.openstreetmap.org/wiki/JOSM/RemoteControl) — full command reference
- [JOSM Remote Control Commands](https://josm.openstreetmap.de/wiki/Help/RemoteControlCommands) — endpoint docs
- [Mapillary JOSM Plugin](https://github.com/JOSM/Mapillary) — closest existing model for imagery ↔ JOSM integration
- [KartaView JOSM Plugin](https://github.com/kartaview/josm-plugin) — another imagery plugin reference
- [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API) — query language reference
- [Overpass QL](https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL) — full query syntax
- [OSM Map Features](https://wiki.openstreetmap.org/wiki/Map_features) — complete tag taxonomy
- [osmtogeojson](https://github.com/tyrasd/osmtogeojson) — browser-compatible OSM→GeoJSON converter
- [JOSM Plugin Development](https://josm.openstreetmap.de/wiki/DevelopersGuide/DevelopingPlugins) — plugin dev guide
- Previous plan: `client/docs/OSM_DATA_LAYER_IMPLEMENTATION_PLAN.md`
