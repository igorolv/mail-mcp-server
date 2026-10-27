package mail.mcp.tools;

import mail.mcp.config.JsonConfig;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionType;
import mail.mcp.index.MailIndex;
import mail.mcp.model.MailMessage;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadToolsTest {
    @TempDir Path dir;

    @Test void threadReturnsCopiesWithKeysAndFolders() {
        var mapper = new JsonConfig().mailMcpObjectMapper();
        var tools = new ThreadTools(registry(), mapper);
        var keys = IntStream.range(1, 5).mapToObj(i -> new UUID(0, i).toString()).toList();
        var date = Instant.parse("2026-10-26T00:00:00Z");
        try (var writer = MailIndex.openWriter(dir.resolve("local"));
             var other = MailIndex.openWriter(dir.resolve("other"))) {
            for (int i = 0; i < 4; i++) {
                writer.put(new MessageMeta(keys.get(i), "f" + (i + 1), "INBOX", keys.get(i), i, null,
                                Instant.now(), MessageState.CONVERTED, null, null, i == 0, i == 0),
                        new MailMessage("Subject", List.of(new mail.mcp.model.Address(null, "user@example.com")), null, null,
                                i == 0 ? date.minusSeconds(1) : date, i == 0 ? "<root>" : "<copy>",
                                i == 0 ? null : "<root>", null, null), null, null);
            }
            other.put(new MessageMeta(keys.get(3), "f9", "INBOX", "remote", 0, null, Instant.now(), MessageState.CONVERTED, null),
                    new MailMessage("Subject", List.of(new mail.mcp.model.Address(null, "user@example.com")), null, null,
                            date, "<copy>", "<root>", null, null), null, null);
            writer.commit();
            other.commit();
            var first = tools.getThread("local", keys.get(3), 0, 1);
            assertThat(first.total()).isEqualTo(2);
            assertThat(first.nextOffset()).isEqualTo(1);
            assertThat(mapper.readTree(mapper.writeValueAsString(first)).at("/messages/0").has("copies")).isFalse();
            var second = tools.getThread("local", keys.get(3), first.nextOffset(), 1);
            assertThat(second.key()).isEqualTo(keys.get(3));
            assertThat(second.nextOffset()).isNull();
            var card = second.messages().getFirst();
            assertThat(card.key()).isEqualTo(keys.get(1));
            assertThat(card.folder()).isEqualTo("f2");
            assertThat(card.copies()).containsExactly(new mail.mcp.api.MessageCopyInfo(keys.get(2), "f3"),
                    new mail.mcp.api.MessageCopyInfo(keys.get(3), "f4"));
            var json = mapper.readTree(mapper.writeValueAsString(card));
            assertThat(json.at("/copies/0/key").asString()).isEqualTo(keys.get(2));
            assertThat(json.at("/copies/0/folder").asString()).isEqualTo("f3");
            var separate = tools.getThread("other", keys.get(3), null, null);
            assertThat(separate.total()).isEqualTo(1);
            assertThat(separate.messages().getFirst().copies()).isEmpty();
        }
    }

    private ConnectionRegistry registry() {
        return new ConnectionRegistry(List.of(
                new ConnectionDefinition("local", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD, null, null, dir.resolve("local"), "unconfigured"),
                new ConnectionDefinition("other", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD, null, null, dir.resolve("other"), "unconfigured")),
                definition -> { throw new AssertionError("no source construction"); });
    }

    private void put(MailIndex writer, String key, String folder) {
        writer.put(new MessageMeta(key, folder, "INBOX", key, 0, null, Instant.now(), MessageState.CONVERTED, null),
                new MailMessage("Subject", null, null, null, null, "<shared@example.com>", null, null, null),
                "Unread body", "Unread attachment text");
    }

    @Test void threadValidatesArgumentsAnchorAndSchema() throws Exception {
        var tools = new ThreadTools(registry(), new JsonConfig().mailMcpObjectMapper());
        String key = UUID.randomUUID().toString();
        // An invalid index path proves argument checks happen before opening it.
        Files.createDirectories(dir.resolve("local/index"));
        Files.writeString(dir.resolve("local/index/segments_1"), "not an index");
        assertThatThrownBy(() -> tools.getThread("local", key, -1, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("offset");
        for (int limit : List.of(0, -1)) {
            assertThatThrownBy(() -> tools.getThread("local", key, null, limit)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        }
        for (String invalid : new String[] {null, "", "../escape", "1-1-1-1-1", "ABCDEF00-0000-0000-0000-000000000001"}) {
            assertThatThrownBy(() -> tools.getThread("local", invalid, null, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UUID");
        }
        for (String connection : new String[] {null, "", "missing"}) {
            assertThatThrownBy(() -> tools.getThread(connection, key, null, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("connection");
        }
        Files.delete(dir.resolve("local/index/segments_1"));
        assertThatThrownBy(() -> tools.getThread("local", key, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message is not indexed; use listMessages");
        assertThatThrownBy(() -> tools.getThread("other", key, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message is not indexed; use listMessages");
        try (var writer = MailIndex.openWriter(dir.resolve("local"))) {
            put(writer, UUID.randomUUID().toString(), "f1");
            writer.commit();
            assertThatThrownBy(() -> tools.getThread("local", key, null, null)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Message is not indexed; use listMessages");
        }
        try (var directory = org.apache.lucene.store.FSDirectory.open(dir.resolve("local/index"));
             var writer = new org.apache.lucene.index.IndexWriter(directory, new org.apache.lucene.index.IndexWriterConfig())) {
            writer.setLiveCommitData(java.util.Map.of("schema", "old").entrySet());
            writer.commit();
        }
        assertThatThrownBy(() -> tools.getThread("local", key, null, null)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The index was built by an older version; run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
    }

    @Test void threadUsesOneConnectionAndCommittedLocalDataOnly() {
        var registry = registry();
        var tools = new ThreadTools(registry, new JsonConfig().mailMcpObjectMapper());
        var keys = IntStream.range(0, 205).mapToObj(i -> new UUID(0, i + 1).toString()).toList();
        try (var other = MailIndex.openWriter(dir.resolve("other"));
             var writer = MailIndex.openWriter(dir.resolve("local"))) {
            put(other, UUID.randomUUID().toString(), "f1");
            other.commit();
            for (int i = 0; i < keys.size(); i++) { put(writer, keys.get(i), i % 2 == 0 ? "f1" : "f2"); }
            writer.commit();
            String pending = UUID.randomUUID().toString();
            put(writer, pending, "f1");
            var defaults = tools.getThread(" local ", keys.getFirst(), null, null);
            assertThat(defaults.connection()).isEqualTo("local");
            assertThat(defaults.key()).isEqualTo(keys.getFirst());
            assertThat(defaults.total()).isEqualTo(205);
            assertThat(defaults.offset()).isZero();
            assertThat(defaults.nextOffset()).isEqualTo(50);
            assertThat(defaults.messages()).extracting(c -> c.key()).containsExactlyElementsOf(keys.subList(0, 50));
            assertThat(defaults.messages()).extracting(c -> c.folder()).contains("f1", "f2");
            assertThat(defaults.messages().getFirst().localPath()).isEqualTo(dir.resolve("local/messages/00").resolve(keys.getFirst()).toAbsolutePath().toString());
            var capped = tools.getThread("local", keys.getFirst(), 0, Integer.MAX_VALUE);
            assertThat(capped.messages()).hasSize(200);
            assertThat(capped.nextOffset()).isEqualTo(200);
            var last = tools.getThread("local", keys.getFirst(), 200, 200);
            assertThat(last.messages()).extracting(c -> c.key()).containsExactlyElementsOf(keys.subList(200, 205));
            assertThat(last.nextOffset()).isNull();
            var beyond = tools.getThread("local", keys.getFirst(), Integer.MAX_VALUE, 1);
            assertThat(beyond.total()).isEqualTo(205);
            assertThat(beyond.messages()).isEmpty();
            assertThat(beyond.nextOffset()).isNull();
            assertThatThrownBy(() -> tools.getThread("local", pending, null, null)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Message is not indexed; use listMessages");
            assertThat(registry.isInitialized("local")).isFalse();
            assertThat(registry.isInitialized("other")).isFalse();
            assertThat(dir.resolve("local/messages")).doesNotExist();
            writer.commit();
            assertThat(tools.getThread("local", pending, null, null).total()).isEqualTo(206);
        }
    }

    @Test void threadExposesStoredFlags() {
        var tools = new ThreadTools(registry(), new JsonConfig().mailMcpObjectMapper());
        var values = new Boolean[] {true, false, null};
        var keys = new java.util.ArrayList<String>();
        try (var writer = MailIndex.openWriter(dir.resolve("local"))) {
            for (Boolean value : values) {
                String key = UUID.randomUUID().toString();
                keys.add(key);
                writer.put(new MessageMeta(key, "f1", "INBOX", key, 0, null, Instant.now(),
                                MessageState.CONVERTED, null, null, value, value),
                        new MailMessage("Flags", null, null, null, null, "<shared@example.com>", null, null, null), null, null);
            }
            writer.commit();
            for (int i = 0; i < values.length; i++) {
                String key = keys.get(i);
                var card = tools.getThread("local", keys.getFirst(), null, null).messages().stream()
                        .filter(c -> c.key().equals(key)).findFirst().orElseThrow();
                assertThat(card.read()).isEqualTo(values[i]);
                assertThat(card.flagged()).isEqualTo(values[i]);
            }
        }
        assertThat(dir.resolve("local/messages")).doesNotExist();
    }

}
