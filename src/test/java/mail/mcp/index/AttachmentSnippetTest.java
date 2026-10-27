package mail.mcp.index;

import mail.mcp.config.JsonConfig;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.model.SearchHit;
import mail.mcp.store.MessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class AttachmentSnippetTest {
    @TempDir Path dir;
    private MessageStore store;

    @BeforeEach void prepare() {
        store = new MessageStore(dir, new JsonConfig().mailMcpObjectMapper());
    }

    @Test void attachmentOnlyMatchUsesSavedMarkdown() throws Exception {
        for (String body : new String[] { "Unrelated body context.", null }) {
            String key = put(body, "f1", new Part("first.txt", "Unrelated attachment."),
                    new Part("report.pdf", "The needle appears in this saved Markdown."));
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.current()).isTrue();
                assertThat(MailIndex.SCHEMA).isEqualTo("4");
                for (var field : List.of(SearchField.ANY, SearchField.ATTACHMENT_TEXT)) {
                    var hit = reader.search("needle", field, null, 0, 20, null, store).hits().stream()
                            .filter(h -> h.card().key().equals(key)).findFirst().orElseThrow();
                    assertAttachment(hit, "2__report.pdf", "report.pdf", "**needle**");
                }
                var legacy = reader.search("needle", SearchField.ATTACHMENT_TEXT, null, 0, 20).hits().stream()
                        .filter(h -> h.card().key().equals(key)).findFirst().orElseThrow();
                assertThat(legacy.snippetAttachmentFile()).isNull();
                assertThat(legacy.snippet()).isEqualTo(body);
                assertThat(reader.search("needle", SearchField.ATTACHMENT_TEXT, null, 0, 20,
                        String::toUpperCase).hits()).allSatisfy(h -> assertThat(h.snippetAttachmentFile()).isNull());
            }
        }
    }

    @Test void bodyMatchWinsOnlyWhenItsFieldIsEligible() throws Exception {
        put("Body needle passage.", "f1", new Part("a.txt", "Attachment needle passage."));
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            for (var field : List.of(SearchField.ANY, SearchField.BODY)) {
                var hit = reader.search("needle", field, null, 0, 20, null, store).hits().getFirst();
                assertThat(hit.snippet()).contains("Body **needle**");
                assertThat(hit.snippetAttachmentFile()).isNull();
                assertThat(hit.snippetAttachmentName()).isNull();
            }
            assertAttachment(reader.search("needle", SearchField.ATTACHMENT_TEXT, null, 0, 20, null, store)
                    .hits().getFirst(), "1__a.txt", "a.txt", "Attachment **needle**");
        }
        String literal = put("Unrelated **note** in the body.", "f1", new Part("b.txt", "Attachment needle."));
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            var hit = reader.search("needle", SearchField.ANY, null, 0, 20, null, store).hits().stream()
                    .filter(h -> h.card().key().equals(literal)).findFirst().orElseThrow();
            assertAttachment(hit, "1__b.txt", "b.txt", "**needle**");
        }
    }

    @Test void firstMatchingAttachmentUsesMimeOrderAndExactFile() throws Exception {
        List<Part> parts = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            parts.add(new Part("duplicate.txt", i == 2 || i == 10 ? "needle" : "Unrelated."));
        }
        parts.add(new Part(null, "nameless"));
        parts.add(new Part("bad/name?.txt", "unsafe"));
        parts.add(new Part("notes.md", "markdown"));
        parts.add(new Part("", "blankname"));
        String key = put("Body context.", "f1", parts.toArray(Part[]::new));
        // An arbitrary sidecar is not a MIME attachment candidate.
        Files.writeString(store.messageDir(key).resolve("attachments/0__fake.md"), "needle");
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            assertAttachment(first(reader, "needle"), "2__duplicate.txt", "duplicate.txt", "**needle**");
            assertAttachment(first(reader, "nameless"), "11__part", "11__part", "**nameless**");
            assertAttachment(first(reader, "unsafe"), "12__bad_name_.txt", "bad/name?.txt", "**unsafe**");
            assertAttachment(first(reader, "markdown"), "13__notes.md", "notes.md", "**markdown**");
            assertAttachment(first(reader, "blankname"), "14__", "14__", "**blankname**");
        }
        assertThat(store.messageDir(key).resolve("attachments/13__notes.md.md")).isRegularFile();
        assertThat(store.messageDir(key).resolve("attachments/13__notes.md")).doesNotExist();
    }

    @Test void attachmentHighlightUsesMorphologyAndPhrasePositions() throws Exception {
        put("Unrelated body.", "f1", new Part("early.txt", "Beta alpha. Договоры поставки. Reporting results."),
                new Part("later.txt", "Alpha beta is the phrase."));
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            assertAttachment(first(reader, "договорами"), "1__early.txt", "early.txt", "**Договоры**");
            assertAttachment(first(reader, "reports"), "1__early.txt", "early.txt", "**Reporting**");
            assertAttachment(first(reader, "\"alpha beta\""), "2__later.txt", "later.txt", "**Alpha** **beta**");
        }
    }

    @Test void splitQueryTermsDoNotRequireOneAttachmentToMatchEverything() throws Exception {
        put("Body context.", "f1", new Part("a.txt", "alpha"), new Part("b.txt", "beta"));
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            for (var field : List.of(SearchField.ANY, SearchField.ATTACHMENT_TEXT)) {
                var split = reader.search("alpha beta", field, null, 0, 20, null, store);
                assertThat(split.total()).isEqualTo(1);
                assertAttachment(split.hits().getFirst(), "1__a.txt", "a.txt", "**alpha**");
                var joinedPhrase = reader.search("\"alpha beta\"", field, null, 0, 20, null, store);
                assertThat(joinedPhrase.total()).isEqualTo(1);
                assertThat(joinedPhrase.hits().getFirst().snippet()).isEqualTo("Body context.");
                assertThat(joinedPhrase.hits().getFirst().snippetAttachmentFile()).isNull();
                assertThat(joinedPhrase.hits().getFirst().snippetAttachmentName()).isNull();
            }
        }
        String key = put("alpha in the body.", "f2", new Part("c.txt", "beta"));
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            var hit = reader.search("alpha beta", SearchField.ANY, "f2", 0, 20, null, store).hits().getFirst();
            assertThat(hit.card().key()).isEqualTo(key);
            assertThat(hit.snippet()).contains("**alpha**");
            assertThat(hit.snippetAttachmentFile()).isNull();
        }
    }

    @Test void missingOrBrokenLocalFilesKeepHitsAndBodyFallback() throws Exception {
        String missing = put("Body context.", "f1", new Part("a.txt", "needle"));
        Files.delete(store.messageDir(missing).resolve(MessageStore.MESSAGE_FILE));
        String broken = put(null, "f1", new Part("a.txt", "needle"));
        Files.writeString(store.messageDir(broken).resolve(MessageStore.MESSAGE_FILE),
                "Content-Type: multipart/mixed; boundary=absent\r\n\r\nNo MIME boundary");
        String noCandidate = put("Body fallback.", "f1", new Part("missing.txt", "needle"),
                new Part("blank.txt", "needle"), new Part("bad.txt", "needle"), new Part("unreadable.txt", "needle"));
        var attachments = store.messageDir(noCandidate).resolve("attachments");
        Files.delete(attachments.resolve("1__missing.txt.md"));
        Files.writeString(attachments.resolve("2__blank.txt.md"), " \n\t");
        Files.write(attachments.resolve("3__bad.txt.md"), new byte[] { (byte) 0xc3, 0x28 });
        Files.delete(attachments.resolve("4__unreadable.txt.md"));
        Files.createDirectory(attachments.resolve("4__unreadable.txt.md"));
        String later = put("Body fallback.", "f1", new Part("missing.txt", "needle"),
                new Part("blank.txt", "needle"), new Part("bad.txt", "needle"), new Part("usable.txt", "needle"));
        var laterDir = store.messageDir(later).resolve("attachments");
        Files.delete(laterDir.resolve("1__missing.txt.md"));
        Files.writeString(laterDir.resolve("2__blank.txt.md"), " \n");
        Files.write(laterDir.resolve("3__bad.txt.md"), new byte[] { (byte) 0xff });
        assertThat(laterDir.resolve("4__usable.txt")).doesNotExist();
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            var baseline = reader.search("needle", SearchField.ATTACHMENT_TEXT, null, 0, 20);
            var result = reader.search("needle", SearchField.ATTACHMENT_TEXT, null, 0, 20, null, store);
            assertThat(result.total()).isEqualTo(4).isEqualTo(baseline.total());
            assertThat(result.hits()).extracting(SearchHit::card).isEqualTo(baseline.hits().stream().map(SearchHit::card).toList());
            assertThat(result.hits()).extracting(SearchHit::score).isEqualTo(baseline.hits().stream().map(SearchHit::score).toList());
            for (var hit : result.hits()) {
                if (hit.card().key().equals(later)) {
                    assertAttachment(hit, "4__usable.txt", "usable.txt", "**needle**");
                } else {
                    assertThat(hit.snippet()).isEqualTo(hit.card().key().equals(broken) ? null
                            : hit.card().key().equals(missing) ? "Body context." : "Body fallback.");
                    assertThat(hit.snippetAttachmentFile()).isNull();
                    assertThat(hit.snippetAttachmentName()).isNull();
                }
            }
            var empty = reader.search("needle", SearchField.ATTACHMENT_TEXT, null, 0, 20, text -> " \n", store);
            assertThat(empty.hits()).allSatisfy(hit -> {
                assertThat(hit.snippet()).isNull();
                assertThat(hit.snippetAttachmentFile()).isNull();
                assertThat(hit.snippetAttachmentName()).isNull();
            });
        }
    }

    @Test void attachmentLoadingIsLimitedToEligiblePageHits() throws Exception {
        for (int i = 0; i < 3; i++) { put("Body context.", "f1", new Part("needle.txt", "needle")); }
        put("Body context.", "f10", new Part("needle.txt", "needle"));
        put("Body needle.", "f2", new Part("needle.txt", "needle"));
        var observed = spy(store);
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            var all = reader.search("needle", SearchField.ATTACHMENT_TEXT, "f1", 0, 20);
            var page = reader.search("needle", SearchField.ATTACHMENT_TEXT, "f1", 1, 1, null, observed);
            assertThat(page.total()).isEqualTo(3);
            assertThat(page.hits()).extracting(SearchHit::card).containsExactly(all.hits().get(1).card());
            assertThat(page.hits().getFirst().score()).isEqualTo(all.hits().get(1).score());
            verify(observed).messageDir(page.hits().getFirst().card().key());
            verifyNoMoreInteractions(observed);
            clearInvocations(observed);
            reader.search("needle", SearchField.ANY, "f2", 0, 20, null, observed);
            for (var field : List.of(SearchField.BODY, SearchField.SUBJECT, SearchField.FROM,
                    SearchField.TO, SearchField.ATTACHMENT_NAME)) {
                assertThat(reader.search("needle", field, null, 0, 20, null, observed).hits()).isNotEmpty();
            }
            reader.search("needle", SearchField.ANY, null, Integer.MAX_VALUE, 20, null, observed);
            verify(observed, never()).messageDir(org.mockito.ArgumentMatchers.anyString());
        }
        // Stored attachment names are not the authoritative MIME list.
        String key = put(null, "f3", new Part("hidden.txt", "needle"));
        try (var writer = MailIndex.openWriter(dir)) {
            writer.put(meta(key, "f3"), null, null, "needle");
            writer.commit();
        }
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            var hit = reader.search("needle", SearchField.ANY, "f3", 0, 20, null, store).hits().getFirst();
            assertThat(hit.card().hasAttachments()).isFalse();
            assertAttachment(hit, "1__hidden.txt", "hidden.txt", "**needle**");
        }
    }

    @Test void lateAttachmentMatchIsHighlighted() throws Exception {
        put("Body context.", "f1", new Part("late.txt", "Unrelated sentence. ".repeat(800)
                + "The needle is far beyond the default prefix."));
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            assertAttachment(first(reader, "needle"), "1__late.txt", "late.txt", "**needle**");
        }
    }

    @Test void snippetComesFromArchiveEntry() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("folder/report.txt"));
            zip.write("The archive needle is searchable.".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String key = store.newKey();
        var directory = store.createMessageDir(key);
        Files.writeString(directory.resolve(MessageStore.MESSAGE_FILE),
                "Content-Type: application/zip\r\nContent-Disposition: attachment; filename=archive.zip\r\n"
                        + "Content-Transfer-Encoding: base64\r\n\r\n"
                        + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
        var result = new mail.mcp.sync.MessageProcessor(store, new mail.mcp.convert.Pandoc("unused"))
                .process(meta(key, "f1"));
        try (var writer = MailIndex.openWriter(dir)) {
            writer.put(result.meta(), result.message(), result.body(), result.attachmentText());
            writer.commit();
        }
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            for (var field : List.of(SearchField.ANY, SearchField.ATTACHMENT_TEXT)) {
                var page = reader.search("needle", field, null, 0, 20, null, store);
                assertThat(page.total()).isEqualTo(1);
                assertAttachment(page.hits().getFirst(), "2__report.txt", "folder/report.txt", "**needle**");
            }
            assertThat(reader.search("report", SearchField.ATTACHMENT_NAME, null, 0, 20).total()).isEqualTo(1);
        }
    }

    @Test void snippetComesFromOutlookEntry() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(mail.mcp.convert.PandocTest.pandocAvailable());
        for (String id : new String[]{"M09", "M10"}) {
            String key = store.newKey();
            var directory = store.createMessageDir(key);
            Files.copy(mail.mcp.OutlookFixtures.fixture(id), directory.resolve(MessageStore.MESSAGE_FILE));
            var processed = new mail.mcp.sync.MessageProcessor(store, new mail.mcp.convert.Pandoc("pandoc"))
                    .process(meta(key, "f1"));
            try (var writer = MailIndex.openWriter(dir)) {
                writer.put(processed.meta(), processed.message(), processed.body(), processed.attachmentText());
                writer.commit();
            }
            var child = processed.message().attachments().getLast();
            // A child-only word must attribute the passage to the document, not its container.
            String query = id.equals("M09") ? "архива" : "вложение";
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var hit = reader.search(query, SearchField.ATTACHMENT_TEXT, null, 0, 20, null, store).hits().stream()
                        .filter(h -> h.card().key().equals(key)).findFirst().orElseThrow();
                assertAttachment(hit, child.file(), child.name(), "**");
                assertThat(hit.snippet()).containsIgnoringCase(query);
                assertThat(child.parent()).isNotNull();
                assertThat(MailIndex.SCHEMA).isEqualTo("4");
            }
        }
    }

    @Test void snippetComesFromLegacyOfficeInsideContainers() throws Exception {
        byte[] xls = mail.mcp.LegacyOfficeFixtures.xls("Уникальный cobaltneedle в ячейке");
        byte[] archive = mail.mcp.LegacyOfficeFixtures.zip("folder/ledger.xls", xls);
        byte[] msg = mail.mcp.OutlookFixtures.msg("Container subject", "Unrelated container body",
                new mail.mcp.OutlookFixtures.Child("archive.zip", archive));
        String key = store.newKey();
        var directory = store.createMessageDir(key);
        var eml = directory.resolve(MessageStore.MESSAGE_FILE);
        Files.write(eml, mail.mcp.OutlookFixtures.eml("message.msg", "application/vnd.ms-outlook", msg));
        var processed = new mail.mcp.sync.MessageProcessor(store, new mail.mcp.convert.Pandoc("unused"))
                .process(meta(key, "f1"));
        assertThat(processed.meta().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(processed.message().attachments()).extracting(a -> a.file())
                .containsExactly("1__message.msg", "2__archive.zip", "3__ledger.xls");
        assertThat(processed.message().attachments()).extracting(a -> a.parent())
                .containsExactly(null, "1__message.msg", "2__archive.zip");
        assertThat(directory.resolve("attachments/3__ledger.xls")).hasBinaryContent(xls);
        assertThat(directory.resolve("attachments/1__message.msg")).hasBinaryContent(msg);
        try (var writer = MailIndex.openWriter(dir)) {
            writer.put(processed.meta(), processed.message(), processed.body(), processed.attachmentText());
            writer.commit();
        }
        try (var reader = MailIndexReader.open(dir).orElseThrow()) {
            for (var field : List.of(SearchField.ANY, SearchField.ATTACHMENT_TEXT)) {
                var page = reader.search("cobaltneedle", field, null, 0, 20, null, store);
                assertThat(page.total()).isEqualTo(1);
                assertAttachment(page.hits().getFirst(), "3__ledger.xls", "folder/ledger.xls", "**cobaltneedle**");
            }
            assertThat(MailIndex.SCHEMA).isEqualTo("4");
        }
    }

    private SearchHit first(MailIndexReader reader, String query) {
        return reader.search(query, SearchField.ATTACHMENT_TEXT, null, 0, 20, null, store).hits().getFirst();
    }

    private static void assertAttachment(SearchHit hit, String file, String name, String text) {
        assertThat(hit.snippet()).contains(text);
        assertThat(hit.snippetAttachmentFile()).isEqualTo(file);
        assertThat(hit.snippetAttachmentName()).isEqualTo(name);
    }

    private String put(String body, String folder, Part... parts) throws Exception {
        String key = store.newKey();
        Path directory = store.createMessageDir(key);
        var eml = new StringBuilder("Subject: Needle\r\nFrom: Needle <needle@example.com>\r\n"
                + "To: Needle <recipient@example.com>\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=synthetic\r\n\r\n");
        if (body != null) {
            eml.append("--synthetic\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n").append(body).append("\r\n");
        }
        for (var part : parts) {
            eml.append("--synthetic\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment");
            if (part.name() != null) { eml.append("; filename=\"").append(part.name()).append('"'); }
            eml.append("\r\n\r\nSynthetic binary\r\n");
        }
        eml.append("--synthetic--\r\n");
        var mime = directory.resolve(MessageStore.MESSAGE_FILE);
        Files.writeString(mime, eml, StandardCharsets.UTF_8);
        var message = MimeReader.read(mime, null).message();
        var attachments = Files.createDirectory(directory.resolve(MessageStore.ATTACHMENTS_DIR));
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            Files.writeString(attachments.resolve(message.attachments().get(i).file() + ".md"), parts[i].text(), StandardCharsets.UTF_8);
            texts.add(parts[i].text());
        }
        if (body != null) { Files.writeString(directory.resolve(MessageStore.BODY_FILE), body); }
        try (var writer = MailIndex.openWriter(dir)) {
            writer.put(meta(key, folder), message, body, String.join("\n\n", texts));
            writer.commit();
        }
        return key;
    }

    private static MessageMeta meta(String key, String folder) {
        return new MessageMeta(key, folder, "INBOX", key, 0, null, Instant.parse("2026-10-26T00:00:00Z"),
                MessageState.CONVERTED, null);
    }

    private record Part(String name, String text) { }
}
