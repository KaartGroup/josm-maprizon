// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.actions;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maprizon.io.ViewerApiClient;
import org.openstreetmap.josm.plugins.maprizon.oauth.ViewerAuth;
import org.openstreetmap.josm.tools.ImageProvider;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;

/**
 * One-click answer to "why is my private imagery not loading?".
 *
 * <p>This exists because that question took several sessions of round-trips to
 * answer, one symptom at a time. Every failure on the private path — an expired
 * login, a token the server will not accept, an org the server cannot see in the
 * token, a tileset that was never baked — is deliberately swallowed into the same
 * fallback to public tiles, so the plugin keeps working. That is the right
 * behaviour and it is also why the causes are indistinguishable from outside.
 *
 * <p>So: run the checks, print the STATUS LINES, and make them copyable.
 *
 * <p><b>Never prints a credential.</b> The access token is reported only by
 * presence and claim shape, and a successful signing response is reported as the
 * facings it covered — never the URLs, which carry the signature that grants
 * access to private imagery.
 */
public class ShowMaprizonDiagnosticsAction extends JosmAction {

    public ShowMaprizonDiagnosticsAction() {
        super(
                "Maprizon Login Diagnostics",
                new ImageProvider("maprizon").setSize(ImageProvider.ImageSizes.SMALLICON),
                "Check the Maprizon login and private-tile access, and show exactly what fails",
                null,   // no shortcut
                false,
                "maprizon-diagnostics",
                false); // installAdapters=false: always available
        setEnabled(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        // Off the EDT: this makes real network calls (and may refresh a token).
        new Thread(() -> {
            String report = buildReport();
            SwingUtilities.invokeLater(() -> show(report));
        }, "maprizon-diagnostics").start();
    }

    private static String buildReport() {
        StringBuilder b = new StringBuilder();
        ViewerAuth auth = ViewerAuth.getInstance();

        b.append("MAPRIZON DIAGNOSTICS\n");
        b.append("====================\n\n");

        b.append("LOGIN\n");
        b.append("  logged in ......... ").append(auth.isLoggedIn()).append('\n');
        b.append("  account ........... ").append(auth.email().isEmpty() ? "(unknown)" : auth.email()).append('\n');
        b.append("  org (from token) .. ").append(auth.orgId().isEmpty() ? "(none)" : auth.orgId()).append('\n');
        b.append("  token claims ...... ").append(auth.orgClaimSummary()).append('\n');
        b.append('\n');

        b.append("PRIVATE TILES  (GET /backend/api/tiles/sign)\n");
        if (!auth.isLoggedIn()) {
            b.append("  skipped — not logged in.\n");
        } else {
            b.append("  ").append(ViewerApiClient.describeSignAttempt()).append('\n');
        }
        b.append('\n');

        b.append("PUBLIC TILES  (direct range read, no auth)\n");
        b.append("  ").append(ViewerApiClient.describePublicTileProbe()).append('\n');
        b.append('\n');

        b.append("HOW TO READ THIS\n");
        b.append("  * public OK + private HTTP 403 'no organization on token'\n");
        b.append("      -> the server cannot see an org on your login. If the claims\n");
        b.append("         line above says org_id=ABSENT, the plugin's Auth0 app is not\n");
        b.append("         issuing the claim the server scopes by; enabling Organization\n");
        b.append("         login for that app is the fix. Re-logging in will not help.\n");
        b.append("  * public OK + private HTTP 401\n");
        b.append("      -> the login itself is being rejected (expired, or the token is\n");
        b.append("         not valid for this backend). Log out and back in.\n");
        b.append("  * private 200 but no imagery on the map\n");
        b.append("      -> signing works; the org's tileset is empty or unbaked for the\n");
        b.append("         area you are looking at.\n");
        b.append("  * public FAILS too -> network/proxy problem, not a login problem.\n");
        return b.toString();
    }

    private static void show(String report) {
        JTextArea area = new JTextArea(report);
        area.setEditable(false);
        area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(680, 460));

        Object[] options = {"Copy to clipboard", "Close"};
        int choice = JOptionPane.showOptionDialog(
                MainApplication.getMainFrame(),
                scroll,
                "Maprizon — Login Diagnostics",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0]);
        if (choice == 0) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(report), null);
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(true);
    }
}
