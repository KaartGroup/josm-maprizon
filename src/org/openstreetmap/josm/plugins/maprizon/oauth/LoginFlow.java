// Maprizon JOSM plugin — Copyright (C) 2026 Kaart Group
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.maprizon.oauth;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Logging;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import java.awt.Desktop;
import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Interactive half of the Auth0 <b>Authorization Code + PKCE</b> login over a
 * <b>loopback redirect</b> (RFC 8252): the user clicks "Log in", the browser
 * opens to Auth0, they approve, and Auth0 redirects back to a one-shot
 * {@code http://127.0.0.1:<port>} listener this class runs — no code typing, no
 * copy-paste. The captured authorization code is exchanged for tokens by
 * {@link ViewerAuth#exchangeCode}.
 *
 * <p>Purely additive to the anonymous experience — only ever reached from the
 * explicit "Log in to Viewer" menu action.
 */
public final class LoginFlow {

    /**
     * Candidate loopback ports, tried in order. <b>Each must be registered as an
     * Allowed Callback URL on the Auth0 Native app</b>, i.e.
     * {@code http://127.0.0.1:<port>/maprizon-callback} for every port here, so
     * whichever one is free still matches a registered redirect URI.
     */
    private static final int[] PORTS = {8765, 8766, 8767};
    private static final String CALLBACK_PATH = "/maprizon-callback";
    private static final int LOGIN_TIMEOUT_MS = 180_000; // 3 min to complete in-browser

    private LoginFlow() {
    }

    /**
     * Begin an interactive login. {@code onSuccess} runs on the EDT after tokens
     * are obtained and stored. Non-blocking: all network / socket work happens on
     * a background daemon thread.
     */
    public static void start(Runnable onSuccess) {
        ViewerAuth auth = ViewerAuth.getInstance();
        if (!auth.isConfigured()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Maprizon login is not configured yet (no Auth0 client_id is baked into this build).",
                    "Maprizon login unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }

        ServerSocket server = null;
        int port = -1;
        for (int p : PORTS) {
            try {
                server = new ServerSocket(p, 1, InetAddress.getByName("127.0.0.1"));
                port = p;
                break;
            } catch (IOException e) {
                Logging.trace("Maprizon: loopback port " + p + " unavailable: " + e);
            }
        }
        if (server == null) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Could not open a local login port. Close other apps using ports "
                            + PORTS[0] + "–" + PORTS[PORTS.length - 1] + " and try again.",
                    "Maprizon login failed", JOptionPane.ERROR_MESSAGE);
            return;
        }

        final ServerSocket srv = server;
        final String redirectUri = "http://127.0.0.1:" + port + CALLBACK_PATH;
        final String verifier = ViewerAuth.randomUrlToken();
        final String challenge = ViewerAuth.codeChallengeS256(verifier);
        final String state = ViewerAuth.randomUrlToken();
        final String authorizeUrl = auth.buildAuthorizeUrl(redirectUri, state, challenge);

        JDialog dialog = buildWaitingDialog(srv);
        dialog.setVisible(true);

        Thread worker = new Thread(() -> {
            try {
                srv.setSoTimeout(LOGIN_TIMEOUT_MS);
                Map<String, String> params = awaitCallback(srv);
                String returnedState = params.get("state");
                String code = params.get("code");
                String error = params.get("error");

                if (error != null) {
                    finish(dialog, "Login was cancelled or denied.");
                    return;
                }
                if (code == null || returnedState == null || !returnedState.equals(state)) {
                    finish(dialog, "Login failed (state mismatch) — please try again.");
                    return;
                }
                auth.exchangeCode(code, verifier, redirectUri);
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                            "Logged in to Maprizon"
                                    + (auth.email().isEmpty() ? "." : " as " + auth.email() + "."),
                            "Maprizon", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (SocketException se) {
                // Socket closed = user cancelled via the dialog. No error popup.
                Logging.trace("Maprizon: login cancelled: " + se);
            } catch (SocketTimeoutException te) {
                finish(dialog, "Login timed out — please try again.");
            } catch (IOException ioe) {
                Logging.warn("Maprizon: login failed: " + ioe);
                finish(dialog, "Login failed: " + ioe.getMessage());
            } finally {
                closeQuietly(srv);
            }
        }, "maprizon-login-loopback");
        worker.setDaemon(true);
        worker.start();

        // Best-effort auto-open; the dialog's button is the fallback.
        openBrowser(authorizeUrl);
    }

    /** Accept one HTTP request on the loopback socket, reply with a friendly page,
     * and return its query params (code/state/error). */
    private static Map<String, String> awaitCallback(ServerSocket srv) throws IOException {
        try (Socket socket = srv.accept()) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine(); // e.g. "GET /maprizon-callback?code=..&state=.. HTTP/1.1"
            Map<String, String> params = parseQuery(requestLine);

            boolean ok = params.containsKey("code") && !params.containsKey("error");
            String body = "<!doctype html><html><head><meta charset='utf-8'><title>Maprizon</title></head>"
                    + "<body style='font-family:sans-serif;text-align:center;padding:3em'>"
                    + (ok
                        ? "<h2>You're logged in to Maprizon.</h2><p>You can close this tab and return to JOSM.</p>"
                        : "<h2>Maprizon login was not completed.</h2><p>You can close this tab and try again in JOSM.</p>")
                    + "</body></html>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
            return params;
        }
    }

    private static Map<String, String> parseQuery(String requestLine) {
        Map<String, String> params = new HashMap<>();
        if (requestLine == null) {
            return params;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return params;
        }
        String path = parts[1];
        int q = path.indexOf('?');
        if (q < 0) {
            return params;
        }
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = urlDecode(pair.substring(0, eq));
            String v = urlDecode(pair.substring(eq + 1));
            params.put(k, v);
        }
        return params;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static JDialog buildWaitingDialog(ServerSocket srv) {
        JDialog dialog = new JDialog(MainApplication.getMainFrame(), "Log in to Maprizon", false);
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JLabel line1 = new JLabel("Complete the login in your browser…");
        line1.setAlignmentX(0.5f);
        JLabel line2 = new JLabel("<html><span style='font-size:90%'>"
                + "It will return here automatically when you approve.</span></html>");
        line2.setAlignmentX(0.5f);

        JButton cancel = new JButton("Cancel");
        cancel.setAlignmentX(0.5f);
        cancel.addActionListener(e -> {
            closeQuietly(srv); // unblocks accept() → treated as cancel
            dialog.dispose();
        });

        root.add(line1);
        root.add(Box.createVerticalStrut(6));
        root.add(line2);
        root.add(Box.createVerticalStrut(12));
        root.add(cancel);

        dialog.setContentPane(root);
        dialog.setMinimumSize(new Dimension(340, 150));
        dialog.pack();
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        return dialog;
    }

    private static void finish(JDialog dialog, String message) {
        SwingUtilities.invokeLater(() -> {
            dialog.dispose();
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    message, "Maprizon", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private static void openBrowser(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            Logging.warn("Maprizon: could not open browser for login: " + e);
        }
    }

    private static void closeQuietly(ServerSocket srv) {
        try {
            if (!srv.isClosed()) {
                srv.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
