package mail.mcp.sync;

import mail.mcp.config.JsonConfig;
import mail.mcp.index.MailIndex;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.MessageState;
import mail.mcp.source.Downloaded;
import mail.mcp.source.FolderChanges;
import mail.mcp.source.MailSource;
import mail.mcp.source.RemoteFolder;
import mail.mcp.source.imap.ImapException;
import mail.mcp.store.MessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncRunnerTest {
    @TempDir Path connectionDir;
    private final FakeMailSource source = new FakeMailSource();

    private MessageStore store() {
        return new MessageStore(connectionDir, new JsonConfig().mailMcpObjectMapper());
    }

    private List<FolderProgress> run() {
        List<FolderProgress> progress = new ArrayList<>();
        try (var index = MailIndex.openWriter(connectionDir)) {
            new SyncRunner(store(), index, source).run(progress::add);
        }
        return progress;
    }

    private Map<String, String> refs(String folderKey) {
        try (var index = MailIndex.openWriter(connectionDir)) { return index.folderRefs(folderKey); }
    }

    private static String[] messageRefs(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(String::valueOf).toArray(String[]::new);
    }

    @Test void firstRunDownloadsEverythingAndSecondRunNothing() throws Exception {
        source.putFolder("inbox", "INBOX", "7", "2", "1");
        source.putFolder("archive", "Archive", "9", "3");
        Instant before = Instant.now();
        assertThat(run()).containsExactly(new FolderProgress("INBOX", 2, 0, 0, false, true),
                new FolderProgress("Archive", 1, 0, 0, false, true));
        Instant after = Instant.now();
        assertThat(source.downloadCalls).isEqualTo(3);
        assertThat(source.foldersCalls).isEqualTo(1);
        assertThat(source.changesCalls).isEqualTo(2);
        assertThat(store().folders()).containsExactly(new LocalFolder("f1", "inbox", "INBOX", "7"),
                new LocalFolder("f2", "archive", "Archive", "9"));
        assertThat(refs("f1")).containsOnlyKeys("1", "2");
        assertThat(refs("f2")).containsOnlyKeys("3");
        for (var folder : store().folders()) {
            for (var entry : refs(folder.key()).entrySet()) {
                var meta = store().readMeta(entry.getValue()).orElseThrow();
                byte[] bytes = FakeMailSource.mime(entry.getKey());
                assertThat(Files.readAllBytes(store().messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE)))
                        .isEqualTo(bytes);
                assertThat(meta.key()).isEqualTo(entry.getValue());
                assertThat(meta.folderKey()).isEqualTo(folder.key());
                assertThat(meta.folder()).isEqualTo(folder.path());
                assertThat(meta.ref()).isEqualTo(entry.getKey());
                assertThat(meta.size()).isEqualTo(bytes.length);
                assertThat(meta.sha256()).isEqualTo(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)));
                assertThat(meta.downloadedAt()).isBetween(before, after);
                assertThat(meta.state()).isEqualTo(MessageState.DOWNLOADED);
                assertThat(meta.error()).isNull();
            }
        }
        var keys = store().keys();
        source.resetCounts();
        assertThat(run()).allSatisfy(p -> assertThat(p.downloaded()).isZero());
        assertThat(source.downloadCalls).isZero();
        assertThat(store().keys()).isEqualTo(keys);
    }

    @Test void newMessageIsDownloadedAlone() {
        source.putFolder("inbox", "INBOX", "7", "1");
        run();
        String oldKey = refs("f1").get("1");
        source.putFolder("inbox", "INBOX", "7", "1", "2");
        source.resetCounts();
        assertThat(run()).containsExactly(new FolderProgress("INBOX", 1, 0, 0, false, true));
        assertThat(source.downloadCalls).isEqualTo(1);
        assertThat(refs("f1")).containsOnlyKeys("1", "2").containsEntry("1", oldKey);
    }

    @Test void removedMessageLosesDirectoryAndDocument() {
        source.putFolder("inbox", "INBOX", "7", "1", "2");
        run();
        String oldKey = refs("f1").get("1");
        source.putFolder("inbox", "INBOX", "7", "2");
        source.resetCounts();
        assertThat(run()).containsExactly(new FolderProgress("INBOX", 0, 0, 1, false, true));
        assertThat(store().messageDir(oldKey)).doesNotExist();
        assertThat(refs("f1")).containsOnlyKeys("2");
        assertThat(source.downloadCalls).isZero();
    }

    @Test void changedCursorResetsTheFolder() {
        source.putFolder("inbox", "INBOX", "7", "1", "2");
        run();
        var oldKeys = store().keys();
        source.putFolder("inbox", "INBOX", "8", "2", "3");
        source.resetCounts();
        // A reset must remove all local refs even when a source omits its removed list.
        MailSource resetSource = new MailSource() {
            @Override public List<RemoteFolder> folders() { return source.folders(); }
            @Override public FolderChanges changes(RemoteFolder folder, String cursor, Set<String> known) {
                var changes = source.changes(folder, cursor, known);
                assertThat(changes.reset()).isTrue();
                return new FolderChanges(changes.added(), List.of(), changes.cursor(), true);
            }
            @Override public Downloaded download(RemoteFolder folder, String ref, Path target) {
                return source.download(folder, ref, target);
            }
            @Override public void close() { }
        };
        List<FolderProgress> progress = new ArrayList<>();
        try (var index = MailIndex.openWriter(connectionDir)) {
            new SyncRunner(store(), index, resetSource).run(progress::add);
        }
        assertThat(progress).containsExactly(new FolderProgress("INBOX", 2, 0, 2, true, true));
        assertThat(source.downloadCalls).isEqualTo(2);
        oldKeys.forEach(key -> assertThat(store().messageDir(key)).doesNotExist());
        assertThat(store().keys()).doesNotContainAnyElementsOf(oldKeys);
        assertThat(refs("f1")).containsOnlyKeys("2", "3");
        assertThat(store().folders().getFirst().cursor()).isEqualTo("8");
    }

    @Test void vanishedFolderIsDeletedWithItsMessages() {
        source.putFolder("inbox", "INBOX", "7", "1", "2");
        source.putFolder("archive", "Archive", "9", "3");
        run();
        var vanishedKeys = refs("f1").values();
        source.removeFolder("inbox");
        assertThat(run()).containsExactly(new FolderProgress("INBOX", 0, 0, 2, false, true),
                new FolderProgress("Archive", 0, 0, 0, false, true));
        vanishedKeys.forEach(key -> assertThat(store().messageDir(key)).doesNotExist());
        assertThat(refs("f1")).isEmpty();
        assertThat(refs("f2")).containsOnlyKeys("3");
        assertThat(store().folders()).containsExactly(new LocalFolder("f2", "archive", "Archive", "9"));
    }

    @Test void newFolderGetsTheNextKey() {
        source.putFolder("inbox", "INBOX", "7", "1");
        source.putFolder("archive", "Archive", "9", "2");
        run();
        source.removeFolder("inbox");
        source.putFolder("new", "New", "10", "3");
        run();
        assertThat(store().folders()).containsExactly(new LocalFolder("f2", "archive", "Archive", "9"),
                new LocalFolder("f3", "new", "New", "10"));
        assertThat(refs("f3")).containsOnlyKeys("3");
    }

    @Test void unavailableMessageIsFailedAndNotRetried() {
        source.putFolder("inbox", "INBOX", "7", "1", "2");
        source.markTooLarge("inbox", "1");
        assertThat(run()).containsExactly(new FolderProgress("INBOX", 1, 1, 0, false, true));
        String key = refs("f1").get("1");
        var failed = store().readMeta(key).orElseThrow();
        assertThat(failed.state()).isEqualTo(MessageState.FAILED);
        assertThat(failed.size()).isZero();
        assertThat(failed.sha256()).isNull();
        assertThat(failed.error()).isEqualTo("Message exceeds the size limit");
        assertThat(store().messageDir(key).resolve(MessageStore.MESSAGE_FILE)).doesNotExist();
        assertThat(refs("f1")).containsOnlyKeys("1", "2");
        source.resetCounts();
        run();
        assertThat(source.downloadCalls).isZero();
        source.putFolder("inbox", "INBOX", "7", "2");
        assertThat(run()).containsExactly(new FolderProgress("INBOX", 0, 0, 1, false, true));
        assertThat(store().messageDir(key)).doesNotExist();
        assertThat(refs("f1")).containsOnlyKeys("2");
    }

    @Test void interruptedRunResumesWithoutRedownloading() {
        source.putFolder("inbox", "INBOX", "7", messageRefs(250));
        source.failOnDownload(150);
        List<FolderProgress> progress = new ArrayList<>();
        try (var index = MailIndex.openWriter(connectionDir)) {
            assertThatThrownBy(() -> new SyncRunner(store(), index, source).run(progress::add))
                    .isInstanceOf(ImapException.class)
                    .hasMessage(new ImapException(ImapException.Kind.CONNECTION).getMessage());
            assertThat(index.folderRefs("f1")).hasSize(149);
        }
        assertThat(progress).containsExactly(new FolderProgress("INBOX", 100, 0, 0, false, false));
        assertThat(source.downloadCalls).isEqualTo(150);
        assertThat(store().folders()).containsExactly(new LocalFolder("f1", "inbox", "INBOX", null));
        assertThat(store().keys()).hasSize(150);
        assertThat(refs("f1")).hasSize(149);
        var incomplete = store().keys().stream().filter(key -> !store().hasMeta(key)).toList();
        assertThat(incomplete).hasSize(1);
        assertThat(store().keys().stream().filter(store()::hasMeta)).hasSize(149);
        source.failOnDownload(0);
        source.resetCounts();
        run();
        assertThat(source.downloadCalls).isEqualTo(101);
        assertThat(store().messageDir(incomplete.getFirst())).doesNotExist();
        assertThat(store().keys()).hasSize(250).allSatisfy(key -> assertThat(store().hasMeta(key)).isTrue());
        assertThat(refs("f1")).hasSize(250);
        assertThat(store().folders().getFirst().cursor()).isEqualTo("7");
    }

    @Test void deletedIndexIsRebuiltBeforeAnyDownload() throws Exception {
        source.putFolder("inbox", "INBOX", "7", "1", "2");
        run();
        var originalRefs = refs("f1");
        Path indexDir = connectionDir.resolve("index").toAbsolutePath().normalize();
        assertThat(indexDir.getParent()).isEqualTo(connectionDir.toAbsolutePath().normalize());
        try (var paths = Files.walk(indexDir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
        source.resetCounts();
        run();
        assertThat(source.downloadCalls).isZero();
        assertThat(refs("f1")).isEqualTo(originalRefs);
    }

    @Test void progressReportsBatchesAndFolderEnds() {
        source.putFolder("inbox", "INBOX", "7", messageRefs(250));
        assertThat(run()).containsExactly(new FolderProgress("INBOX", 100, 0, 0, false, false),
                new FolderProgress("INBOX", 200, 0, 0, false, false),
                new FolderProgress("INBOX", 250, 0, 0, false, true));
    }

    @Test void pathChangeKeepsKeyAndCursor() {
        source.putFolder("id", "Old", "7", "1");
        run();
        var originalRefs = refs("f1");
        source.putFolder("id", "New", "7", "1");
        source.resetCounts();
        assertThat(run()).containsExactly(new FolderProgress("New", 0, 0, 0, false, true));
        assertThat(store().folders()).containsExactly(new LocalFolder("f1", "id", "New", "7"));
        assertThat(refs("f1")).isEqualTo(originalRefs);
        assertThat(source.downloadCalls).isZero();
    }
}
