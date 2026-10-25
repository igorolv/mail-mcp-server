package mail.mcp.tools;

import mail.mcp.config.JsonConfig;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionType;
import mail.mcp.convert.Pandoc;
import mail.mcp.index.MailIndex;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.MessageProcessor;
import mail.mcp.sync.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailToolsTest {
    @TempDir Path dir;
    private MessageStore store;
    private MailTools tools;
    private List<String> keys;

    @BeforeEach void prepare() throws Exception {
        var mapper = new JsonConfig().mailMcpObjectMapper();
        store = new MessageStore(dir, mapper);
        var definition = new ConnectionDefinition("local", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD,
                null, null, dir, null);
        var registry = new ConnectionRegistry(List.of(definition), d -> { throw new AssertionError("no source"); });
        var service = new SyncService(registry, () -> { throw new AssertionError("no pandoc"); }, mapper);
        tools = new MailTools(registry, service, mapper);
        store.saveFolders(List.of(new LocalFolder("f1", "INBOX", "INBOX", "7"),
                new LocalFolder("f2", "Archive", "Archive", null)));
        try (var index = MailIndex.openWriter(dir)) {
            for (String fixture : List.of("M01-rfc2231-continuations.eml", "M16-unsafe-filenames.eml")) {
                String key = store.newKey();
                Files.copy(Path.of("testdata/mime", fixture), store.createMessageDir(key).resolve(MessageStore.MESSAGE_FILE));
                var meta = new MessageMeta(key, "f1", "INBOX", fixture, 0, null, Instant.now(), MessageState.DOWNLOADED, null);
                store.writeMeta(meta);
                var processed = new MessageProcessor(store, new Pandoc("unused")).process(meta);
                assertThat(processed.meta().state()).isEqualTo(MessageState.CONVERTED);
                index.put(processed.meta(), processed.message(), processed.body(), processed.attachmentText());
            }
            index.commit();
        }
        keys = store.keys();
    }

    @Test void getAttachmentChunksTheText() throws Exception {
        String key = keys.getFirst();
        var attachment = tools.getMessage("local", key, null, null).attachments().getFirst();
        Path markdown = Path.of(attachment.path() + ".md");
        String expected = "Attachment text 😀 конец";
        Files.writeString(markdown, expected);
        var text = new StringBuilder();
        Integer offset = 0;
        do {
            var chunk = tools.getAttachment("local", key, attachment.file(), offset, 3);
            assertThat(chunk.key()).isEqualTo(key);
            assertThat(chunk.file()).isEqualTo(attachment.file());
            assertThat(chunk.name()).isEqualTo(attachment.name());
            assertThat(chunk.contentType()).isEqualTo(attachment.contentType());
            assertThat(chunk.size()).isEqualTo(attachment.size());
            assertThat(chunk.path()).isEqualTo(attachment.path());
            assertThat(chunk.textPath()).isEqualTo(markdown.toString());
            assertThat(chunk.offset()).isEqualTo(offset);
            assertThat(chunk.textLength()).isEqualTo(expected.length());
            assertThat(chunk.text()).hasSizeLessThanOrEqualTo(3);
            text.append(chunk.text());
            offset = chunk.nextOffset();
        } while (offset != null);
        assertThat(text.toString()).isEqualTo(expected);
        var beyond = tools.getAttachment("local", key, attachment.file(), Integer.MAX_VALUE, 10);
        assertThat(beyond.text()).isEmpty();
        assertThat(beyond.nextOffset()).isNull();
        Files.writeString(markdown, "x".repeat(100_001));
        assertThat(tools.getAttachment("local", key, attachment.file(), null, null).text()).hasSize(20_000);
        assertThat(tools.getAttachment("local", key, attachment.file(), 0, Integer.MAX_VALUE).text()).hasSize(100_000);
        assertThatThrownBy(() -> tools.getAttachment("local", key, attachment.file(), -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.getAttachment("local", key, attachment.file(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        Files.delete(markdown);
        var absent = tools.getAttachment("local", key, attachment.file(), 0, 10);
        assertThat(absent.text()).isNull();
        assertThat(absent.textPath()).isNull();
        assertThat(absent.textLength()).isZero();
        assertThat(absent.nextOffset()).isNull();
        String json = new JsonConfig().mailMcpObjectMapper().writeValueAsString(absent);
        assertThat(json).doesNotContain("\"text\"", "\"textPath\"");
    }

    @Test void getAttachmentRejectsUnknownFile() {
        String key = keys.getFirst();
        var ownFiles = tools.getMessage("local", key, null, null).attachments().stream().map(a -> a.file()).toList();
        String other = tools.getMessage("local", keys.getLast(), null, null).attachments().stream()
                .map(a -> a.file()).filter(file -> !ownFiles.contains(file)).findFirst().orElseThrow();
        for (String file : List.of("missing", "..\\..\\x", other)) {
            assertThatThrownBy(() -> tools.getAttachment("local", key, file, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tools.getAttachment("local", key, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void getMessageShowsTextPath() throws Exception {
        String key = keys.getFirst();
        var attachment = tools.getMessage("local", key, null, null).attachments().getFirst();
        Path markdown = Path.of(attachment.path() + ".md");
        Files.writeString(markdown, "Saved text");
        assertThat(tools.getMessage("local", key, null, null).attachments().getFirst().textPath())
                .isEqualTo(markdown.toString());
        Files.delete(markdown);
        assertThat(tools.getMessage("local", key, null, null).attachments().getFirst().textPath()).isNull();
    }

    @Test void listFoldersCountsMessagesAndShowsSynced() {
        var result = tools.listFolders("local");
        assertThat(result.folders()).extracting(f -> f.key()).containsExactly("f1", "f2");
        assertThat(result.folders().getFirst().messages()).isEqualTo(2);
        assertThat(result.folders().getFirst().synced()).isTrue();
        assertThat(result.folders().getLast().messages()).isZero();
        assertThat(result.folders().getLast().synced()).isFalse();
    }

    @Test void listMessagesPagesWithNextOffset() {
        var first = tools.listMessages("local", "f1", null, 1);
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.offset()).isZero();
        assertThat(first.nextOffset()).isEqualTo(1);
        assertThat(first.messages()).hasSize(1);
        assertThat(Path.of(first.messages().getFirst().localPath())).isAbsolute().isDirectory();
        var second = tools.listMessages("local", "f1", first.nextOffset(), 1);
        assertThat(second.nextOffset()).isNull();
        assertThat(second.messages()).hasSize(1);
        assertThat(second.messages().getFirst().key()).isNotEqualTo(first.messages().getFirst().key());
        assertThat(tools.listMessages("local", "f1", Integer.MAX_VALUE, null).messages()).isEmpty();
    }

    @Test void unknownFolderKeyIsRejected() {
        assertThatThrownBy(() -> tools.listMessages("local", "missing", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown folder");
    }

    @Test void limitAndMaxCharsAreCapped() throws Exception {
        try (var index = MailIndex.openWriter(dir)) {
            for (int i = 0; i < 201; i++) {
                index.put(new MessageMeta(store.newKey(), "f1", "INBOX", "extra" + i, 0, null,
                        Instant.now(), MessageState.FAILED, "synthetic"), null, null, null);
            }
            index.commit();
        }
        assertThat(tools.listMessages("local", "f1", null, 1000).messages()).hasSize(200);
        assertThat(tools.listMessages("local", "f1", null, null).messages()).hasSize(50);
        Files.writeString(store.messageDir(keys.getFirst()).resolve(MessageStore.BODY_FILE), "x".repeat(100_001));
        assertThat(tools.getMessage("local", keys.getFirst(), null, Integer.MAX_VALUE).body()).hasSize(100_000);
        assertThat(tools.getMessage("local", keys.getFirst(), null, null).body()).hasSize(20_000);
        assertThatThrownBy(() -> tools.listMessages("local", "f1", -1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.listMessages("local", "f1", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.getMessage("local", keys.getFirst(), -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.getMessage("local", keys.getFirst(), 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void getMessageChunksTheBody() throws Exception {
        String key = keys.getFirst();
        String expected = Files.readString(store.messageDir(key).resolve(MessageStore.BODY_FILE));
        StringBuilder body = new StringBuilder();
        Integer offset = 0;
        do {
            var chunk = tools.getMessage("local", key, offset, 10);
            assertThat(chunk.body()).hasSizeLessThanOrEqualTo(10);
            assertThat(chunk.bodyLength()).isEqualTo(expected.length());
            assertThat(chunk.offset()).isEqualTo(offset);
            body.append(chunk.body());
            offset = chunk.nextOffset();
        } while (offset != null);
        assertThat(body.toString()).isEqualTo(expected);
        var beyond = tools.getMessage("local", key, Integer.MAX_VALUE, 10);
        assertThat(beyond.body()).isEmpty();
        assertThat(beyond.nextOffset()).isNull();
    }

    @Test void getMessageListsAttachmentPaths() {
        for (String key : keys) {
            var message = tools.getMessage("local", key, null, null);
            assertThat(message.subject()).isNotBlank();
            assertThat(message.from()).isNotEmpty();
            assertThat(message.attachments()).isNotEmpty().allSatisfy(a -> {
                assertThat(Path.of(a.path())).isRegularFile();
                assertThat(Path.of(a.path()).startsWith(Path.of(message.localPath()))).isTrue();
            });
        }
    }

    @Test void invalidOrUnknownKeyIsRejected() {
        for (String key : List.of("..\\..\\x", store.newKey())) {
            assertThatThrownBy(() -> tools.getMessage("local", key, null, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void missingOrInvalidMimeStillReturnsMetadataAndBody() throws Exception {
        String key = keys.getFirst();
        var directory = store.messageDir(key);
        String body = Files.readString(directory.resolve(MessageStore.BODY_FILE));
        Files.writeString(directory.resolve(MessageStore.MESSAGE_FILE),
                "Content-Type: text/plain; charset=not-a-charset\r\n\r\nBody");
        var invalid = tools.getMessage("local", key, null, null);
        assertThat(invalid.subject()).isNull();
        assertThat(invalid.body()).isEqualTo(body);
        assertThat(invalid.state()).isEqualTo("converted");
        Files.delete(directory.resolve(MessageStore.MESSAGE_FILE));
        Files.delete(directory.resolve(MessageStore.BODY_FILE));
        var absent = tools.getMessage("local", key, null, null);
        assertThat(absent.body()).isNull();
        assertThat(absent.bodyLength()).isZero();
        assertThat(absent.nextOffset()).isNull();
    }

    @Test void missingAttachmentHasNoPath() throws Exception {
        String key = keys.getFirst();
        var first = tools.getMessage("local", key, null, null).attachments().getFirst();
        Files.delete(Path.of(first.path()));
        assertThat(tools.getMessage("local", key, null, null).attachments().getFirst().path()).isNull();
    }
}
