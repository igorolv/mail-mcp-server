package mail.mcp.connections;

import mail.mcp.source.ews.EwsClient;

/**
 * The live objects of one connection, created on first use by {@link ConnectionRegistry}.
 *
 * <p>Creating it does not sign in: the EWS client opens its HTTP connection on the first request.
 *
 * @param definition the configuration it was built from
 * @param ews        EWS client for an {@code ews} connection, otherwise {@code null}
 */
public record MailConnection(ConnectionDefinition definition, EwsClient ews) implements AutoCloseable {

    @Override
    public void close() {
        if (ews != null) {
            ews.close();
        }
    }
}
