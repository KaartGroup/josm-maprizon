// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.maprizon.actions.DownloadMaprizonCoverageAction;
import org.openstreetmap.josm.plugins.maprizon.actions.ShowMaprizonDiagnosticsAction;
import org.openstreetmap.josm.plugins.maprizon.actions.ShowMaprizonHelpAction;
import org.openstreetmap.josm.plugins.maprizon.actions.ToggleMaprizonLayerAction;
import org.openstreetmap.josm.plugins.maprizon.gui.MaprizonImageDialog;
import org.openstreetmap.josm.tools.Logging;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.event.KeyEvent;

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

        // Build the Tools-menu items EXPLICITLY. Adding the JosmAction to the menu
        // (raw JMenu.add or MainMenu.add) rendered the item with its label +
        // accelerator but never delivered clicks to actionPerformed (verified via
        // diagnostics: menu clicks logged nothing, only the global hotkey did). A
        // plain JMenuItem with an explicit ActionListener is the direct, reliable
        // click path; we still show the shortcut accelerator + icon ourselves.
        // Dedicated top-level "Maprizon" menu instead of the Tools menu: JOSM greys
        // out the Tools menu's contents when there is no editable OSM data layer
        // (data-operations gate), which was disabling our items too even though the
        // actions themselves stay enabled. A custom top-level menu is not subject to
        // that gate, so its items remain clickable with only imagery layers loaded.
        MainMenu menu = MainApplication.getMenu();
        JMenu maprizonMenu = new JMenu("Maprizon");
        maprizonMenu.add(buildMenuItem(new ToggleMaprizonLayerAction()));
        maprizonMenu.add(buildMenuItem(new DownloadMaprizonCoverageAction()));
        maprizonMenu.addSeparator();
        maprizonMenu.add(buildMenuItem(new ShowMaprizonDiagnosticsAction()));
        maprizonMenu.add(buildMenuItem(new ShowMaprizonHelpAction()));
        menu.addMenu(maprizonMenu, "Maprizon", KeyEvent.VK_M, menu.getMenuCount(), null);

        MaprizonLog.info("plugin loaded, version "
                + (info == null || info.version == null ? "?" : info.version)
                + " — log file: " + MaprizonLog.file());
    }

    /** A Tools-menu item wired directly to the action's actionPerformed, carrying
     * the action's label, tooltip, icon, and shortcut accelerator. */
    private static JMenuItem buildMenuItem(JosmAction action) {
        JMenuItem item = new JMenuItem(String.valueOf(action.getValue(Action.NAME)));
        Object tip = action.getValue(Action.SHORT_DESCRIPTION);
        if (tip != null) {
            item.setToolTipText(String.valueOf(tip));
        }
        Object icon = action.getValue(Action.SMALL_ICON);
        if (icon instanceof Icon) {
            item.setIcon((Icon) icon);
        }
        if (action.getShortcut() != null && action.getShortcut().getKeyStroke() != null) {
            item.setAccelerator(action.getShortcut().getKeyStroke());
        }
        item.addActionListener(action);
        return item;
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
