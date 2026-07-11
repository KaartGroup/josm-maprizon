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
 * Menu action that adds the Maprizon coverage layer if it isn't present,
 * or removes it if it already is - a simple on/off toggle, mirroring the
 * ToggleVisualizationAction pattern used by CR_PLUGIN.
 */
public class ToggleMaprizonLayerAction extends JosmAction {

    public ToggleMaprizonLayerAction() {
        super(
                "Maprizon Coverage",
                new ImageProvider("maprizon"),
                "Show/hide the Maprizon street-level imagery coverage layer",
                Shortcut.registerShortcut(
                        "view:maprizoncoverage",
                        "Toggle Maprizon coverage layer",
                        KeyEvent.VK_K,
                        Shortcut.ALT_SHIFT),
                false,
                "maprizon-toggle-coverage",
                true);
        setEnabled(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        List<MaprizonLayer> existing = MainApplication.getLayerManager().getLayersOfType(MaprizonLayer.class);
        if (!existing.isEmpty()) {
            for (MaprizonLayer layer : existing) {
                MainApplication.getLayerManager().removeLayer(layer);
            }
        } else {
            MainApplication.getLayerManager().addLayer(new MaprizonLayer());
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(true);
    }
}
