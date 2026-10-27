package mail.mcp.index;

import mail.mcp.model.Address;
import mail.mcp.model.AttachmentInfo;
import mail.mcp.model.MailMessage;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.model.MessageCopy;
import mail.mcp.model.DateRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailIndexReaderTest {
    @TempDir Path dir;

    private void putCopy(MailIndex writer, String key, String folder, Instant date,
                         String messageId, String from, String subject, String body) {
        writer.put(new MessageMeta(key, folder, "INBOX", key, 0, null, Instant.now(), MessageState.CONVERTED, null),
                new MailMessage(subject, from == null ? null : List.of(new Address(null, from)), null, null,
                        date, messageId, null, null, null), body, null);
    }

    @Test void searchGroupsCopiesBeforePagingWithExactTotal() {
        var date = Instant.parse("2026-10-26T00:00:00Z");
        try (var writer = MailIndex.openWriter(dir)) {
            // Insert out of key order; all scores tie. The copy group starts on page two.
            putCopy(writer, "d", "f3", date, "<copy>", "user@example.com", "Subject", "Needle");
            putCopy(writer, "c", "f2", date, " <copy> ", "user@example.com", "Subject", "Needle");
            putCopy(writer, "a", "f1", date, "<first>", "user@example.com", "Subject", "Needle");
            putCopy(writer, "b", "f1", date, "<copy>", "user@example.com", "Subject", "Needle");
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var first = reader.search("Needle", SearchField.BODY, null, 0, 1);
                assertThat(first.total()).isEqualTo(2);
                assertThat(first.hits()).extracting(h -> h.card().key()).containsExactly("a");
                assertThat(first.hits().getFirst().copies()).isEmpty();
                var second = reader.search("Needle", SearchField.BODY, null, 1, 1);
                assertThat(second.total()).isEqualTo(2);
                var hit = second.hits().getFirst();
                assertThat(hit.card().key()).isEqualTo("b");
                assertThat(hit.score()).isEqualTo(first.hits().getFirst().score());
                assertThat(hit.snippet()).isEqualTo("**Needle**");
                assertThat(hit.copies()).containsExactly(new MessageCopy("c", "f2"), new MessageCopy("d", "f3"));
                assertThat(reader.search("Needle", SearchField.BODY, null, 2, 1).hits()).isEmpty();
                assertThat(reader.search("Needle", SearchField.BODY, null, Integer.MAX_VALUE, 1).total()).isEqualTo(2);
                assertThat(reader.count("f1")).isEqualTo(2);
                assertThat(reader.cards("f1", 0, 10)).hasSize(2);
            }
        }
    }

    @Test void searchKeepsDistinctMessagesThatShareAMessageId() {
        var date = Instant.parse("2026-10-26T00:00:00Z");
        try (var writer = MailIndex.openWriter(dir)) {
            putCopy(writer, "base", "f1", date, "<Same>", "user@example.com", "Subject", "Needle");
            putCopy(writer, "date", "f2", date.plusSeconds(1), "<Same>", "user@example.com", "Subject", "Needle");
            putCopy(writer, "sender", "f2", date, "<Same>", "other@example.com", "Subject", "Needle");
            putCopy(writer, "subject", "f2", date, "<Same>", "user@example.com", "Different", "Needle");
            putCopy(writer, "case", "f2", date, "<same>", "user@example.com", "Subject", "Needle");
            int number = 0;
            for (String id : new String[] {null, "", "Same", "<>", "<bad id>", "<<Same>>", "<Same> <other>", "junk <Same>", "<bad\u2003id>"}) {
                for (int copy = 0; copy < 2; copy++) {
                    putCopy(writer, "invalid" + number++, "f1", date, id, "user@example.com", "Subject", "Needle");
                }
            }
            for (int copy = 0; copy < 2; copy++) {
                putCopy(writer, "undated" + copy, "f1", null, "<Same>", "user@example.com", "Subject", "Needle");
                putCopy(writer, "noSender" + copy, "f1", date, "<Same>", null, "Subject", "Needle");
                putCopy(writer, "blankSender" + copy, "f1", date, "<Same>", " ", "Subject", "Needle");
                putCopy(writer, "noSubject" + copy, "f1", date, "<Same>", "user@example.com", null, "Needle");
                putCopy(writer, "blankSubject" + copy, "f1", date, "<Same>", "user@example.com", " ", "Needle");
                writer.put(new MessageMeta("failed" + copy, "f1", "INBOX", "failed" + copy, 0, null,
                        Instant.now(), MessageState.FAILED, "Unavailable"), null, "Needle", null);
            }
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var result = reader.search("Needle", SearchField.BODY, null, 0, 100);
                assertThat(result.total()).isEqualTo(35);
                assertThat(result.hits()).hasSize(35).allSatisfy(h -> assertThat(h.copies()).isEmpty());
                assertThat(reader.thread("base", 0, 100).total()).isEqualTo(14);
            }
        }
    }

    @Test void searchRepresentativeKeepsTheHighestScoringCopyAndItsSnippet() {
        var date = Instant.parse("2026-10-26T00:00:00Z");
        try (var writer = MailIndex.openWriter(dir)) {
            putCopy(writer, "a", "f1", date, "<copy>", "user@example.com", "Subject",
                    "Needle " + "Unrelated context. ".repeat(100));
            putCopy(writer, "z", "f2", date, "<copy>", "user@example.com", "Subject", "Needle ".repeat(10));
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var expected = reader.search("Needle", SearchField.BODY, "f2", 0, 10).hits().getFirst();
                var lower = reader.search("Needle", SearchField.BODY, "f1", 0, 10).hits().getFirst();
                var result = reader.search("Needle", SearchField.BODY, null, 0, 10);
                var hit = result.hits().getFirst();
                assertThat(result.total()).isEqualTo(1);
                assertThat(hit.card()).isEqualTo(expected.card());
                assertThat(hit.score()).isEqualTo(expected.score()).isGreaterThan(lower.score());
                assertThat(hit.snippet()).isEqualTo(expected.snippet());
                assertThat(hit.copies()).containsExactly(new MessageCopy("a", "f1"));
            }
        }
    }

    @Test void dateOnlySearchGroupsCopiesInDateOrder() {
        var date = Instant.parse("2026-10-26T00:00:00Z");
        try (var writer = MailIndex.openWriter(dir)) {
            putThread(writer, "z", "f1", date.plusSeconds(1), "<latest>", null);
            putThread(writer, "b", "f2", date, "<copy>", null);
            putThread(writer, "a", "f1", date, "<copy>", null);
            putThread(writer, "c", "f3", date, "<copy>", null);
            putThread(writer, "undated", "f1", null, "<copy>", null);
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var range = DateRange.parse("2026-10-26", "2026-10-27");
                var page = reader.search(null, SearchField.ANY, null, 1, 1,
                        text -> { throw new AssertionError("no snippets"); }, null, range);
                assertThat(page.total()).isEqualTo(2);
                assertThat(page.hits()).extracting(h -> h.card().key()).containsExactly("a");
                var hit = page.hits().getFirst();
                assertThat(hit.copies()).containsExactly(new MessageCopy("b", "f2"), new MessageCopy("c", "f3"));
                assertThat(hit.score()).isZero();
                assertThat(hit.snippet()).isNull();
                assertThat(hit.snippetAttachmentFile()).isNull();
                assertThat(hit.snippetAttachmentName()).isNull();
                assertThat(reader.search(null, SearchField.ANY, null, 0, 10).hits())
                        .extracting(h -> h.card().key()).containsExactly("z", "a", "undated");
                assertThat(reader.search(null, SearchField.ANY, null, 0, 1, null, null,
                        DateRange.parse("2026-10-27", null)).total()).isZero();
            }
        }
    }

    @Test void folderFilteredSearchListsOnlyMatchingCopies() {
        var date = Instant.parse("2026-10-26T00:00:00Z");
        try (var writer = MailIndex.openWriter(dir)) {
            putCopy(writer, "a", "f1", date, "<copy>", "user@example.com", "Subject", "Needle");
            putCopy(writer, "b", "f1", date, "<copy>", "user@example.com", "Subject", "Needle");
            putCopy(writer, "c", "f10", date, "<copy>", "user@example.com", "Subject", "Needle");
            putCopy(writer, "d", "f1", date, "<copy>", "user@example.com", "Subject", "Unrelated");
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var page = reader.search("Needle", SearchField.BODY, "f1", 0, 10);
                assertThat(page.total()).isEqualTo(1);
                assertThat(page.hits().getFirst().card().key()).isEqualTo("a");
                assertThat(page.hits().getFirst().copies()).containsExactly(new MessageCopy("b", "f1"));
                assertThat(reader.search("Needle", SearchField.BODY, "f10", 0, 10).hits().getFirst().copies()).isEmpty();
                assertThat(reader.search("Needle", SearchField.BODY, null, 0, 10).hits().getFirst().copies())
                        .containsExactly(new MessageCopy("b", "f1"), new MessageCopy("c", "f10"));
            }
        }
    }

    @Test void threadGroupsCopiesAndCountsThemOnce() {
        var date = Instant.parse("2026-10-26T00:00:00Z");
        try (var writer = MailIndex.openWriter(dir)) {
            putThread(writer, "z", "f1", date.minusSeconds(1), "<root>", null);
            putThread(writer, "c", "f3", date, "<copy>", "<root>");
            putThread(writer, "b", "f2", date, "<copy>", null);
            putThread(writer, "a", "f1", date, " <copy> ", null);
            putThread(writer, "undated", "f1", null, null, "<root>");
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var page = reader.thread("c", 1, 1);
                assertThat(page.total()).isEqualTo(3);
                assertThat(page.cards()).extracting(c -> c.key()).containsExactly("a");
                assertThat(page.copies()).containsOnlyKeys("a");
                assertThat(page.copies().get("a")).containsExactly(new MessageCopy("b", "f2"), new MessageCopy("c", "f3"));
                assertThat(reader.thread("b", 0, 10).cards()).extracting(c -> c.key()).containsExactly("z", "a", "undated");
                assertThat(reader.thread("c", 0, 1).copies()).isEmpty();
                assertThat(reader.thread("c", 3, 1).cards()).isEmpty();
                assertThat(reader.thread("c", Integer.MAX_VALUE, 1).total()).isEqualTo(3);
            }
        }
    }

    private void putThread(MailIndex writer, String key, String folder, Instant date,
                           String messageId, String inReplyTo, String... references) {
        writer.put(new MessageMeta(key, folder, "INBOX", key, 0, null, Instant.now(), MessageState.CONVERTED, null),
                new MailMessage("Same subject", List.of(new Address("Name", "user@example.com")), null, null,
                        date, messageId, inReplyTo, List.of(references), List.of()), "Unused body", "Unused attachment text");
    }

    @Test void threadFindsAncestorsDescendantsAndMissingParentSiblings() {
        try (var writer = MailIndex.openWriter(dir)) {
            putThread(writer, "ancestor", "f1", null, " <root@example.com> ", null);
            putThread(writer, "bridge", "f2", null, "<bridge@example.com>", "<root@example.com>", "<missing@example.com>");
            putThread(writer, "descendant", "f3", null, "<child@example.com>", "<bridge@example.com>");
            putThread(writer, "sibling", "f4", null, "<sibling@example.com>", "<missing@example.com>");
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var result = reader.thread("descendant", 0, 10);
                assertThat(result.total()).isEqualTo(4);
                assertThat(result.cards()).extracting(c -> c.key()).containsExactly("ancestor", "bridge", "descendant", "sibling");
                assertThat(reader.thread("ancestor", 2, 1).cards()).extracting(c -> c.key()).containsExactly("descendant");
                assertThat(reader.thread("sibling", 0, 10).cards()).isEqualTo(result.cards());
            }
        }
    }

    @Test void threadCyclesAndRepeatedIdsTerminate() {
        try (var writer = MailIndex.openWriter(dir)) {
            putThread(writer, "a", "f1", null, "<a>", "<c> <c> <a>", "<b>", "<b>");
            putThread(writer, "b", "f1", null, "<b>", "<a>", "<a> <a>");
            putThread(writer, "c", "f2", null, "<c>", "<b>");
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.thread("a", 0, 10).total()).isEqualTo(3);
                assertThat(reader.thread("a", 0, 10).cards()).extracting(c -> c.key()).containsExactly("a", "b", "c");
            }
        }
    }

    @Test void threadKeepsCopiesAndDoesNotMatchSubjectOrIdCase() {
        try (var writer = MailIndex.openWriter(dir)) {
            putThread(writer, "a", "f1", null, "<Same@example.com>", null);
            putThread(writer, "copy", "f2", null, "<Same@example.com>", null);
            putThread(writer, "differentCase", "f1", null, "<same@example.com>", null);
            putThread(writer, "sameSubject", "f1", null, "<other@example.com>", null);
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.thread("a", 0, 10).cards()).extracting(c -> c.key()).containsExactly("a", "copy");
                assertThat(reader.thread("differentCase", 0, 10).total()).isEqualTo(1);
                assertThat(reader.thread("sameSubject", 0, 10).total()).isEqualTo(1);
            }
        }
    }

    @Test void threadSingletonMalformedHeadersAndDeletedDocuments() {
        try (var writer = MailIndex.openWriter(dir)) {
            putThread(writer, "target", "f1", null, "<valid>", null);
            int n = 0;
            for (String malformed : List.of("", "valid", "<>", "<valid> <other>", "<bad id>", "<<valid>>", "<outer<valid>>", "junk <valid>", "<bad\u2003id>")) {
                putThread(writer, "malformed" + n++, "f1", null, malformed, null);
            }
            putThread(writer, "badReplies", "f1", null, null, "<> <bad id> <outer<valid>>", "valid", "<<valid>>", "<outer", "<valid>>");
            putThread(writer, "a", "f1", null, "<a>", null);
            putThread(writer, "b", "f1", null, "<b>", null);
            putThread(writer, "deletedBridge", "f1", null, "<deleted>", "<a>", "<b>");
            writer.put(new MessageMeta("failed", "f1", "INBOX", "failed", 0, null, Instant.now(), MessageState.FAILED, "Unavailable"),
                    null, null, null);
            writer.commit();
            writer.delete("deletedBridge");
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                for (String key : List.of("target", "badReplies", "a", "b", "failed")) {
                    assertThat(reader.thread(key, 0, 10).cards()).extracting(c -> c.key()).containsExactly(key);
                }
                for (int i = 0; i < n; i++) { assertThat(reader.thread("malformed" + i, 0, 10).total()).isEqualTo(1); }
                assertThat(reader.thread("failed", 0, 10).cards().getFirst().state()).isEqualTo("failed");
                assertThatThrownBy(() -> reader.thread("deletedBridge", 0, 10)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Message is not indexed; use listMessages");
            }
        }
    }

    @Test void threadPagesChronologicallyWithUndatedLast() {
        try (var writer = MailIndex.openWriter(dir)) {
            var date = Instant.parse("2026-10-26T00:00:00Z");
            putThread(writer, "z", "f1", date.minusSeconds(1), "<root>", null);
            putThread(writer, "b", "f2", date, "<b>", "<root>");
            putThread(writer, "a", "f1", date, "<a>", "<root>");
            putThread(writer, "c", "f2", null, null, "<root>");
            putThread(writer, "d", "f2", null, null, "<root>");
            putThread(writer, "lastDated", "f2", Instant.ofEpochMilli(Long.MAX_VALUE), null, "<root>");
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.thread("a", 0, 20).cards()).extracting(c -> c.key()).containsExactly("z", "a", "b", "lastDated", "c", "d");
                var page = reader.thread("a", 1, 2);
                assertThat(page.total()).isEqualTo(6);
                assertThat(page.cards()).extracting(c -> c.key()).containsExactly("a", "b");
                assertThat(reader.thread("a", 5, Integer.MAX_VALUE).cards()).extracting(c -> c.key()).containsExactly("d");
                assertThat(reader.thread("a", 6, 1).cards()).isEmpty();
                assertThat(reader.thread("a", Integer.MAX_VALUE, 1).total()).isEqualTo(6);
                putThread(writer, "laterCommit", "f1", date, null, "<root>");
                writer.commit();
                assertThat(reader.thread("a", 0, 20).total()).isEqualTo(6);
            }
        }
    }

    private void put(MailIndex writer, String key, String folder, Instant date) {
        writer.put(new MessageMeta(key, folder, "INBOX", key, 0, null, Instant.now(), MessageState.CONVERTED, null),
                new MailMessage("Subject", List.of(new Address("Name", "user@example.com")), null, null,
                        date, null, null, null,
                        List.of(new AttachmentInfo("1__a", "a", "text/plain", 1, null, false, null, null))), null, null);
    }

    @Test void attachmentTextFieldFindsTheMessage() throws Exception {
        try (var writer = MailIndex.openWriter(dir)) {
            writer.put(new MessageMeta("a", "f1", "INBOX", "a", 0, null, Instant.now(), MessageState.CONVERTED, null),
                    null, "Leading body", "Договоры поставки. Reporting results.");
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                for (String query : List.of("договорами", "reports")) {
                    assertThat(reader.search(query, SearchField.ATTACHMENT_TEXT, null, 0, 20).hits())
                            .extracting(hit -> hit.card().key()).containsExactly("a");
                    assertThat(reader.search(query, SearchField.ANY, null, 0, 20).total()).isEqualTo(1);
                    assertThat(reader.search(query, SearchField.BODY, null, 0, 20).total()).isZero();
                }
            }
        }
        try (var directory = org.apache.lucene.store.FSDirectory.open(dir.resolve("index"));
             var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
            assertThat(reader.storedFields().document(0).get("attachmentText")).isNull();
            assertThat(reader.storedFields().document(0).get("attachmentText_en")).isNull();
        }
    }

    @Test void fullAddressQueryIgnoresDisplayNames() {
        try (var writer = MailIndex.openWriter(dir)) {
            writer.put(new MessageMeta("a", "f1", "INBOX", "a", 0, null, Instant.now(), MessageState.CONVERTED, null),
                    new MailMessage("Subject", List.of(new Address("alice@example.org", "mallory@example.net")),
                            null, null, null, null, null, null, List.of()), null, null);
            writer.put(new MessageMeta("b", "f1", "INBOX", "b", 0, null, Instant.now(), MessageState.CONVERTED, null),
                    new MailMessage("Subject", List.of(new Address("Alice", "alice@example.org")),
                            null, null, null, null, null, null, List.of()), null, null);
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                for (var field : List.of(SearchField.FROM, SearchField.ANY)) {
                    assertThat(reader.search("alice@example.org", field, null, 0, 20).hits())
                            .extracting(hit -> hit.card().key()).containsExactly("b");
                }
                assertThat(reader.search("alice", SearchField.FROM, null, 0, 20).hits())
                        .extracting(hit -> hit.card().key()).containsExactlyInAnyOrder("a", "b");
            }
        }
    }

    @Test void fullAddressQueryStillFindsTheAddressInTheBody() {
        try (var writer = MailIndex.openWriter(dir)) {
            writer.put(new MessageMeta("a", "f1", "INBOX", "a", 0, null, Instant.now(), MessageState.CONVERTED, null),
                    null, "Contact alice@example.org for details.", null);
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.search("alice@example.org", SearchField.ANY, null, 0, 20).hits())
                        .extracting(hit -> hit.card().key()).containsExactly("a");
            }
        }
    }

    @Test void searchFiltersByFolderAndPages() {
        try (var writer = MailIndex.openWriter(dir)) {
            put(writer, "b", "f1", null);
            put(writer, "a", "f1", null);
            put(writer, "c", "f10", null);
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                var first = reader.search("subjects", SearchField.SUBJECT, "f1", 0, 1);
                assertThat(first.total()).isEqualTo(2);
                assertThat(first.hits()).extracting(hit -> hit.card().key()).containsExactly("a");
                assertThat(first.hits().getFirst().score()).isPositive().isFinite();
                assertThat(first.hits().getFirst().card()).isEqualTo(reader.cards("f1", 0, 1).getFirst());
                var next = reader.search("subjects", SearchField.SUBJECT, "f1", 1, 1);
                assertThat(next.hits()).extracting(hit -> hit.card().key()).containsExactly("b");
                assertThat(reader.search("subjects", SearchField.ANY, null, 0, 20).total()).isEqualTo(3);
                assertThat(reader.search("subjects", SearchField.ANY, "f1", Integer.MAX_VALUE, 20).hits()).isEmpty();
            }
        }
    }

    @Test void stopWordOnlyQueryFindsNothing() {
        try (var writer = MailIndex.openWriter(dir)) {
            put(writer, "a", "f1", null);
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.search("и в на", SearchField.ANY, null, 0, 20).total()).isZero();
                assertThat(reader.search("the and", SearchField.BODY, null, 0, 20).hits()).isEmpty();
            }
        }
    }

    @Test void snippetMarksTheMatch() {
        try (var writer = MailIndex.openWriter(dir)) {
            writer.put(new MessageMeta("a", "f1", "INBOX", "a", 0, null, Instant.now(), MessageState.CONVERTED, null),
                    null, "Обсудим договоры поставки. Reporting results.", null);
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.search("договорами", SearchField.BODY, null, 0, 20).hits().getFirst().snippet())
                        .contains("**договоры**");
                assertThat(reader.search("reports", SearchField.BODY, null, 0, 20).hits().getFirst().snippet())
                        .contains("**Reporting**");
            }
        }
    }

    @Test void oldSchemaIsNotCurrent() throws Exception {
        try (var directory = org.apache.lucene.store.FSDirectory.open(dir.resolve("index"));
             var writer = new org.apache.lucene.index.IndexWriter(directory, new org.apache.lucene.index.IndexWriterConfig())) {
            writer.commit();
        }
        try (var reader = MailIndexReader.open(dir).orElseThrow()) { assertThat(reader.current()).isFalse(); }
    }

    @Test void noIndexIsEmpty() {
        assertThat(MailIndexReader.open(dir)).isEmpty();
        assertThat(dir.resolve("index")).doesNotExist();
        try (var writer = MailIndex.openWriter(dir)) { assertThat(MailIndexReader.open(dir)).isEmpty(); }
    }

    @Test void countsByFolder() {
        try (var writer = MailIndex.openWriter(dir)) {
            put(writer, "a", "f1", null);
            put(writer, "b", "f1", null);
            put(writer, "c", "f10", null);
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.count("f1")).isEqualTo(2);
                assertThat(reader.count("f10")).isEqualTo(1);
                assertThat(reader.count("absent")).isZero();
                assertThat(reader.cards("f1", 0, 1).getFirst().from()).isEqualTo("Name <user@example.com>");
                assertThat(reader.cards("f1", 0, 1).getFirst().hasAttachments()).isTrue();
            }
        }
    }

    @Test void cardsAreNewestFirstWithUndatedLastAndPaged() {
        try (var writer = MailIndex.openWriter(dir)) {
            put(writer, "c", "f1", Instant.parse("2026-10-24T00:00:00Z"));
            put(writer, "z", "f1", null);
            put(writer, "b", "f1", Instant.parse("2026-10-25T00:00:00Z"));
            put(writer, "a", "f1", Instant.parse("2026-10-25T00:00:00Z"));
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.cards("f1", 0, 2)).extracting(c -> c.key()).containsExactly("a", "b");
                assertThat(reader.cards("f1", 2, 2)).extracting(c -> c.key()).containsExactly("c", "z");
                assertThat(reader.cards("f1", 4, 2)).isEmpty();
                assertThat(reader.cards("f1", Integer.MAX_VALUE, 2)).isEmpty();
            }
        }
    }

    @Test void olderDocumentsWithoutKeyDocValuesStillSortByDate() throws Exception {
        try (var directory = org.apache.lucene.store.FSDirectory.open(dir.resolve("index"));
             var writer = new org.apache.lucene.index.IndexWriter(directory, new org.apache.lucene.index.IndexWriterConfig())) {
            for (int day : List.of(24, 25)) {
                var document = new org.apache.lucene.document.Document();
                document.add(new org.apache.lucene.document.StringField("key", "old" + day, org.apache.lucene.document.Field.Store.YES));
                document.add(new org.apache.lucene.document.StringField("ref", "f1/" + day, org.apache.lucene.document.Field.Store.YES));
                long date = Instant.parse("2026-10-" + day + "T00:00:00Z").toEpochMilli();
                document.add(new org.apache.lucene.document.LongPoint("date", date));
                document.add(new org.apache.lucene.document.StoredField("date", date));
                document.add(new org.apache.lucene.document.NumericDocValuesField("date", date));
                writer.addDocument(document);
            }
            writer.setLiveCommitData(java.util.Map.of("schema", MailIndex.SCHEMA).entrySet());
            writer.commit();
        }
        try (var writer = MailIndex.openWriter(dir)) {
            put(writer, "new", "f1", Instant.parse("2026-10-26T00:00:00Z"));
            writer.commit();
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.cards("f1", 0, 10)).extracting(c -> c.key()).containsExactly("new", "old25", "old24");
            }
        }
    }

    @Test void readerSeesOnlyCommittedDocuments() {
        try (var writer = MailIndex.openWriter(dir)) {
            put(writer, "a", "f1", null);
            writer.commit();
            put(writer, "b", "f1", null);
            try (var reader = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(reader.count("f1")).isEqualTo(1);
                writer.commit();
                assertThat(reader.count("f1")).isEqualTo(1);
                try (var next = MailIndexReader.open(dir).orElseThrow()) {
                    assertThat(next.count("f1")).isEqualTo(2);
                }
            }
        }
    }

    @Test void dateFilterUsesInclusiveSinceExclusiveUntilAndExcludesUndated() {
        try (var w = MailIndex.openWriter(dir)) {
            put(w, "a", "f1", Instant.parse("2026-10-26T00:00:00Z"));
            put(w, "b", "f1", Instant.parse("2026-10-27T23:59:59.999Z"));
            put(w, "c", "f1", Instant.parse("2026-10-28T00:00:00Z")); put(w, "d", "f1", null); w.commit();
            try (var r = MailIndexReader.open(dir).orElseThrow()) {
                assertThat(r.cards("f1", 0, 10, mail.mcp.model.DateRange.parse("2026-10-26", "2026-10-28")).cards()).extracting(c -> c.key()).containsExactly("b", "a");
                assertThat(r.cards("f1", 0, 10, mail.mcp.model.DateRange.parse(null, "2026-10-26T03:00:00+03:00")).total()).isZero();
                assertThat(r.cards("f1", 0, 10, mail.mcp.model.DateRange.parse("2026-10-28", null)).total()).isEqualTo(1);
                assertThat(r.cards("f1", 0, 10, mail.mcp.model.DateRange.parse(null, Instant.ofEpochMilli(Long.MIN_VALUE).toString())).total()).isZero();
                assertThat(r.cards("f1", 0, 10)).hasSize(4);
            }
        }
    }
    @Test void dateFilterCombinesFolderAndTextWithExactPagedTotal() {
        try (var w = MailIndex.openWriter(dir)) {
            put(w, "a", "f1", Instant.parse("2026-10-26T00:00:00Z")); put(w, "b", "f1", Instant.parse("2026-10-26T00:00:00Z")); put(w, "c", "f2", Instant.parse("2026-10-26T00:00:00Z")); w.commit();
            try (var r = MailIndexReader.open(dir).orElseThrow()) {
                var range = mail.mcp.model.DateRange.parse("2026-10-26", "2026-10-27");
                var page = r.search("subject", SearchField.SUBJECT, "f1", 1, 1, null, null, range);
                assertThat(page.total()).isEqualTo(2); assertThat(page.hits()).extracting(h -> h.card().key()).containsExactly("b");
                assertThat(r.search("subject", SearchField.SUBJECT, "f1", 9, 1, null, null, range).hits()).isEmpty();
                assertThat(r.search("subject", SearchField.SUBJECT, "f2", 0, 1, null, null, mail.mcp.model.DateRange.parse("2026-10-27", null)).total()).isZero();
            }
        }
    }
    @Test void dateOnlySearchSortsByDateAndDoesNotReadSidecars() {
        try (var w = MailIndex.openWriter(dir)) {
            put(w, "b", "f1", Instant.parse("2026-10-26T00:00:00Z")); put(w, "a", "f2", Instant.parse("2026-10-26T00:00:00Z")); put(w, "c", "f1", Instant.parse("2026-10-27T00:00:00Z")); w.commit();
            try (var r = MailIndexReader.open(dir).orElseThrow()) {
                var result = r.search(null, SearchField.ANY, null, 0, 10, text -> { throw new AssertionError("no snippets"); }, new mail.mcp.store.MessageStore(dir, new mail.mcp.config.JsonConfig().mailMcpObjectMapper()), mail.mcp.model.DateRange.parse("2026-10-26", null));
                assertThat(result.hits()).extracting(h -> h.card().key()).containsExactly("c", "a", "b");
                assertThat(result.hits()).allSatisfy(h -> { assertThat(h.score()).isZero(); assertThat(h.snippet()).isNull(); assertThat(h.snippetAttachmentFile()).isNull(); });
            }
        }
    }

}
