package mail.mcp.connections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mailboxes this server serves, and their lazily created connections.
 *
 * <p>Every lookup names its connection — there is no implicit default (decision on question 12).
 * Definitions are fixed at startup; the file is re-read only by restarting the server. Connections
 * are created on first use, one lock per connection, so a slow or unreachable mail server never
 * blocks a call to another one. A failed creation is not cached: the next call tries again. Creating
 * a connection does not sign in; the "no retry after a rejected sign-in" rule belongs to the code
 * that talks to the server (synchronization).
 *
 * <p>Copied from jdbc-mcp-server {@code connection/ConnectionRegistry} @ {@code c2c4362} with mail
 * types.
 */
public final class ConnectionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Map<String, ConnectionDefinition> definitions;
    private final Map<String, Holder> holders;
    private final List<String> orderedNames;

    public ConnectionRegistry(List<ConnectionDefinition> definitions, MailConnectionFactory factory) {
        Map<String, ConnectionDefinition> byName = new LinkedHashMap<>();
        Map<String, Holder> holders = new LinkedHashMap<>();
        for (ConnectionDefinition definition : definitions) {
            byName.put(definition.name(), definition);
            holders.put(definition.name(), new Holder(definition, factory));
        }
        this.definitions = Map.copyOf(byName);
        // Map.copyOf loses the configured order; keep the ordered view for listings.
        this.orderedNames = List.copyOf(byName.keySet());
        this.holders = Map.copyOf(holders);
    }

    /** Configured connections, in the order they were declared. */
    public List<ConnectionDefinition> definitions() {
        List<ConnectionDefinition> out = new ArrayList<>(orderedNames.size());
        for (String name : orderedNames) {
            out.add(definitions.get(name));
        }
        return List.copyOf(out);
    }

    public Collection<String> names() {
        return orderedNames;
    }

    /** True when this connection's objects have already been created in this process. */
    public boolean isInitialized(String name) {
        Holder holder = holders.get(name);
        return holder != null && holder.created();
    }

    /**
     * Validates the connection name a tool call named.
     *
     * @throws IllegalArgumentException when the name is missing or unknown
     */
    public String resolveName(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("No connection given. Pass 'connection' (available: "
                    + String.join(", ", orderedNames) + ").");
        }
        String name = requested.trim();
        if (!definitions.containsKey(name)) {
            throw new IllegalArgumentException("Unknown connection '" + name + "'. Available: "
                    + String.join(", ", orderedNames));
        }
        return name;
    }

    public ConnectionDefinition definition(String requested) {
        return definitions.get(resolveName(requested));
    }

    /**
     * Resolves and, on first use, creates the connection.
     *
     * @throws IllegalArgumentException when the name cannot be resolved or the connection is
     *                                  unusable
     */
    public MailConnection resolve(String requested) {
        return holders.get(resolveName(requested)).get();
    }

    @Override
    public void close() {
        for (Holder holder : holders.values()) {
            holder.close();
        }
    }

    /**
     * One lazily created connection. Synchronising on the holder rather than on the registry keeps
     * mailboxes independent.
     */
    private static final class Holder implements AutoCloseable {

        private final ConnectionDefinition definition;
        private final MailConnectionFactory factory;
        private volatile MailConnection connection;

        private Holder(ConnectionDefinition definition, MailConnectionFactory factory) {
            this.definition = definition;
            this.factory = factory;
        }

        boolean created() {
            return connection != null;
        }

        MailConnection get() {
            MailConnection current = connection;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (connection == null) {
                    connection = factory.create(definition);
                }
                return connection;
            }
        }

        @Override
        public void close() {
            MailConnection current;
            synchronized (this) {
                current = connection;
                connection = null;
            }
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close connection '{}': {}", definition.name(), e.getMessage());
            }
        }
    }
}
