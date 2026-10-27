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
                Date: Mon, 26 Oct 2026 00:00:00 +0000
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

    @Test void dateFiltersPreserveMatchingAndFilteredTextSnippets() {
        var search = new SearchTools(registry, mapper, redactor);
        var result = search.search("local", "Reporting", "body", "f1", null, null, "2026-10-26", "2026-10-27");
        assertThat(result.total()).isEqualTo(1); assertThat(result.hits().getFirst().snippet()).contains("**Reporting**").doesNotContain(SECRET);
        assertThat(search.search("local", "Reporting", "body", "f1", null, null, "2026-10-27", null).total()).isZero();
    }

    @Test void threadRedactsDisplayFieldsAndPreservesLookupValues() throws Exception {
        String token = "ghp_" + "A".repeat(36);
        Path connectionDir = dir.resolve(token);
        var threadRegistry = new ConnectionRegistry(List.of(new ConnectionDefinition("local", null,
                ConnectionType.IMAP, AuthKind.APP_PASSWORD, null, null, connectionDir, "unconfigured")),
                definition -> { throw new AssertionError("no mailbox access"); });
        var threadStore = new MessageStore(connectionDir, mapper);
        String copyKey = threadStore.newKey();
        try (var index = MailIndex.openWriter(connectionDir)) {
            for (String member : List.of(key, copyKey)) {
                var meta = new MessageMeta(member, token, "INBOX", member, 0, null, Instant.now(), MessageState.CONVERTED, null);
                var message = new mail.mcp.model.MailMessage("Password: " + SECRET,
                        List.of(new mail.mcp.model.Address("Password: " + SECRET, "sender@example.com")), null, null,
                        Instant.parse("2026-10-26T00:00:00Z"), "<" + token + ">", null, null, null);
                index.put(meta, message, BODY, BODY);
            }
            index.commit();
        }
        var before = snapshot();
        var original = new ThreadTools(threadRegistry, mapper).getThread("local", key, null, null);
        var result = new ThreadTools(threadRegistry, mapper, redactor).getThread("local", key, null, null);
        assertThat(result.key()).isEqualTo(key);
        assertThat(result.total()).isEqualTo(1).isEqualTo(original.total());
        assertThat(result.offset()).isEqualTo(original.offset());
        assertThat(result.nextOffset()).isEqualTo(original.nextOffset());
        for (int i = 0; i < result.messages().size(); i++) {
            var card = result.messages().get(i);
            var rawCard = original.messages().get(i);
            assertThat(card.key()).isEqualTo(rawCard.key());
            assertThat(card.folder()).isEqualTo(token).isEqualTo(rawCard.folder());
            assertThat(card.localPath()).isEqualTo(rawCard.localPath()).contains(token);
            assertThat(card.date()).isEqualTo(rawCard.date());
            assertThat(card.state()).isEqualTo(rawCard.state());
            assertThat(card.hasAttachments()).isEqualTo(rawCard.hasAttachments());
            assertThat(card.subject()).isEqualTo(redactor.text(rawCard.subject())).contains("[redacted:password]").doesNotContain(SECRET);
            assertThat(card.from()).isEqualTo(redactor.text(rawCard.from())).contains("[redacted:password]").doesNotContain(SECRET);
        }
        assertThat(mapper.writeValueAsString(result)).doesNotContain(SECRET, "messageId", "inReplyTo", "references", "body", "<" + token + ">");
        assertUnchanged(before);
    }

    @Test void copiesKeepKeysAndFolderKeysUnfiltered() throws Exception {
        String token = "ghp_" + "B".repeat(36);
        String representative = "00000000-0000-0000-0000-000000000001";
        // The reader preserves lookup keys verbatim, even for a synthetic detector-shaped key.
        var message = new mail.mcp.model.MailMessage("Password: " + SECRET,
                List.of(new mail.mcp.model.Address("Password: " + SECRET, "sender@example.com")), null, null,
                Instant.parse("2026-10-26T00:00:00Z"), "<copy>", null, null, null);
        try (var writer = MailIndex.openWriter(dir)) {
            for (String member : List.of(representative, token)) {
                writer.put(new MessageMeta(member, token, "INBOX", member, 0, null, Instant.now(), MessageState.CONVERTED, null),
                        message, "Needle", null);
            }
            writer.commit();
        }
        var before = snapshot();
        var search = new SearchTools(registry, mapper, redactor).search("local", "Needle", "body", null, null, null);
        var thread = new ThreadTools(registry, mapper, redactor).getThread("local", representative, null, null);
        var expected = new mail.mcp.api.MessageCopyInfo(token, token);
        assertThat(search.hits().getFirst().copies()).containsExactly(expected);
        assertThat(thread.messages().getFirst().copies()).containsExactly(expected);
        assertThat(search.hits().getFirst().subject()).doesNotContain(SECRET);
        assertThat(thread.messages().getFirst().from()).doesNotContain(SECRET);
        assertThat(mapper.readTree(mapper.writeValueAsString(search)).at("/hits/0/copies/0/key").asString()).isEqualTo(token);
        assertThat(mapper.readTree(mapper.writeValueAsString(thread)).at("/messages/0/copies/0/folder").asString()).isEqualTo(token);
        assertUnchanged(before);
    }

}
