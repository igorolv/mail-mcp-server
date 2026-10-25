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

    @Test void putReplacesByKeyAndDeleteRemoves() {
        var store = store();
        String key = store.newKey();
        try (var index = MailIndex.openWriter(connectionDir)) {
            assertThat(index.keys()).isEmpty();
            assertThat(index.folderRefs("f1")).isEmpty();
            index.put(meta(key, "f1", "101"));
            assertThat(index.keys()).containsExactly(key);
            assertThat(index.folderRefs("f1")).containsExactlyEntriesOf(Map.of("101", key));
            index.put(meta(key, "f2", "102"));
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
            index.put(meta(a, "f1", "101"));
            index.put(meta(b, "f10", "102"));
            index.put(meta(c, "f1", "AAMk/x+y="));
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
            index.put(meta(a, "f1", "101"));
            index.commit();
            index.put(meta(b, "f1", "102"));
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
            index.put(meta(c, "f1", "103"));
            index.put(alreadyIndexed);
            // Existing indexed metadata must not be read during reconciliation.
            Files.writeString(store.messageDir(alreadyIndexed.key()).resolve(MessageStore.META_FILE), "not JSON");
            index.reconcile(store);
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
                index.put(meta);
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
            index.reconcile(store);
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
            index.reconcile(store);
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
}
