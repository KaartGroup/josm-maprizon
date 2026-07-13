package org.openstreetmap.josm.plugins.maprizon.actions;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maprizon.layer.MaprizonLayer;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Downloads Maprizon coverage for the CURRENT map view, JOSM-style: scoped to
 * the visible bbox, at the on-screen zoom, accumulating across downloads. If the
 * coverage layer isn't present yet it is added first. Mirrors how a user
 * downloads OSM data for the area they're working in, rather than the layer
 * auto-dumping everything.
 */
public class DownloadMaprizonCoverageAction extends JosmAction {

    public DownloadMaprizonCoverageAction() {
        super(
                "Download Maprizon coverage (current view)",
                new ImageProvider("maprizon"),
                "Download Maprizon street-level imagery coverage for the current view",
                Shortcut.registerShortcut(
                        "maprizon:downloadcoverage",
                        "Download Maprizon coverage for current view",
                        KeyEvent.VK_D,
                        Shortcut.ALT_SHIFT),
                false,
                "maprizon-download-coverage",
                // installAdapters=false: always-available action (it creates its
                // own layer + downloads); must not be gated on JOSM's OSM data /
                // selection context, which would grey it out + kill the hotkey
                // when only imagery (no data layer) is present.
                false);
        setEnabled(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        List<MaprizonLayer> existing = MainApplication.getLayerManager().getLayersOfType(MaprizonLayer.class);
        MaprizonLayer layer;
        if (existing.isEmpty()) {
            layer = new MaprizonLayer();
            MainApplication.getLayerManager().addLayer(layer);
        } else {
            layer = existing.get(0);
        }
        layer.downloadCurrentView();
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(true);
    }
}
