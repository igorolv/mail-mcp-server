package mail.mcp.connections;

import mail.mcp.source.ews.EwsClient;
import mail.mcp.source.ews.HttpClient5EwsTransport;

/**
 * Builds the live objects of one connection. Split from {@link ConnectionRegistry} so tests can
 * drive the registry with hand-made connections.
 */
@FunctionalInterface
public interface MailConnectionFactory {

    MailConnection create(ConnectionDefinition definition);

    /**
     * The production factory: an {@link EwsClient} over {@link HttpClient5EwsTransport} for
     * {@code ews}. IMAP comes after the EWS spike (decision P8).
     *
     * @throws IllegalArgumentException when the entry is unusable or its source is not implemented
     */
    static MailConnectionFactory standard() {
        return definition -> {
            if (!definition.usable()) {
                throw new IllegalArgumentException("Connection '" + definition.name() + "' cannot be used: "
                        + definition.configError());
            }
            if (!definition.sourceImplemented()) {
                throw new IllegalArgumentException("Connection '" + definition.name() + "' has type '"
                        + definition.type().id() + "', which this server version does not read yet");
            }
            return new MailConnection(definition, new EwsClient(new HttpClient5EwsTransport(definition.ews())));
        };
    }
}
