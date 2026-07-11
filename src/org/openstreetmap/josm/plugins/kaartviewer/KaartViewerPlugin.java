package org.openstreetmap.josm.plugins.kaartviewer;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.kaartviewer.actions.ToggleKaartViewerLayerAction;
import org.openstreetmap.josm.tools.Logging;

/**
 * Main entry point for the Kaart Viewer JOSM plugin (Phase 1).
 *
 * Adds a single menu action (Tools menu, mirroring the CR_PLUGIN pattern) that
 * toggles the {@link org.openstreetmap.josm.plugins.kaartviewer.layer.KaartViewerLayer}
 * coverage layer on and off.
 */
public class KaartViewerPlugin extends Plugin {

    public KaartViewerPlugin(PluginInformation info) {
        super(info);

        ToggleKaartViewerLayerAction toggleAction = new ToggleKaartViewerLayerAction();
        MainApplication.getMenu().toolsMenu.add(toggleAction);

        Logging.info("KaartViewer plugin loaded successfully");
    }
}
