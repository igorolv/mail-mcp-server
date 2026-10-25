package mail.mcp.connections;

import mail.mcp.source.imap.ImapClient;
import mail.mcp.source.imap.ImapMailSource;
import mail.mcp.source.ews.EwsClient;
import mail.mcp.source.ews.EwsMailSource;
import mail.mcp.source.ews.HttpClient5EwsTransport;

/**
 * Builds the lazy session factory of one connection. Split from {@link ConnectionRegistry} so tests can
 * drive the registry with hand-made connections.
 */
@FunctionalInterface
public interface MailConnectionFactory {

    MailConnection create(ConnectionDefinition definition);

    /**
     * The production factory: fresh lazy {@link ImapMailSource} and {@link EwsMailSource} sessions.
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
            return switch (definition.type()) {
                case EWS -> new MailConnection(definition,
                        () -> new EwsMailSource(new EwsClient(new HttpClient5EwsTransport(definition.ews()))));
                case IMAP -> new MailConnection(definition, () -> new ImapMailSource(new ImapClient(definition.imap())));
            };
        };
    }
}
