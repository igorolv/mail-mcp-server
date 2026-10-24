package mail.mcp.source.imap;

import mail.mcp.connections.AuthKind;

/**
 * Settings of one IMAP mailbox (Gmail, Mail.ru). Parsed and validated already; the IMAP source
 * itself comes after the EWS spike (decision P8).
 *
 * @param host     IMAP server, e.g. {@code imap.gmail.com}
 * @param port     IMAP port, 993 by default
 * @param tls      implicit TLS; plain IMAP is rejected by the loader
 * @param auth     {@link AuthKind#APP_PASSWORD} (OAuth2 later)
 * @param username login, usually the full address
 * @param password app password; never logged, never returned by a tool
 */
public record ImapSettings(
        String host,
        int port,
        boolean tls,
        AuthKind auth,
        String username,
        String password
) {

    public static final int DEFAULT_PORT = 993;

    /** The password is masked: records print every component by default. */
    @Override
    public String toString() {
        return "ImapSettings[host=" + host + ", port=" + port + ", tls=" + tls + ", auth=" + auth.id()
                + ", username=" + username + ", password=" + (password == null ? "null" : "***") + "]";
    }
}
