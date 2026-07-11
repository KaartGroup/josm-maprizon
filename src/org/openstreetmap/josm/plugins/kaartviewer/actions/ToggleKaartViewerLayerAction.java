package org.openstreetmap.josm.plugins.kaartviewer.actions;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.kaartviewer.layer.KaartViewerLayer;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Menu action that adds the Kaart Viewer coverage layer if it isn't present,
 * or removes it if it already is - a simple on/off toggle, mirroring the
 * ToggleVisualizationAction pattern used by CR_PLUGIN.
 */
public class ToggleKaartViewerLayerAction extends JosmAction {

    public ToggleKaartViewerLayerAction() {
        super(
                "Kaart Viewer Coverage",
                new ImageProvider("kaartviewer"),
                "Show/hide the Kaart Viewer street-level imagery coverage layer",
                Shortcut.registerShortcut(
                        "view:kaartviewercoverage",
                        "Toggle Kaart Viewer coverage layer",
                        KeyEvent.VK_K,
                        Shortcut.ALT_SHIFT),
                false,
                "kaartviewer-toggle-coverage",
                true);
        setEnabled(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        List<KaartViewerLayer> existing = MainApplication.getLayerManager().getLayersOfType(KaartViewerLayer.class);
        if (!existing.isEmpty()) {
            for (KaartViewerLayer layer : existing) {
                MainApplication.getLayerManager().removeLayer(layer);
            }
        } else {
            MainApplication.getLayerManager().addLayer(new KaartViewerLayer());
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(true);
    }
}
