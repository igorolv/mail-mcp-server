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

    private String archiveMessage() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("folder/entry.txt"));
            zip.write("Archive needle text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String key = keys.getFirst();
        Files.writeString(store.messageDir(key).resolve(MessageStore.MESSAGE_FILE),
                "Content-Type: application/zip\r\nContent-Disposition: attachment; filename=archive.zip\r\n"
                        + "Content-Transfer-Encoding: base64\r\n\r\n"
                        + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
        new MessageProcessor(store, new Pandoc("unused")).process(store.readMeta(key).orElseThrow());
        return key;
    }

    @Test void getMessageShowsArchiveEntriesWithParent() throws Exception {
        String key = archiveMessage();
        var result = tools.getMessage("local", key, null, null);
        assertThat(result.attachments()).extracting(a -> a.file()).containsExactly("1__archive.zip", "2__entry.txt");
        assertThat(result.attachments().getFirst().parent()).isNull();
        var entry = result.attachments().getLast();
        assertThat(entry.parent()).isEqualTo("1__archive.zip");
        assertThat(entry.name()).isEqualTo("folder/entry.txt");
        assertThat(Path.of(entry.path())).isRegularFile();
        assertThat(Path.of(entry.textPath())).isRegularFile();
        var mapper = new JsonConfig().mailMcpObjectMapper();
        assertThat(mapper.writeValueAsString(entry)).contains("\"parent\":\"1__archive.zip\"");
        assertThat(mapper.writeValueAsString(result.attachments().getFirst())).doesNotContain("\"parent\"");
        Files.delete(Path.of(entry.path()));
        Files.delete(Path.of(entry.textPath()));
        entry = tools.getMessage("local", key, null, null).attachments().getLast();
        assertThat(entry.parent()).isEqualTo("1__archive.zip");
        assertThat(entry.path()).isNull();
        assertThat(entry.textPath()).isNull();
        assertThat(store.messageDir(key).resolve("attachments/2__entry.txt")).doesNotExist();
    }

    @Test void getAttachmentReadsArchiveEntry() throws Exception {
        String key = archiveMessage();
        var entry = tools.getMessage("local", key, null, null).attachments().getLast();
        var result = tools.getAttachment("local", key, entry.file(), 8, 6);
        assertThat(result.file()).isEqualTo("2__entry.txt");
        assertThat(result.name()).isEqualTo("folder/entry.txt");
        assertThat(result.path()).isEqualTo(entry.path());
        assertThat(result.textPath()).isEqualTo(entry.textPath());
        assertThat(result.text()).isEqualTo("needle");
        assertThat(result.nextOffset()).isEqualTo(14);
        assertThat(result.textLength()).isEqualTo("Archive needle text".length());
        assertThatThrownBy(() -> tools.getAttachment("local", key, "folder/entry.txt", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void getMessageAndAttachmentExposeOutlookEntries() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mail.mcp.convert.PandocTest.pandocAvailable());
        for (String id : new String[]{"M09", "M10"}) {
            String key = keys.getFirst();
            var directory = store.messageDir(key);
            Files.copy(mail.mcp.OutlookFixtures.fixture(id), directory.resolve(MessageStore.MESSAGE_FILE),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var processed = new MessageProcessor(store, new Pandoc("pandoc")).process(store.readMeta(key).orElseThrow());
            var result = tools.getMessage("local", key, null, null);
            assertThat(result.attachments()).hasSize(id.equals("M09") ? 4 : 2);
            for (int i = 0; i < result.attachments().size(); i++) {
                var entry = result.attachments().get(i);
                var expected = processed.message().attachments().get(i);
                assertThat(entry.file()).isEqualTo(expected.file());
                assertThat(entry.parent()).isEqualTo(expected.parent());
                var actual = tools.getAttachment("local", key, entry.file(), null, null);
                assertThat(actual.path()).isEqualTo(entry.path());
                assertThat(Files.size(Path.of(actual.path()))).isEqualTo(expected.size());
                if (entry.textPath() != null) { assertThat(actual.text()).isEqualTo(Files.readString(Path.of(entry.textPath()))); }
            }
            var child = result.attachments().getLast();
            assertThat(child.parent()).isEqualTo(id.equals("M09") ? "3__package.zip" : "1__winmail.dat");
            if (id.equals("M10")) {
                assertThat(child.name()).isEqualTo("Длинное имя вложения для учебного письма.txt");
                assertThat(tools.getAttachment("local", key, child.file(), null, null).text()).isNotBlank();
            }
        }
    }

    @Test void getAttachmentExposesLegacyOfficeText() throws Exception {
        String key = keys.getFirst();
        var directory = store.messageDir(key);
        Files.write(directory.resolve(MessageStore.MESSAGE_FILE), mail.mcp.LegacyOfficeFixtures.message());
        new MessageProcessor(store, new Pandoc("unused")).process(store.readMeta(key).orElseThrow());
        var message = tools.getMessage("local", key, null, null);
        assertThat(message.state()).isEqualTo("converted");
        assertThat(message.attachments()).hasSize(4);
        for (var entry : message.attachments().subList(0, 3)) {
            var result = tools.getAttachment("local", key, entry.file(), null, null);
            assertThat(result.file()).isEqualTo(entry.file());
            assertThat(result.path()).isEqualTo(entry.path());
            assertThat(result.textPath()).isEqualTo(entry.textPath());
            assertThat(result.text()).isEqualTo(Files.readString(Path.of(entry.textPath())));
            assertThat(result.textLength()).isEqualTo(result.text().length());
        }
        assertThat(tools.getAttachment("local", key, "4__broken.doc", null, null).text()).isNull();
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
