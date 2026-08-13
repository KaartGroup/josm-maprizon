// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.actions;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.plugins.maprizon.MaprizonLog;
import org.openstreetmap.josm.plugins.maprizon.layer.MaprizonLayer;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.Collection;

/**
 * Opens Maprizon at the currently selected OSM object, so a mapper can check
 * street-level imagery for the thing they are editing without hunting for it on
 * the map first.
 *
 * <p><b>Why this exists as our own action.</b> The request was for a Maprizon
 * icon in the "Images" row of the {@code osm-obj-info} plugin's panel, beside
 * Mapillary / KartaView / Yandex. That row belongs to a DIFFERENT plugin
 * ({@code github.com/JOSM/osm-obj-info}) and its service URLs are hardcoded in
 * its own source, so a JOSM plugin cannot add a button to it — the only honest
 * route there is a pull request to that project, on their review and release
 * schedule. This action delivers the same capability on a path we control: pick
 * an OSM object, get Maprizon at that spot. If the upstream PR later lands,
 * users simply gain a second way in.
 *
 * <p><b>Deliberately just a location.</b> The link carries coordinates and
 * nothing else, so what the viewer shows follows the user's own Maprizon
 * session: public imagery when logged out, and additionally their organisation's
 * private imagery when logged in. That is the same thing they would see by
 * opening Maprizon themselves — this grants no access anyone did not already
 * have, which is exactly why it does not need to filter by scope.
 */
public class ShowMaprizonForSelectionAction extends JosmAction {

    /**
     * Zoom the viewer opens at. Close enough that the imagery around the object
     * is the subject, in the same range the other services in that Images row use
     * (KartaView and Yandex open at 18, Mapillary at 20).
     */
    private static final int LINK_ZOOM = 18;

    public ShowMaprizonForSelectionAction() {
        super(
                "Maprizon imagery for selected object",
                new ImageProvider("maprizon").setSize(ImageProvider.ImageSizes.SMALLICON),
                "Open Maprizon street-level imagery at the selected OSM object",
                Shortcut.registerShortcut(
                        "maprizon:selectionimagery",
                        "Open Maprizon imagery for the selected OSM object",
                        KeyEvent.VK_I,
                        Shortcut.ALT_SHIFT),
                false,
                "maprizon-selection-imagery",
                // installAdapters=false, matching the other actions here: JOSM's
                // data-context gating greys these out (and kills the hotkey) when
                // only imagery layers are present. The selection is checked
                // explicitly below instead, which also lets us SAY what is wrong
                // rather than presenting a dead menu item.
                false);
        setEnabled(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        LatLon where = selectionCentre();
        if (where == null) {
            return; // selectionCentre already told the user why
        }
        String url = MaprizonLayer.locationDeepLink(where.lat(), where.lon(), LINK_ZOOM);
        MaprizonLog.info("opening viewer for selected OSM object at "
                + String.format(java.util.Locale.ROOT, "%.6f, %.6f", where.lat(), where.lon()));
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ex) {
            Logging.error("Maprizon: failed to open browser for " + url, ex);
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Could not open browser: " + ex.getMessage(),
                    "Maprizon",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Centre of the current OSM selection, or null (having explained why) when
     * there is nothing usable to open.
     *
     * <p>A way or relation is reduced to the centre of its bounding box, which is
     * what the user means by "here" for an extended object. Incomplete primitives
     * — a relation member that was never downloaded — carry no geometry, so they
     * are skipped rather than contributing a garbage bbox; only if EVERY selected
     * object is unusable does this give up.
     */
    private static LatLon selectionCentre() {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        Collection<OsmPrimitive> selected = ds == null ? null : ds.getSelected();
        if (selected == null || selected.isEmpty()) {
            new Notification("Select an OSM object first, then open Maprizon imagery for it.")
                    .setIcon(JOptionPane.INFORMATION_MESSAGE)
                    .show();
            return null;
        }
        BBox box = new BBox();
        for (OsmPrimitive p : selected) {
            if (p.isIncomplete()) {
                continue;
            }
            BBox b = p.getBBox();
            if (b != null && b.isValid()) {
                box.add(b);
            }
        }
        if (!box.isValid()) {
            new Notification("The selected object has no position to look up "
                    + "(it may not be downloaded yet).")
                    .setIcon(JOptionPane.WARNING_MESSAGE)
                    .show();
            return null;
        }
        return box.getCenter();
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(true);
    }
}
