package mail.mcp.connections;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionRegistryTest {

    private static ConnectionDefinition definition(String name, ConnectionType type, String configError) {
        return new ConnectionDefinition(name, null, type, type == null ? null : type.defaultAuth(), null, null,
                Path.of("data", name), configError);
    }

    @Test
    void connectionsAreCreatedLazilyAndOnce() {
        AtomicInteger created = new AtomicInteger();
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("work", ConnectionType.EWS, null), definition("gmail", ConnectionType.IMAP, null)),
                d -> {
                    created.incrementAndGet();
                    return new MailConnection(d, null);
                });

        assertThat(registry.names()).containsExactly("work", "gmail");
        assertThat(registry.isInitialized("work")).isFalse();
        assertThat(created).hasValue(0);

        MailConnection first = registry.resolve("work");
        MailConnection second = registry.resolve(" work ");

        assertThat(first).isSameAs(second);
        assertThat(created).hasValue(1);
        assertThat(registry.isInitialized("work")).isTrue();
        assertThat(registry.isInitialized("gmail")).isFalse();
    }

    @Test
    void missingOrUnknownNamesListTheAvailableOnes() {
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("work", ConnectionType.EWS, null)), d -> new MailConnection(d, null));

        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("available: work");
        assertThatThrownBy(() -> registry.resolve("personal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown connection 'personal'. Available: work");
    }

    @Test
    void failedCreationIsNotCached() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("work", ConnectionType.EWS, null)), d -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("first attempt fails");
                    }
                    return new MailConnection(d, null);
                });

        assertThatThrownBy(() -> registry.resolve("work")).hasMessage("first attempt fails");
        assertThat(registry.isInitialized("work")).isFalse();
        assertThat(registry.resolve("work")).isNotNull();
        assertThat(attempts).hasValue(2);
    }

    @Test
    void standardFactoryRefusesUnusableAndNotYetImplementedConnections() {
        MailConnectionFactory factory = MailConnectionFactory.standard();

        assertThatThrownBy(() -> factory.create(definition("broken", ConnectionType.EWS, "no 'url'")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be used: no 'url'");
        assertThatThrownBy(() -> factory.create(definition("gmail", ConnectionType.IMAP, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type 'imap'");
    }

    @Test
    void closeReleasesCreatedConnections() {
        ConnectionRegistry registry = new ConnectionRegistry(
                List.of(definition("work", ConnectionType.EWS, null)), d -> new MailConnection(d, null));
        registry.resolve("work");

        registry.close();

        assertThat(registry.isInitialized("work")).isFalse();
    }
}
