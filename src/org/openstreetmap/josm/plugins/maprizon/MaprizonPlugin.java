package org.openstreetmap.josm.plugins.maprizon;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.maprizon.actions.ToggleMaprizonLayerAction;
import org.openstreetmap.josm.tools.Logging;

/**
 * Main entry point for the Maprizon JOSM plugin (Phase 1).
 *
 * Adds a single menu action (Tools menu, mirroring the CR_PLUGIN pattern) that
 * toggles the {@link org.openstreetmap.josm.plugins.maprizon.layer.MaprizonLayer}
 * coverage layer on and off.
 */
public class MaprizonPlugin extends Plugin {

    public MaprizonPlugin(PluginInformation info) {
        super(info);

        ToggleMaprizonLayerAction toggleAction = new ToggleMaprizonLayerAction();
        MainApplication.getMenu().toolsMenu.add(toggleAction);

        Logging.info("Maprizon plugin loaded successfully");
    }
}
