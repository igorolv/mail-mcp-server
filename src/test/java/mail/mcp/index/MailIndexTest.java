package mail.mcp.index;

import mail.mcp.config.JsonConfig;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailIndexTest {
    @TempDir Path connectionDir;

    private MessageStore store() {
        return new MessageStore(connectionDir, new JsonConfig().mailMcpObjectMapper());
    }

    private static MessageMeta meta(String key, String folderKey, String ref) {
        return new MessageMeta(key, folderKey, "INBOX", ref, 12, "a".repeat(64),
                Instant.parse("2026-10-25T12:00:00Z"), MessageState.DOWNLOADED, null);
    }

    private static void save(MessageStore store, MessageMeta meta) {
        store.createMessageDir(meta.key());
        store.writeMeta(meta);
    }

    @Test void oldSchemaIsRecreatedOnOpen() throws Exception {
        try (var directory = FSDirectory.open(connectionDir.resolve("index"));
             var writer = new org.apache.lucene.index.IndexWriter(directory, new org.apache.lucene.index.IndexWriterConfig())) {
            var doc = new org.apache.lucene.document.Document();
            doc.add(new org.apache.lucene.document.StringField("key", "old", org.apache.lucene.document.Field.Store.YES));
            writer.addDocument(doc);
            writer.setLiveCommitData(Map.of("schema", "2").entrySet());
            writer.commit();
        }
        try (var index = MailIndex.openWriter(connectionDir)) { assertThat(index.keys()).isEmpty(); }
        try (var reader = MailIndexReader.open(connectionDir).orElseThrow()) { assertThat(reader.current()).isTrue(); }
        try (var directory = FSDirectory.open(connectionDir.resolve("index"));
             var reader = DirectoryReader.open(directory)) {
            assertThat(reader.getIndexCommit().getUserData().get("schema")).isEqualTo("3");
        }
    }

    @Test void recreateFlagEmptiesTheIndex() {
        try (var index = MailIndex.openWriter(connectionDir)) { index.put(meta("a", "f1", "101"), null, null, null); }
        try (var index = MailIndex.openWriter(connectionDir, true)) { assertThat(index.keys()).isEmpty(); }
        try (var reader = MailIndexReader.open(connectionDir).orElseThrow()) { assertThat(reader.current()).isTrue(); }
    }

    @Test void addressIsAnExactTerm() {
        var message = new mail.mcp.model.MailMessage("Subject",
                java.util.List.of(new mail.mcp.model.Address("Name", "ivan.testov@example.com")),
                null, null, null, null, null, null, null);
        try (var index = MailIndex.openWriter(connectionDir)) { index.put(meta("a", "f1", "101"), message, null, null); }
        try (var reader = MailIndexReader.open(connectionDir).orElseThrow()) {
            assertThat(reader.search("IVAN.TESTOV@EXAMPLE.COM", SearchField.FROM, null, 0, 20).total()).isEqualTo(1);
            assertThat(reader.search("ivan.testov", SearchField.FROM, null, 0, 20).total()).isZero();
            assertThat(reader.search("example.com", SearchField.FROM, null, 0, 20).total()).isZero();
            assertThat(reader.search("Name", SearchField.FROM, null, 0, 20).hits().getFirst().snippet()).isNull();
        }
    }

    @Test void putReplacesByKeyAndDeleteRemoves() {
        var store = store();
        String key = store.newKey();
        try (var index = MailIndex.openWriter(connectionDir)) {
            assertThat(index.keys()).isEmpty();
            assertThat(index.folderRefs("f1")).isEmpty();
            index.put(meta(key, "f1", "101"), null, null, null);
            assertThat(index.keys()).containsExactly(key);
            assertThat(index.folderRefs("f1")).containsExactlyEntriesOf(Map.of("101", key));
            index.put(meta(key, "f2", "102"), null, null, null);
            assertThat(index.keys()).containsExactly(key);
            assertThat(index.folderRefs("f1")).isEmpty();
            assertThat(index.folderRefs("f2")).containsExactlyEntriesOf(Map.of("102", key));
            index.delete(key);
            assertThat(index.keys()).isEmpty();
            assertThat(index.folderRefs("f2")).isEmpty();
        }
    }

    @Test void folderRefsReturnOnlyThatFolder() {
        var store = store();
        String a = store.newKey();
        String b = store.newKey();
        String c = store.newKey();
        try (var index = MailIndex.openWriter(connectionDir)) {
            index.put(meta(a, "f1", "101"), null, null, null);
            index.put(meta(b, "f10", "102"), null, null, null);
            index.put(meta(c, "f1", "AAMk/x+y="), null, null, null);
            assertThat(index.folderRefs("f1")).containsExactlyInAnyOrderEntriesOf(
                    Map.of("101", a, "AAMk/x+y=", c));
            assertThat(index.folderRefs("f10")).containsExactlyEntriesOf(Map.of("102", b));
            assertThat(index.folderRefs("f2")).isEmpty();
        }
    }

    @Test void committedDocumentsSurviveReopen() throws IOException {
        String a = store().newKey();
        String b = store().newKey();
        try (var index = MailIndex.openWriter(connectionDir)) {
            index.put(meta(a, "f1", "101"), null, null, null);
            index.commit();
            index.put(meta(b, "f1", "102"), null, null, null);
        }
        try (var index = MailIndex.openWriter(connectionDir)) {
            assertThat(index.keys()).containsExactlyInAnyOrder(a, b);
            assertThat(index.folderRefs("f1")).containsExactlyInAnyOrderEntriesOf(Map.of("101", a, "102", b));
        }
        try (var directory = FSDirectory.open(connectionDir.resolve("index"));
             var reader = DirectoryReader.open(directory)) {
            var document = reader.storedFields().document(0);
            assertThat(document.getFields()).extracting(field -> field.name())
                    .containsExactlyInAnyOrder("key", "ref", "folder", "state");
            assertThat(document.get("folder")).isEqualTo("INBOX");
            assertThat(document.get("state")).isEqualTo("downloaded");
        }
    }

    @Test void secondWriterOnTheSameConnectionIsRefused() {
        try (var first = MailIndex.openWriter(connectionDir)) {
            assertThatThrownBy(() -> MailIndex.openWriter(connectionDir))
                    .isInstanceOf(IndexLockedException.class)
                    .hasMessage("Connection is being synchronized by another process").hasNoCause();
            assertThat(first.keys()).isEmpty();
        }
        try (var next = MailIndex.openWriter(connectionDir)) {
            assertThat(next.keys()).isEmpty();
        }
    }

    @Test void reconcileDeletesIncompleteDirectoriesAndIndexesMissingOnes() throws IOException {
        var store = store();
        var a = meta(store.newKey(), "f1", "101");
        String b = store.newKey();
        String c = store.newKey();
        var alreadyIndexed = meta(store.newKey(), "f2", "104");
        save(store, a);
        store.createMessageDir(b);
        save(store, alreadyIndexed);
        try (var index = MailIndex.openWriter(connectionDir)) {
            index.put(meta(c, "f1", "103"), null, null, null);
            index.put(alreadyIndexed, null, null, null);
            // Matching indexed identities need no restoration; metadata is checked for reassignment.
            var missingKeys = index.reconcile(store);
            assertThat(missingKeys).containsExactly(a.key());
            assertThat(index.keys()).containsExactly(alreadyIndexed.key());
            for (String missing : missingKeys) { index.put(store.readMeta(missing).orElseThrow(), null, null, null); }
            index.commit();
            assertThat(index.keys()).containsExactlyInAnyOrder(a.key(), alreadyIndexed.key());
            assertThat(index.folderRefs("f1")).containsExactlyEntriesOf(Map.of("101", a.key()));
            assertThat(store.messageDir(b)).doesNotExist();
            assertThat(store.keys()).containsExactlyInAnyOrder(a.key(), alreadyIndexed.key());
        }
        try (var directory = FSDirectory.open(connectionDir.resolve("index"));
             var reader = DirectoryReader.open(directory)) {
            assertThat(reader.numDocs()).isEqualTo(2);
        }
    }

    @Test void reconcileRepairsRefChangedInMetadata() throws IOException {
        var store = store();
        String key = store.newKey();
        var old = new MessageMeta(key, "f1", "INBOX", "101", 12, "a".repeat(64),
                Instant.parse("2026-10-25T12:00:00Z"), MessageState.CONVERTED, null);
        save(store, old);
        Files.writeString(store.messageDir(key).resolve(MessageStore.MESSAGE_FILE), "Subject: Saved\r\n\r\nOriginal body");
        Files.writeString(store.messageDir(key).resolve(MessageStore.BODY_FILE), "Saved Markdown");
        try (var index = MailIndex.openWriter(connectionDir)) { index.put(old, null, null, null); }
        var moved = new MessageMeta(key, "f2", "Archive", "new/ref", old.size(), old.sha256(), old.downloadedAt(),
                old.state(), null);
        store.writeMeta(moved);
        try (var index = MailIndex.openWriter(connectionDir)) {
            assertThat(index.reconcile(store)).containsExactly(key);
            mail.mcp.sync.SyncRunner.restore(store, index,
                    new mail.mcp.sync.MessageProcessor(store, new mail.mcp.convert.Pandoc("unused")));
            assertThat(index.folderRefs("f1")).isEmpty();
            assertThat(index.folderRefs("f2")).containsEntry("new/ref", key);
            assertThat(index.reconcile(store)).isEmpty();
        }
        try (var directory = FSDirectory.open(connectionDir.resolve("index")); var reader = DirectoryReader.open(directory)) {
            var document = reader.storedFields().document(0);
            assertThat(document.get("body")).isEqualTo("Saved Markdown");
            assertThat(document.get("subject")).isEqualTo("Saved");
            assertThat(document.get("folder")).isEqualTo("Archive");
        }
        assertThat(Files.readString(store.messageDir(key).resolve(MessageStore.BODY_FILE))).isEqualTo("Saved Markdown");
    }

    @Test void deletedIndexIsRestoredFromDisk() throws IOException {
        var store = store();
        var a = meta(store.newKey(), "f1", "101");
        var b = meta(store.newKey(), "f1", "AAMk/x+y=");
        var c = meta(store.newKey(), "f2", "103");
        Set<String> keys;
        Map<String, String> f1;
        Map<String, String> f2;
        try (var index = MailIndex.openWriter(connectionDir)) {
            for (var meta : new MessageMeta[] { a, b, c }) {
                save(store, meta);
                index.put(meta, null, null, null);
            }
            keys = index.keys();
            f1 = index.folderRefs("f1");
            f2 = index.folderRefs("f2");
        }
        Path indexDir = connectionDir.resolve("index");
        assertThat(indexDir.toAbsolutePath().normalize().getParent())
                .isEqualTo(connectionDir.toAbsolutePath().normalize());
        try (var paths = Files.walk(indexDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
        assertThat(indexDir).doesNotExist();
        try (var index = MailIndex.openWriter(connectionDir)) {
            assertThat(index.keys()).isEmpty();
            for (String missing : index.reconcile(store)) { index.put(store.readMeta(missing).orElseThrow(), null, null, null); }
            index.commit();
            assertThat(index.keys()).isEqualTo(keys);
            assertThat(index.folderRefs("f1")).isEqualTo(f1);
            assertThat(index.folderRefs("f2")).isEqualTo(f2);
        }
    }

    @Test void failedMessageWithoutMimeIsIndexed() throws IOException {
        var store = store();
        String key = store.newKey();
        var meta = new MessageMeta(key, "f1", "INBOX", "101", 0, null,
                Instant.parse("2026-10-25T12:00:00Z"), MessageState.FAILED, "Synthetic download failure");
        save(store, meta);
        try (var index = MailIndex.openWriter(connectionDir)) {
            for (String missing : index.reconcile(store)) { index.put(store.readMeta(missing).orElseThrow(), null, null, null); }
            index.commit();
            assertThat(index.keys()).containsExactly(key);
            assertThat(index.folderRefs("f1")).containsExactlyEntriesOf(Map.of("101", key));
            assertThat(store.messageDir(key)).isDirectory();
            assertThat(store.messageDir(key).resolve(MessageStore.MESSAGE_FILE)).doesNotExist();
        }
        try (var directory = FSDirectory.open(connectionDir.resolve("index"));
             var reader = DirectoryReader.open(directory)) {
            var document = reader.storedFields().document(0);
            assertThat(document.getFields()).extracting(field -> field.name())
                    .containsExactlyInAnyOrder("key", "ref", "folder", "state", "error");
            assertThat(document.get("state")).isEqualTo("failed");
            assertThat(document.get("error")).isEqualTo(meta.error());
            assertThat(reader.leaves().getFirst().reader().getFieldInfos().fieldInfo("error").getIndexOptions())
                    .isEqualTo(IndexOptions.NONE);
        }
    }

    @Test void headersBodyAndDateUseTheSpecifiedFields() throws Exception {
        var meta = meta(store().newKey(), "f1", "101");
        var date = Instant.parse("2026-10-25T12:00:00Z");
        var message = new mail.mcp.model.MailMessage("Header subject",
                java.util.List.of(new mail.mcp.model.Address("Name", "sender@example.com"),
                        new mail.mcp.model.Address(null, "other@example.com")),
                java.util.List.of(new mail.mcp.model.Address(null, "reader@example.org")),
                java.util.List.of(new mail.mcp.model.Address("Copy", "copy@example.com")), date,
                "<message@example.com>", "<parent@example.com>",
                java.util.List.of("<one@example.com>", "<two@example.com>"),
                java.util.List.of(new mail.mcp.model.AttachmentInfo("1__part", null, "application/octet-stream", 1, null, false, null, null),
                        new mail.mcp.model.AttachmentInfo("2__file.txt", "file.txt", "text/plain", 1, null, false, null, null)));
        try (var index = MailIndex.openWriter(connectionDir)) {
            index.put(meta, message, "Indexed body", null);
            index.commit();
        }
        try (var directory = FSDirectory.open(connectionDir.resolve("index"));
             var reader = DirectoryReader.open(directory)) {
            var searcher = new org.apache.lucene.search.IndexSearcher(reader);
            var hit = searcher.search(org.apache.lucene.document.LongPoint.newExactQuery("date", date.toEpochMilli()), 1);
            assertThat(hit.totalHits.value()).isEqualTo(1);
            var document = reader.storedFields().document(hit.scoreDocs[0].doc);
            assertThat(document.getField("date").numericValue().longValue()).isEqualTo(date.toEpochMilli());
            var values = reader.leaves().getFirst().reader().getNumericDocValues("date");
            assertThat(values.advanceExact(0)).isTrue();
            assertThat(values.longValue()).isEqualTo(date.toEpochMilli());
            assertThat(document.get("subject")).isEqualTo("Header subject");
            assertThat(document.get("from")).isEqualTo("Name <sender@example.com>, other@example.com");
            assertThat(document.get("to")).isEqualTo("reader@example.org");
            assertThat(document.get("cc")).isEqualTo("Copy <copy@example.com>");
            assertThat(document.get("messageId")).isEqualTo(message.messageId());
            assertThat(document.get("inReplyTo")).isEqualTo(message.inReplyTo());
            assertThat(document.getValues("references")).containsExactlyElementsOf(message.references());
            assertThat(document.get("body")).isEqualTo("Indexed body");
            assertThat(document.get("attachmentNames")).isEqualTo("1__part\nfile.txt");
            assertThat(searcher.count(new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term("body", "indexed")))).isEqualTo(1);
        }
    }
}
