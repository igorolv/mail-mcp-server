package mail.mcp.connections;

import mail.mcp.source.MailSource;

import java.util.function.Supplier;

/**
 * The session factory of one connection, created on first use by {@link ConnectionRegistry}.
 * Creating a source is lazy; its caller owns and closes the session.
 *
 * @param definition the configuration it was built from
 * @param sources   creates a fresh mail session
 */
public record MailConnection(ConnectionDefinition definition, Supplier<MailSource> sources) implements AutoCloseable {

    /** A new session; the caller closes it. Signs in only on its first request. */
    public MailSource openSource() {
        return sources.get();
    }

    @Override
    public void close() { } // sessions are owned by their callers
}
