package mail.mcp.tools;

import mail.mcp.config.JsonConfig;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionType;
import mail.mcp.index.MailIndex;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.Address;
import mail.mcp.model.AttachmentInfo;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.MailMessage;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.SyncService;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchToolsTest {
    @TempDir Path dir;
    private MessageStore store;
    private SearchTools tools;
    private ConnectionRegistry registry;

    @BeforeEach void prepare() {
        var mapper = new JsonConfig().mailMcpObjectMapper();
        store = new MessageStore(dir, mapper);
        var definition = new ConnectionDefinition("local", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD,
                null, null, dir, null);
        registry = new ConnectionRegistry(List.of(definition), d -> { throw new AssertionError("no source"); });
        tools = new SearchTools(registry, mapper);
        store.saveFolders(List.of(new LocalFolder("f1", "INBOX", "INBOX", "7"),
                new LocalFolder("f2", "Archive", "Archive", null)));
    }

    @Test void attachmentSnippetIdentifiesAReadableAttachment() throws Exception {
        String key = store.newKey();
        var directory = store.createMessageDir(key);
        var eml = directory.resolve(MessageStore.MESSAGE_FILE);
        Files.writeString(eml, """
                Subject: Synthetic attachment
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary=synthetic

                --synthetic
                Content-Type: text/plain

                Unrelated body.
                --synthetic
                Content-Type: application/pdf
                Content-Disposition: attachment; filename="report.pdf"

                Synthetic binary
                --synthetic--
                """.replace("\n", "\r\n"));
        var message = MimeReader.read(eml, directory.resolve("attachments")).message();
        String file = message.attachments().getFirst().file();
        String text = "A needle in the saved Markdown.";
        Files.writeString(directory.resolve("attachments").resolve(file + ".md"), text);
        var meta = new MessageMeta(key, "f1", "INBOX", key, 0, null, Instant.now(), MessageState.CONVERTED, null);
        store.writeMeta(meta);
        var mapper = new JsonConfig().mailMcpObjectMapper();
        try (var index = MailIndex.openWriter(dir)) {
            index.put(meta, message, "Unrelated body.", text);
            put(index, "f1");
            index.commit();
        }
        var hit = tools.search("local", "needle", "attachmentText", null, null, null).hits().getFirst();
        assertThat(hit.snippet()).contains("**needle**");
        assertThat(hit.snippetAttachmentFile()).isEqualTo(file);
        assertThat(hit.snippetAttachmentName()).isEqualTo("report.pdf");
        var mail = new MailTools(registry, new SyncService(registry,
                () -> { throw new AssertionError("no pandoc"); }, mapper), mapper);
        var attachment = mail.getAttachment("local", hit.key(), hit.snippetAttachmentFile(), null, null);
        assertThat(attachment.text()).isEqualTo(text);
        assertThat(attachment.path()).isEqualTo(directory.resolve("attachments").resolve(file).toAbsolutePath().toString());
        assertThat(hit.localPath()).isEqualTo(mail.getMessage("local", key, null, null).localPath());
        var json = mapper.readTree(mapper.writeValueAsString(hit));
        assertThat(json.path("snippetAttachmentFile").asString()).isEqualTo(file);
        assertThat(json.path("snippetAttachmentName").asString()).isEqualTo("report.pdf");
        var body = tools.search("local", "reports", "body", null, null, null).hits().getFirst();
        var bodyJson = mapper.readTree(mapper.writeValueAsString(body));
        assertThat(bodyJson.has("snippetAttachmentFile")).isFalse();
        assertThat(bodyJson.has("snippetAttachmentName")).isFalse();
    }

    private String put(MailIndex index, String folder) {
        String key = store.newKey();
        store.createMessageDir(key);
        var meta = new MessageMeta(key, folder, "INBOX", key, 0, null, Instant.now(), MessageState.CONVERTED, null);
        store.writeMeta(meta);
        index.put(meta, new MailMessage("Договор поставки", List.of(new Address("Sender", "sender@example.com")),
                null, List.of(new Address("Copy", "copy@example.org")), Instant.parse("2026-10-26T00:00:00Z"),
                null, null, null, List.of(new AttachmentInfo("1__report.txt", "Report_2026.txt", "text/plain", 1, null, false, null, null))),
                "Согласуем договоры. Reporting results.", null);
        return key;
    }

    @Test void searchReturnsCardsWithLocalPath() {
        try (var index = MailIndex.openWriter(dir)) {
            String a = put(index, "f1");
            String b = put(index, "f1");
            put(index, "f2");
            index.commit();
            var first = tools.search("local", "договорами", null, "f1", null, 1);
            assertThat(first.connection()).isEqualTo("local");
            assertThat(first.query()).isEqualTo("договорами");
            assertThat(first.field()).isEqualTo("any");
            assertThat(first.folder()).isEqualTo("f1");
            assertThat(first.offset()).isZero();
            assertThat(first.total()).isEqualTo(2);
            assertThat(first.nextOffset()).isEqualTo(1);
            var hit = first.hits().getFirst();
            assertThat(hit.key()).isIn(a, b);
            assertThat(hit.folder()).isEqualTo("f1");
            assertThat(hit.date()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
            assertThat(hit.from()).isEqualTo("Sender <sender@example.com>");
            assertThat(hit.subject()).isEqualTo("Договор поставки");
            assertThat(hit.hasAttachments()).isTrue();
            assertThat(hit.state()).isEqualTo("converted");
            assertThat(hit.score()).isPositive().isFinite();
            assertThat(hit.snippet()).contains("**договоры**");
            assertThat(Path.of(hit.localPath())).isAbsolute().isDirectory().isEqualTo(store.messageDir(hit.key()));
            var next = tools.search("local", "договорами", null, "f1", first.nextOffset(), 1);
            assertThat(next.nextOffset()).isNull();
            assertThat(next.hits().getFirst().key()).isNotEqualTo(hit.key());
            assertThat(tools.search("local", "report", "body", null, null, null).total()).isEqualTo(3);
            assertThat(tools.search("local", "copy@example.org", "to", null, null, null).total()).isEqualTo(3);
            assertThat(tools.search("local", "2026", "attachmentName", null, null, null).total()).isEqualTo(3);
            assertThat(tools.search("local", "\"reporting договоры\"", "body", null, null, null).total()).isZero();
            assertThat(tools.search("local", "договоры absent", "body", null, null, null).total()).isZero();
        }
    }

    @Test void unknownFieldOrFolderIsRejected() {
        assertThatThrownBy(() -> tools.search("local", "word", "unknown", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("any, subject, body, from, to, attachmentName");
        assertThatThrownBy(() -> tools.search("local", "word", null, "missing", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown folder key; use listFolders");
        assertThatThrownBy(() -> tools.search("unknown", "word", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void blankOrTooLongQueryIsRejected() {
        for (String query : new String[] { null, "", " \n\t", "a".repeat(1_001) }) {
            assertThatThrownBy(() -> tools.search("local", query, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query must be nonblank");
        }
        assertThat(tools.search("local", "a".repeat(1_000), null, null, null, null).total()).isZero();
    }

    @Test void oldIndexAsksForReindex() throws Exception {
        try (var directory = FSDirectory.open(dir.resolve("index"));
             var writer = new IndexWriter(directory, new IndexWriterConfig())) { writer.commit(); }
        assertThatThrownBy(() -> tools.search("local", "word", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The index was built by an older version; " +
                        "run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
    }

    @Test void missingIndexReturnsEmptyPage() {
        var result = tools.search("local", "word", null, null, null, null);
        assertThat(result.total()).isZero();
        assertThat(result.hits()).isEmpty();
        assertThat(result.nextOffset()).isNull();
        assertThat(dir.resolve("index")).doesNotExist();
    }

    @Test void boundsAndPagingAreApplied() {
        assertThatThrownBy(() -> tools.search("local", "word", null, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.search("local", "word", null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        try (var index = MailIndex.openWriter(dir)) {
            for (int i = 0; i < 101; i++) { put(index, "f1"); }
            index.commit();
            assertThat(tools.search("local", "договор", null, null, null, null).hits()).hasSize(20);
            var capped = tools.search("local", "договор", null, null, null, 200);
            assertThat(capped.hits()).hasSize(100);
            assertThat(capped.nextOffset()).isEqualTo(100);
            var pastEnd = tools.search("local", "договор", null, null, Integer.MAX_VALUE, null);
            assertThat(pastEnd.total()).isEqualTo(101);
            assertThat(pastEnd.hits()).isEmpty();
            assertThat(pastEnd.nextOffset()).isNull();
        }
    }

    @Test void dateOnlyQueryRequiresAtLeastOneBound() {
        assertThatThrownBy(() -> tools.search("local", null, null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.search("local", " ".repeat(1001), null, null, null, null, "2026-10-26", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.search("local", null, "bad", null, null, null, "2026-10-26", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.search("local", null, null, "bad", null, null, "2026-10-26", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.search("local", null, null, null, null, null, "bad", null)).isInstanceOf(IllegalArgumentException.class);
        var empty = tools.search("local", " ", null, null, null, null, "2026-10-26", null);
        assertThat(empty.query()).isNull(); assertThat(empty.total()).isZero(); assertThat(empty.since()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        try (var index = MailIndex.openWriter(dir)) { put(index, "f1"); put(index, "f2"); index.commit(); assertThat(tools.search("local", null, null, null, null, null, "2026-10-26", "2026-10-27").total()).isEqualTo(2); }
    }
    @Test void stopWordsWithDatesRemainAnEmptyTextSearch() {
        try (var index = MailIndex.openWriter(dir)) { put(index, "f1"); index.commit(); assertThat(tools.search("local", "the and", "body", null, null, null, "2026-10-26", null).total()).isZero(); }
    }

}
