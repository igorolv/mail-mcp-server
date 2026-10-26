package mail.mcp.tools;

import mail.mcp.config.JsonConfig;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionType;
import mail.mcp.index.MailIndex;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.FolderProgress;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.model.SyncStatus;
import mail.mcp.redaction.SecretRedactor;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutputRedactionTest {
    private static final String SECRET = "FictionOnlyOutputSecret";
    private static final String BODY = "Reporting results.\nPassword: \"" + SECRET + "\"\nFollow-up message.\n"
            + "-----BEGIN PRIVATE KEY-----\n" + "U3ludGhldGljT25seQ==\n".repeat(150)
            + "-----END PRIVATE KEY-----\n";
    @TempDir Path dir;
    private final ObjectMapper mapper = new JsonConfig().mailMcpObjectMapper();
    private final SecretRedactor redactor = new SecretRedactor(true);
    private MessageStore store;
    private ConnectionRegistry registry;
    private SyncService sync;
    private MailTools raw;
    private MailTools filtered;
    private String key;

    @BeforeEach void prepare() throws Exception {
        store = new MessageStore(dir, mapper);
        registry = new ConnectionRegistry(List.of(new ConnectionDefinition("local", "password: " + SECRET,
                ConnectionType.IMAP, AuthKind.APP_PASSWORD, null, null, dir, "token: " + SECRET)),
                definition -> { throw new AssertionError("no mailbox access"); });
        sync = new SyncService(registry, () -> { throw new AssertionError("no pandoc"); }, mapper);
        raw = new MailTools(registry, sync, mapper);
        filtered = new MailTools(registry, sync, mapper, redactor);
        store.saveFolders(List.of(new LocalFolder("f1", "password: " + SECRET, "remote", "7")));
        key = store.newKey();
        Path directory = store.createMessageDir(key);
        Files.writeString(directory.resolve(MessageStore.MESSAGE_FILE), """
                Subject: Password: %s
                From: "Password: %s" <sender@example.com>
                To: "Token: %s" <recipient@example.com>
                Message-ID: <message@example.com>
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="synthetic"

                --synthetic
                Content-Type: text/plain; charset=UTF-8

                %s
                --synthetic
                Content-Type: text/plain; charset=UTF-8
                Content-Disposition: attachment; filename="password: %s.txt"

                Password: %s
                --synthetic--
                """.formatted(SECRET, SECRET, SECRET, BODY, SECRET, SECRET).replace("\n", "\r\n"));
        var message = MimeReader.read(directory.resolve(MessageStore.MESSAGE_FILE), directory.resolve("attachments")).message();
        Files.writeString(directory.resolve(MessageStore.BODY_FILE), BODY);
        Files.writeString(directory.resolve("attachments").resolve(message.attachments().getFirst().file() + ".md"), BODY);
        var meta = new MessageMeta(key, "f1", "password: " + SECRET, "remote", 0, null, Instant.now(),
                MessageState.CONVERTED, "password: " + SECRET);
        store.writeMeta(meta);
        try (var index = MailIndex.openWriter(dir)) {
            index.put(meta, message, BODY, "password: " + SECRET);
            index.commit();
        }
        store.writeSyncStatus(new SyncStatus("failed", 0, Instant.now(), Instant.now(),
                List.of(new FolderProgress("password: " + SECRET, 1, 0, 0, false, true)), "token: " + SECRET, true));
    }

    @Test void chunksUseFilteredOffsetsAndLeaveFilesUntouched() throws Exception {
        Map<Path, byte[]> before = snapshot();
        var original = raw.getMessage("local", key, null, null);
        var originalAttachment = original.attachments().getFirst();
        var message = filtered.getMessage("local", key, null, null);
        assertThat(message.localPath()).isEqualTo(original.localPath());
        var attachment = message.attachments().getFirst();
        assertThat(attachment.file()).isEqualTo(originalAttachment.file());
        assertThat(attachment.path()).isEqualTo(originalAttachment.path());
        assertThat(attachment.textPath()).isEqualTo(originalAttachment.textPath());
        assertThat(attachment.name()).doesNotContain(SECRET);
        String expected = redactor.text(BODY);
        StringBuilder joinedBody = new StringBuilder();
        StringBuilder joinedText = new StringBuilder();
        Integer offset = 0;
        do {
            var chunk = filtered.getMessage("local", key, offset, 3);
            var text = filtered.getAttachment("local", key, attachment.file(), offset, 3);
            assertThat(chunk.bodyLength()).isEqualTo(expected.length());
            assertThat(text.textLength()).isEqualTo(expected.length());
            assertThat(text.path()).isEqualTo(originalAttachment.path());
            assertThat(chunk.body()).hasSizeLessThanOrEqualTo(3);
            assertThat(text.text()).isEqualTo(chunk.body());
            assertThat(text.nextOffset()).isEqualTo(chunk.nextOffset());
            assertThat(chunk.offset()).isEqualTo(offset);
            joinedBody.append(chunk.body());
            joinedText.append(text.text());
            offset = chunk.nextOffset();
        } while (offset != null);
        assertThat(joinedBody.toString()).isEqualTo(expected).doesNotContain(SECRET, "U3ludGhldGljT25seQ");
        assertThat(joinedText.toString()).isEqualTo(expected);
        assertThat(filtered.getMessage("local", key, Integer.MAX_VALUE, 3).body()).isEmpty();
        assertThat(filtered.getAttachment("local", key, attachment.file(), Integer.MAX_VALUE, 3).text()).isEmpty();
        assertThat(raw.getMessage("local", key, null, null).body()).isEqualTo(BODY);
        assertThat(raw.getAttachment("local", key, attachment.file(), null, null).text()).isEqualTo(BODY);
        assertUnchanged(before);
    }

    @Test void searchFiltersBeforeHighlightingAndKeepsRawMatches() throws Exception {
        Map<Path, byte[]> before = snapshot();
        var rawSearch = new SearchTools(registry, mapper);
        var filteredSearch = new SearchTools(registry, mapper, redactor);
        for (String query : List.of(SECRET, "Reporting", "U3ludGhldGljT25seQ", "password: " + SECRET)) {
            var original = rawSearch.search("local", query, "body", "f1", null, null);
            var result = filteredSearch.search("local", query, "body", "f1", null, null);
            assertThat(result.total()).isEqualTo(1).isEqualTo(original.total());
            var hit = result.hits().getFirst();
            assertThat(hit.score()).isEqualTo(original.hits().getFirst().score());
            assertThat(hit.key()).isEqualTo(key);
            assertThat(hit.localPath()).isEqualTo(original.hits().getFirst().localPath());
            assertThat(hit.snippet()).doesNotContain(SECRET, "U3ludGhldGljT25seQ");
            assertThat(hit.subject()).doesNotContain(SECRET);
            assertThat(hit.from()).doesNotContain(SECRET);
            if (query.equals("Reporting")) { assertThat(hit.snippet()).contains("**Reporting**"); }
            if (query.startsWith("password:")) { assertThat(result.query()).doesNotContain(SECRET); }
        }
        assertUnchanged(before);
    }

    @Test void metadataAndDiscoveryAreFiltered() {
        var message = filtered.getMessage("local", key, null, null);
        assertThat(message.subject()).contains("[redacted:password]").doesNotContain(SECRET);
        assertThat(message.folder()).doesNotContain(SECRET);
        assertThat(message.error()).doesNotContain(SECRET);
        assertThat(String.join(" ", message.from())).doesNotContain(SECRET).contains("sender@example.com");
        assertThat(String.join(" ", message.to())).doesNotContain(SECRET).contains("recipient@example.com");
        assertThat(filtered.listFolders("local").folders().getFirst().path()).doesNotContain(SECRET);
        var card = filtered.listMessages("local", "f1", null, null).messages().getFirst();
        assertThat(card.subject()).doesNotContain(SECRET);
        assertThat(card.localPath()).isEqualTo(raw.listMessages("local", "f1", null, null).messages().getFirst().localPath());
        var discovery = new ConnectionTools(registry, redactor).listConnections().connections().getFirst();
        assertThat(discovery.name()).isEqualTo("local");
        assertThat(discovery.description()).doesNotContain(SECRET);
        assertThat(discovery.configError()).doesNotContain(SECRET);
        var status = new SyncTools(registry, sync, mapper, redactor).syncStatus("local");
        assertThat(status.error()).doesNotContain(SECRET);
        assertThat(status.folders().getFirst().path()).doesNotContain(SECRET);
        assertThat(new ConnectionTools(registry).listConnections().connections().getFirst().description()).contains(SECRET);
        assertThat(new SyncTools(registry, sync, mapper).syncStatus("local").error()).contains(SECRET);
    }

    @Test void attachmentSnippetsFilterBeforeHighlightingAndKeepIdentity() throws Exception {
        var directory = store.messageDir(key);
        var message = MimeReader.read(directory.resolve(MessageStore.MESSAGE_FILE), null).message();
        String file = message.attachments().getFirst().file();
        String farSecret = "FictionOnlyFarAttachmentSecret";
        String text = "Reporting results.\nPassword: \"" + SECRET + "\"\nSafe nearby sentence.\n"
                + "Password: \"" + "Filler sentence. ".repeat(1200) + farSecret + "\"\nFinal safe context.";
        Files.writeString(directory.resolve("attachments").resolve(file + ".md"), text);
        String body = "Unrelated **note** in the body.";
        Files.writeString(directory.resolve(MessageStore.BODY_FILE), body);
        try (var index = MailIndex.openWriter(dir)) {
            index.put(store.readMeta(key).orElseThrow(), message, body, text);
            index.commit();
        }
        var before = snapshot();
        var rawSearch = new SearchTools(registry, mapper);
        var filteredSearch = new SearchTools(registry, mapper, redactor);
        for (var field : List.of("any", "attachmentText")) {
            for (String query : List.of(SECRET, "Reporting", farSecret)) {
                var original = rawSearch.search("local", query, field, "f1", null, null);
                var result = filteredSearch.search("local", query, field, "f1", null, null);
                assertThat(result.total()).isEqualTo(1).isEqualTo(original.total());
                var rawHit = original.hits().getFirst();
                var hit = result.hits().getFirst();
                assertThat(hit.key()).isEqualTo(key).isEqualTo(rawHit.key());
                assertThat(hit.score()).isEqualTo(rawHit.score());
                assertThat(hit.localPath()).isEqualTo(rawHit.localPath());
                assertThat(hit.snippetAttachmentFile()).isEqualTo(file).isEqualTo(rawHit.snippetAttachmentFile());
                assertThat(file).contains(SECRET);
                assertThat(rawHit.snippetAttachmentName()).contains(SECRET);
                assertThat(hit.snippetAttachmentName()).isEqualTo(redactor.text(rawHit.snippetAttachmentName()))
                        .doesNotContain(SECRET);
                assertThat(hit.snippet()).doesNotContain(SECRET, farSecret, "Filler");
                if (query.equals("Reporting")) {
                    assertThat(hit.snippet()).contains("**Reporting**");
                } else {
                    assertThat(rawHit.snippet()).contains("**" + query + "**");
                    assertThat(hit.snippet()).contains("Reporting results.");
                }
            }
        }
        assertUnchanged(before);
    }

    private Map<Path, byte[]> snapshot() throws Exception {
        Map<Path, byte[]> result = new LinkedHashMap<>();
        try (var files = Files.walk(dir)) {
            for (var file : files.filter(Files::isRegularFile).toList()) { result.put(file, Files.readAllBytes(file)); }
        }
        return result;
    }

    private void assertUnchanged(Map<Path, byte[]> before) throws Exception {
        var after = snapshot();
        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((path, bytes) -> assertThat(after.get(path)).as(path.getFileName().toString()).isEqualTo(bytes));
    }
}
