package mail.mcp.connections;

import mail.mcp.source.ews.EwsClient;
import mail.mcp.source.ews.HttpClient5EwsTransport;
import mail.mcp.source.imap.ImapClient;

/**
 * Builds the live objects of one connection. Split from {@link ConnectionRegistry} so tests can
 * drive the registry with hand-made connections.
 */
@FunctionalInterface
public interface MailConnectionFactory {

    MailConnection create(ConnectionDefinition definition);

    /**
     * The production factory: an {@link EwsClient} over {@link HttpClient5EwsTransport} for
     * {@code ews}, or a lazy {@link ImapClient} for {@code imap}.
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
                case EWS -> new MailConnection(definition, new EwsClient(new HttpClient5EwsTransport(definition.ews())), null);
                case IMAP -> new MailConnection(definition, null, new ImapClient(definition.imap()));
            };
        };
    }
}
