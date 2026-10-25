package mail.mcp.index;

import mail.mcp.model.Address;
import mail.mcp.model.AttachmentInfo;
import mail.mcp.model.MailMessage;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailIndexReaderTest {
    @TempDir Path dir;

    private void put(MailIndex writer, String key, String folder, Instant date) {
        writer.put(new MessageMeta(key, folder, "INBOX", key, 0, null, Instant.now(), MessageState.CONVERTED, null),
                new MailMessage("Subject", List.of(new Address("Name", "user@example.com")), null, null,
                        date, null, null, null,
                        List.of(new AttachmentInfo("1__a", "a", "text/plain", 1, null, false))), null);
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
}
