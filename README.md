# Maprizon JOSM Plugin

View [Maprizon](https://viewer.kaart.com) street-level imagery coverage inside
JOSM, and browse the actual photos without leaving the editor.

## Features

- **Coverage layer**, colour-coded by camera facing, downloaded on demand for
  your current view (downloads accumulate as you pan/zoom around).
- **In-editor image viewer** — click a track to open its photo in a side panel
  and walk the sequence with the arrow keys or Prev/Next.
- **360° panorama viewer** — 360 images open as an interactive panorama (drag to
  look around, scroll to zoom).
- **View cone** on the map showing the selected image's camera direction.
- **Optional login** to view your organization's private imagery; everything
  public works with no account.

## Requirements

- JOSM **19481** or newer (Java 17+).

## Install

Once the plugin is listed, install it from within JOSM:
**Preferences → Plugins**, search for **Maprizon**, tick it, and restart JOSM.

## Usage

A **Maprizon** menu is added to the menu bar:

- **Maprizon Coverage** (`Alt+Shift+K`) — show/hide the coverage layer.
- **Download Maprizon coverage (current view)** (`Alt+Shift+D`) — fetch coverage
  for the area you're looking at. Zoom in to your work area first; downloads
  accumulate, and the layer's right-click menu has "Clear downloaded coverage".
- **Maprizon Help** — a quick in-app guide.

Facings are colour-coded: **front** white, **left** red, **right** green,
**360** purple, **still** amber. Each can be shown/hidden from the layer's
right-click menu.

Click a coverage track to open its image in the **Maprizon Image** panel; a cone
on the map marks the camera direction. To see private imagery, right-click the
layer → **Log in to Viewer** (optional; anonymous access covers all public data).

## Building from source

Requires **JDK 17+** and **Apache Ant**. JOSM core is a compile-only dependency
and is not included in the repo — fetch it first:

```bash
curl -fSL https://josm.openstreetmap.de/download/josm-tested.jar -o lib/josm-custom.jar
ant clean dist      # builds Maprizon.jar
ant install         # copies it to your local JOSM plugins directory
```

## License

GPL-2.0-or-later — see [LICENSE](LICENSE). Bundled third-party libraries and
their (GPL-compatible) licenses are listed in [NOTICE](NOTICE).

## Contact

Questions or issues: **dev@kaart.com**
