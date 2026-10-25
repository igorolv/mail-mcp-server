package mail.mcp.connections;

import mail.mcp.source.ews.EwsClient;
import mail.mcp.source.imap.ImapClient;

/**
 * The live objects of one connection, created on first use by {@link ConnectionRegistry}.
 *
 * <p>Creating it does not sign in: both clients connect on the first request.
 *
 * @param definition the configuration it was built from
 * @param ews        EWS client for an {@code ews} connection, otherwise {@code null}
 * @param imap       IMAP client for an {@code imap} connection, otherwise {@code null}
 */
public record MailConnection(ConnectionDefinition definition, EwsClient ews, ImapClient imap) implements AutoCloseable {

    public MailConnection(ConnectionDefinition definition, EwsClient ews) {
        this(definition, ews, null);
    }

    @Override
    public void close() {
        if (ews != null) {
            ews.close();
        }
        if (imap != null) { imap.close(); }
    }
}
