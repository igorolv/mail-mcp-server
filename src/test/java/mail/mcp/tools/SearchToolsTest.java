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

    @Test void changedWordFormsAskForReindexOnlyForTextSearch() throws Exception {
        var file = dir.resolve("word-forms.txt");
        var mapper = new JsonConfig().mailMcpObjectMapper();
        var search = new SearchTools(registry, mapper, new mail.mcp.redaction.SecretRedactor(false),
                () -> mail.mcp.index.WordForms.load(file));
        try (var index = MailIndex.openWriter(dir)) { put(index, "f1"); index.commit(); }
        assertThat(search.search("local", "договор", "body", null, null, null).total()).isEqualTo(1);
        Files.writeString(file, "письмо писем");
        assertThatThrownBy(() -> search.search("local", "договор", "body", null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The word forms list changed since the index was built; run " +
                        "'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
        assertThat(search.search("local", null, null, null, null, null, "2026-10-26", null).total()).isEqualTo(1);
        Files.writeString(file, "invalid");
        assertThat(search.search("local", null, null, null, null, null, "2026-10-26", null).total()).isEqualTo(1);
        assertThatThrownBy(() -> search.search("local", "договор", "body", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("word-forms.txt line 1:");
        var sync = new SyncService(registry, () -> { throw new AssertionError("no pandoc or sign-in"); }, mapper,
                () -> mail.mcp.index.WordForms.load(file));
        assertThatThrownBy(() -> sync.start("local")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("word-forms.txt line 1:");
        Files.delete(file);
        assertThat(search.search("local", "договор", "body", null, null, null).total()).isEqualTo(1);
        assertThat(registry.isInitialized("local")).isFalse();
    }

    @Test void wordFormsMatchAndHighlightBothDirections() throws Exception {
        var file = dir.resolve("word-forms.txt");
        Files.writeString(file, "письмо писем\nребёнок дети\nчеловек люди");
        var forms = mail.mcp.index.WordForms.load(file);
        var search = new SearchTools(registry, new JsonConfig().mailMcpObjectMapper(),
                new mail.mcp.redaction.SecretRedactor(false), () -> mail.mcp.index.WordForms.load(file));
        try (var writer = MailIndex.openWriter(dir, false, forms)) {
            for (String word : List.of("письмо", "писем", "РЕБЁНОК", "дети", "человек", "люди")) {
                String key = store.newKey();
                writer.put(new MessageMeta(key, "f1", "INBOX", key, 0, null, Instant.now(),
                        MessageState.CONVERTED, null), null, "Контекст " + word + " здесь.", null);
            }
            writer.commit();
        }
        for (String query : List.of("письмо", "писем")) {
            var hits = search.search("local", query, "body", null, null, null).hits();
            assertThat(hits).hasSize(2);
            assertThat(hits).extracting(hit -> hit.snippet()).containsExactlyInAnyOrder(
                    "Контекст **письмо** здесь.", "Контекст **писем** здесь.");
        }
        for (String query : List.of("ребенок", "дети")) {
            var hits = search.search("local", query, "body", null, null, null).hits();
            assertThat(hits).hasSize(2);
            assertThat(hits).extracting(hit -> hit.snippet()).containsExactlyInAnyOrder(
                    "Контекст **РЕБЁНОК** здесь.", "Контекст **дети** здесь.");
        }
        for (String query : List.of("человек", "люди")) {
            var hits = search.search("local", query, "body", null, null, null).hits();
            assertThat(hits).hasSize(2);
            assertThat(hits).extracting(hit -> hit.snippet()).containsExactlyInAnyOrder(
                    "Контекст **человек** здесь.", "Контекст **люди** здесь.");
        }
        Files.delete(file);
        assertThatThrownBy(() -> search.search("local", "письмо", "body", null, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("word forms list changed");
    }

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

    @Test void searchReturnsCopiesWithKeysAndFolders() {
        var mapper = new JsonConfig().mailMcpObjectMapper();
        var otherDir = dir.resolve("other");
        var copyRegistry = new ConnectionRegistry(List.of(
                new ConnectionDefinition("local", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD, null, null, dir, null),
                new ConnectionDefinition("other", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD, null, null, otherDir, null)),
                d -> { throw new AssertionError("no source"); });
        var search = new SearchTools(copyRegistry, mapper);
        var keys = java.util.stream.IntStream.range(1, 5).mapToObj(i -> new java.util.UUID(0, i).toString()).toList();
        var message = new MailMessage("Subject", List.of(new Address(null, "user@example.com")), null, null,
                Instant.parse("2026-10-26T00:00:00Z"), "<copy>", null, null, null);
        try (var writer = MailIndex.openWriter(dir); var other = MailIndex.openWriter(otherDir)) {
            for (int i = 0; i < 4; i++) {
                var meta = new MessageMeta(keys.get(i), i < 2 ? "f1" : "f2", "INBOX", keys.get(i), i, null,
                        Instant.now(), MessageState.CONVERTED, null, null, i == 0, i != 0);
                writer.put(meta, i == 0 ? new MailMessage("Subject", message.from(), null, null, message.date(),
                        "<first>", null, null, null) : message, "Needle", null);
            }
            other.put(new MessageMeta(keys.get(2), "f3", "INBOX", "remote", 0, null, Instant.now(), MessageState.CONVERTED, null),
                    message, "Needle", null);
            writer.commit();
            other.commit();
            var first = search.search("local", "Needle", "body", null, 0, 1);
            assertThat(first.total()).isEqualTo(2);
            assertThat(first.nextOffset()).isEqualTo(1);
            assertThat(mapper.readTree(mapper.writeValueAsString(first)).at("/hits/0").has("copies")).isFalse();
            var second = search.search("local", "Needle", "body", null, first.nextOffset(), 1);
            assertThat(second.nextOffset()).isNull();
            var hit = second.hits().getFirst();
            assertThat(hit.key()).isEqualTo(keys.get(1));
            assertThat(hit.copies()).containsExactly(new mail.mcp.api.MessageCopyInfo(keys.get(2), "f2"),
                    new mail.mcp.api.MessageCopyInfo(keys.get(3), "f2"));
            assertThat(hit.read()).isFalse();
            assertThat(hit.flagged()).isTrue();
            var json = mapper.readTree(mapper.writeValueAsString(hit));
            assertThat(json.at("/copies/0/key").asString()).isEqualTo(keys.get(2));
            assertThat(json.at("/copies/0/folder").asString()).isEqualTo("f2");
            assertThat(search.search("other", "Needle", "body", null, 0, 10).total()).isEqualTo(1);
            assertThat(search.search("other", "Needle", "body", null, 0, 10).hits().getFirst().copies()).isEmpty();
        }
        assertThat(copyRegistry.isInitialized("local")).isFalse();
        assertThat(copyRegistry.isInitialized("other")).isFalse();
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


    @Test void textAndDateSearchExposeStoredFlags() {
        for (Boolean value : new Boolean[] {true, false, null}) {
            String key = store.newKey();
            var meta = new MessageMeta(key, "f1", "INBOX", key, 0, null, Instant.now(),
                    MessageState.CONVERTED, null, null, value, value);
            var message = new MailMessage("Flags", null, null, null, Instant.parse("2026-10-25T12:00:00Z"),
                    null, null, null, null);
            try (var writer = MailIndex.openWriter(dir)) {
                writer.put(meta, message, "Needle", null);
                writer.commit();
                for (var result : List.of(tools.search("local", "Needle", "body", null, null, null),
                        tools.search("local", null, null, null, null, null, "2026-10-25", "2026-10-26"))) {
                    var hit = result.hits().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
                    assertThat(hit.read()).isEqualTo(value);
                    assertThat(hit.flagged()).isEqualTo(value);
                }
            }
        }
        assertThat(registry.isInitialized("local")).isFalse();
    }

}
