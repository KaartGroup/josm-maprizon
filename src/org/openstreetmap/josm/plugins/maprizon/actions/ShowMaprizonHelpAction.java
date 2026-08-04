// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.actions;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.OpenBrowser;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import java.awt.event.ActionEvent;

/**
 * Menu action that shows a short "what is this / how do I use it" dialog for the
 * Maprizon plugin, with a button to open the project page in a browser. Purely
 * informational — always enabled, no data-layer dependency.
 */
public class ShowMaprizonHelpAction extends JosmAction {

    private static final String PROJECT_URL = "https://github.com/KaartGroup/josm-maprizon";

    private static final String HELP_HTML =
            "<html><body style='width:440px'>"
            + "<h2>Maprizon coverage</h2>"
            + "<p>Shows <b>Maprizon</b> street-level imagery coverage as a layer in JOSM, and "
            + "lets you view the actual photos without leaving the editor.</p>"
            + "<h3>Getting started</h3>"
            + "<ul>"
            + "<li><b>Show the layer:</b> Maprizon menu &rarr; <i>Maprizon Coverage</i> "
            + "(<tt>Alt+Shift+K</tt>).</li>"
            + "<li><b>Download coverage:</b> zoom in to your area, then Maprizon menu &rarr; "
            + "<i>Download Maprizon coverage (current view)</i> (<tt>Alt+Shift+D</tt>). "
            + "Coverage accumulates as you download more areas; a spinner shows while it loads.</li>"
            + "<li><b>Facings are colour-coded:</b> front = white, left = red, right = green, "
            + "360 = purple.</li>"
            + "</ul>"
            + "<h3>Viewing images</h3>"
            + "<ul>"
            + "<li><b>Click a track</b> to open its photo in the <i>Maprizon Image</i> side panel; "
            + "walk the sequence with the arrow keys or Prev/Next. A cone on the map shows the "
            + "camera's direction.</li>"
            + "<li><b>360 images</b> open in an interactive panorama &mdash; drag to look around, "
            + "scroll to zoom; the map cone tracks where you're looking.</li>"
            + "</ul>"
            + "<h3>Private imagery</h3>"
            + "<p>Right-click the layer &rarr; <i>Log in to Maprizon</i> to add your organization's "
            + "private coverage and images (optional; everything public works logged out). "
            + "Logged in you see <b>your organization's imagery and public imagery together</b>.</p>"
            + "<p><b>Login lasts for this JOSM session only.</b> Quitting JOSM logs you out, so "
            + "each new session starts logged out &mdash; this keeps two machines from sharing "
            + "one live session.</p>"
            + "<p style='color:#777'>Questions: dev@kaart.com</p>"
            + "</body></html>";

    public ShowMaprizonHelpAction() {
        super(
                "Maprizon Help",
                new ImageProvider("maprizon").setSize(ImageProvider.ImageSizes.SMALLICON),
                "What the Maprizon plugin does and how to use it",
                null,   // no shortcut
                false,
                "maprizon-help",
                false); // installAdapters=false: informational, always available
        setEnabled(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Object[] options = {"Open project page", "Close"};
        int choice = JOptionPane.showOptionDialog(
                MainApplication.getMainFrame(),
                new JLabel(HELP_HTML),
                "Maprizon — Help",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[1]);
        if (choice == 0) {
            OpenBrowser.displayUrl(PROJECT_URL);
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(true);
    }
}
