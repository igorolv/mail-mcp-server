package mail.mcp.connections;

import java.util.Map;

/**
 * On-disk shape of {@code connections.json}.
 *
 * <p>String values may reference environment variables as {@code ${VAR}} — see
 * {@link EnvironmentPlaceholders}. Keys this record does not know are logged as warnings and
 * ignored, so a typo does not go unnoticed.
 *
 * <p>Modeled on jdbc-mcp-server {@code connection/ConnectionsFile} @ {@code c2c4362}.
 *
 * @param connections connection name → definition; the name is also the local data directory under
 *                    {@code <data-dir>/}
 */
public record ConnectionsFile(
        Map<String, Entry> connections
) {

    /**
     * One mailbox.
     *
     * @param type                 {@code ews} or {@code imap}; required
     * @param description          what the mailbox is for, shown by {@code listConnections}
     * @param auth                 {@code ntlm} (default) or {@code basic} for EWS; {@code app-password}
     *                             (default) or {@code oauth2} for IMAP
     * @param url                  EWS endpoint, {@code https://.../EWS/Exchange.asmx}
     * @param host                 IMAP host
     * @param port                 IMAP port, 993 by default
     * @param tls                  IMAP implicit TLS, {@code true} by default
     * @param username             login
     * @param password             password or app password; prefer {@code ${VAR}}
     * @param domain               EWS NTLM domain; leave out for a bare login
     * @param connectTimeoutSeconds EWS connect timeout, 15 by default
     * @param readTimeoutSeconds   EWS read timeout, 120 by default
     * @param oauthClientFile      Google Desktop client JSON path, required for Gmail OAuth2
     */
    public record Entry(
            String type,
            String description,
            String auth,
            String url,
            String host,
            Integer port,
            Boolean tls,
            String username,
            String password,
            String domain,
            Integer connectTimeoutSeconds,
            Integer readTimeoutSeconds,
            String oauthClientFile
    ) {
    }
}
