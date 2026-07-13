package org.openstreetmap.josm.plugins.maprizon;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.maprizon.actions.DownloadMaprizonCoverageAction;
import org.openstreetmap.josm.plugins.maprizon.actions.ToggleMaprizonLayerAction;
import org.openstreetmap.josm.plugins.maprizon.gui.MaprizonImageDialog;
import org.openstreetmap.josm.tools.Logging;

/**
 * Main entry point for the Maprizon JOSM plugin (Phase 1).
 *
 * Adds the Tools-menu actions that toggle / download the
 * {@link org.openstreetmap.josm.plugins.maprizon.layer.MaprizonLayer} coverage
 * layer, and registers the docked {@link MaprizonImageDialog} image viewer with
 * each map frame so clicking a coverage point shows the actual image in JOSM.
 */
public class MaprizonPlugin extends Plugin {

    public MaprizonPlugin(PluginInformation info) {
        super(info);

        ToggleMaprizonLayerAction toggleAction = new ToggleMaprizonLayerAction();
        MainApplication.getMenu().toolsMenu.add(toggleAction);

        DownloadMaprizonCoverageAction downloadAction = new DownloadMaprizonCoverageAction();
        MainApplication.getMenu().toolsMenu.add(downloadAction);

        Logging.info("Maprizon plugin loaded successfully");
    }

    /**
     * Register the image viewer toggle dialog when a map frame comes up (and let
     * it be torn down with the frame). Mirrors how the Mapillary plugin adds its
     * lateral image panel.
     */
    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            newFrame.addToggleDialog(new MaprizonImageDialog());
        }
    }
}
