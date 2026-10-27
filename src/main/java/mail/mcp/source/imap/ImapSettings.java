package mail.mcp.source.imap;

import mail.mcp.connections.AuthKind;
import mail.mcp.connections.Secret;
import mail.mcp.source.oauth.google.GoogleOAuthSettings;

/**
 * Settings of one IMAP mailbox, using implicit TLS with an app password or Gmail OAuth2.
 *
 * @param host     IMAP server, e.g. {@code imap.gmail.com}
 * @param port     IMAP port, 993 by default
 * @param tls      implicit TLS; plain IMAP is rejected by the loader
 * @param auth     app-password or Gmail oauth2
 * @param username login, usually the full address
 * @param password app password, asked for only at sign-in; never logged, never returned by a tool;
 *                 null for oauth2
 * @param oauth2   Google OAuth file locations; no secret contents, null for app-password
 */
public record ImapSettings(
        String host,
        int port,
        boolean tls,
        AuthKind auth,
        String username,
        Secret password,
        GoogleOAuthSettings oauth2
) {

    public ImapSettings(String host, int port, boolean tls, AuthKind auth, String username, String password) {
        this(host, port, tls, auth, username, Secret.of(password), null);
    }

    public static final int DEFAULT_PORT = 993;

    /** The password is masked: records print every component by default. */
    @Override
    public String toString() {
        return "ImapSettings[host=" + host + ", port=" + port + ", tls=" + tls + ", auth=" + auth.id()
                + ", username=" + username + ", password=" + (password == null ? "null" : "***") + "]";
    }
}
