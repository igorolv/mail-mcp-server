package mail.mcp.sync;

import mail.mcp.config.JsonConfig;
import mail.mcp.index.MailIndex;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.FolderProgress;
import mail.mcp.model.MessageState;
import mail.mcp.model.MessageFingerprint;
import mail.mcp.model.MessageMeta;
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
    private static final MessageFingerprint IDENTITY = new MessageFingerprint("<move@example.com>", 987654L,
            Instant.parse("2026-10-25T12:00:00Z"), "Sender@example.com", "Exact decoded subject");
    @TempDir Path connectionDir;
    private FakeMailSource source = new FakeMailSource();

    private MessageStore store() {
        return new MessageStore(connectionDir, new JsonConfig().mailMcpObjectMapper());
    }

    private MessageProcessor processor() {
        return new MessageProcessor(store(), new mail.mcp.convert.Pandoc("pandoc"));
    }

    private List<FolderProgress> run() {
        List<FolderProgress> progress = new ArrayList<>();
        try (var index = MailIndex.openWriter(connectionDir)) {
            new SyncRunner(store(), index, source, processor()).run(progress::add);
        }
        return progress;
    }

    private Map<String, String> refs(String folderKey) {
        try (var index = MailIndex.openWriter(connectionDir)) { return index.folderRefs(folderKey); }
    }

    private static String[] messageRefs(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(String::valueOf).toArray(String[]::new);
    }

    private String seedMove(boolean reverse) throws Exception {
        source = new FakeMailSource();
        if (reverse) { source.putFolder("b", "B", "7"); }
        source.putFolder("a", "A", "7", "1");
        if (!reverse) { source.putFolder("b", "B", "7"); }
        source.fingerprint("a", "1", IDENTITY);
        source.putMime("a", "1", ("MIME-Version: 1.0\r\nSubject: Saved subject\r\n"
                + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
                + "--parts\r\nContent-Type: text/plain\r\n\r\nSaved body\r\n"
                + "--parts\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=one.txt\r\n\r\n"
                + "Saved attachment\r\n--parts--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        run();
        String key = refs(reverse ? "f2" : "f1").get("1");
        Files.writeString(store().messageDir(key).resolve(MessageStore.BODY_FILE), "Saved custom Markdown");
        source.resetCounts();
        return key;
    }

    private void moveRemote() {
        source.putFolder("a", "A", "7");
        source.putFolder("b", "B", "7", "2");
        source.fingerprint("b", "2", IDENTITY);
    }

    private Map<String, String> content(String key) throws Exception {
        var directory = store().messageDir(key);
        var result = new java.util.TreeMap<String, String>();
        try (var files = Files.walk(directory)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().equals(MessageStore.META_FILE)) {
                    result.put(directory.relativize(file).toString(), HexFormat.of().formatHex(Files.readAllBytes(file))
                            + ":" + Files.getLastModifiedTime(file));
                }
            }
        }
        return result;
    }

    private void assertMoved(String key, MessageMeta old, Map<String, String> bytes, String folderKey) throws Exception {
        assertThat(store().keys()).containsExactly(key);
        assertThat(refs(folderKey)).containsExactlyEntriesOf(Map.of("2", key));
        var meta = store().readMeta(key).orElseThrow();
        assertThat(meta.folder()).isEqualTo("B");
        assertThat(meta.fingerprint()).isEqualTo(IDENTITY);
        assertThat(meta.size()).isEqualTo(old.size()).isNotEqualTo(IDENTITY.size());
        assertThat(meta.sha256()).isEqualTo(old.sha256());
        assertThat(meta.downloadedAt()).isEqualTo(old.downloadedAt());
        assertThat(meta.state()).isEqualTo(old.state());
        assertThat(meta.error()).isEqualTo(old.error());
        assertThat(content(key)).isEqualTo(bytes);
        assertThat(source.downloadCalls).isZero();
        try (var directory = org.apache.lucene.store.FSDirectory.open(connectionDir.resolve("index"));
             var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
            var document = reader.storedFields().document(0);
            assertThat(document.get("body")).isEqualTo("Saved custom Markdown");
            assertThat(document.get("subject")).isEqualTo("Saved subject");
            assertThat(document.get("folder")).isEqualTo("B");
        }
    }

    @Test void moveReusesKeyAndContentInEitherFolderOrder() throws Exception {
        Path base = connectionDir;
        for (boolean reverse : List.of(false, true)) {
            connectionDir = base.resolve(String.valueOf(reverse));
            String key = seedMove(reverse);
            var old = store().readMeta(key).orElseThrow();
            var bytes = content(key);
            assertThat(bytes.keySet()).contains("message.eml", "body.md",
                    Path.of("attachments", "1__one.txt").toString(), Path.of("attachments", "1__one.txt.md").toString());
            moveRemote();
            assertThat(run()).allSatisfy(p -> {
                assertThat(p.downloaded()).isZero();
                assertThat(p.failed()).isZero();
                assertThat(p.removed()).isZero();
            });
            assertThat(refs(reverse ? "f2" : "f1")).isEmpty();
            assertMoved(key, old, bytes, reverse ? "f1" : "f2");
            run();
            assertMoved(key, old, bytes, reverse ? "f1" : "f2");
        }
    }

    @Test void moveFromVanishedFolderPreservesReassignedDirectory() throws Exception {
        String key = seedMove(false);
        var old = store().readMeta(key).orElseThrow();
        var bytes = content(key);
        moveRemote();
        source.removeFolder("a");
        assertThat(run()).containsExactly(new FolderProgress("A", 0, 0, 0, false, true),
                new FolderProgress("B", 0, 0, 0, false, true));
        assertThat(store().folders()).containsExactly(new LocalFolder("f2", "b", "B", "7"));
        assertMoved(key, old, bytes, "f2");
    }

    @Test void ambiguousOrIncompleteMoveIdentityFallsBackToDownload() throws Exception {
        var incomplete = java.util.Arrays.asList(
                null,
                new MessageFingerprint(null, IDENTITY.size(), IDENTITY.sentAt(), IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint(" ", IDENTITY.size(), IDENTITY.sentAt(), IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint("<one@example.com> <two@example.com>", IDENTITY.size(), IDENTITY.sentAt(), IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), null, IDENTITY.sentAt(), IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), 0L, IDENTITY.sentAt(), IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), IDENTITY.size(), null, IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), IDENTITY.size(), IDENTITY.sentAt(), " ", IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), IDENTITY.size(), IDENTITY.sentAt(), IDENTITY.from(), " "));
        Path base = connectionDir;
        int n = 0;
        for (var fingerprint : incomplete) {
            connectionDir = base.resolve("incomplete" + n++);
            String key = seedMove(false);
            moveRemote();
            source.fingerprint("b", "2", fingerprint);
            run();
            assertThat(source.downloadCalls).isEqualTo(1);
            assertThat(store().messageDir(key)).doesNotExist();
            assertThat(refs("f2").get("2")).isNotEqualTo(key);
        }
        for (boolean duplicateOld : List.of(false, true)) {
            connectionDir = base.resolve("duplicate" + duplicateOld);
            String key = seedMove(false);
            if (duplicateOld) {
                source.putFolder("a", "A", "7", "1", "3");
                source.fingerprint("a", "3", IDENTITY);
                run();
            }
            moveRemote();
            if (!duplicateOld) {
                source.putFolder("b", "B", "7", "2", "3");
                source.fingerprint("b", "3", IDENTITY);
            }
            source.resetCounts();
            run();
            assertThat(source.downloadCalls).isEqualTo(duplicateOld ? 1 : 2);
            assertThat(store().messageDir(key)).doesNotExist();
        }
    }

    @Test void retainedCopyAndChangedMetadataDoNotReuseKey() throws Exception {
        Path base = connectionDir;
        var changed = List.of(
                new MessageFingerprint("<Move@example.com>", IDENTITY.size(), IDENTITY.sentAt(), IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), IDENTITY.size() + 1, IDENTITY.sentAt(), IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), IDENTITY.size(), IDENTITY.sentAt().plusSeconds(1), IDENTITY.from(), IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), IDENTITY.size(), IDENTITY.sentAt(), "sender@example.com", IDENTITY.subject()),
                new MessageFingerprint(IDENTITY.messageId(), IDENTITY.size(), IDENTITY.sentAt(), IDENTITY.from(), "exact decoded subject"));
        int n = 0;
        for (var fingerprint : changed) {
            connectionDir = base.resolve("changed" + n++);
            String key = seedMove(false);
            moveRemote();
            source.fingerprint("b", "2", fingerprint);
            run();
            assertThat(source.downloadCalls).isEqualTo(1);
            assertThat(store().messageDir(key)).doesNotExist();
        }
        connectionDir = base.resolve("retained");
        String key = seedMove(false);
        source.putFolder("c", "C", "7", "3");
        source.fingerprint("c", "3", IDENTITY);
        run();
        // Refresh the retained copy to incomplete: pre-run ambiguity must still disqualify it.
        source.fingerprint("c", "3", null);
        moveRemote();
        source.resetCounts();
        run();
        assertThat(source.downloadCalls).isEqualTo(1);
        assertThat(store().messageDir(key)).doesNotExist();
        assertThat(store().readMeta(refs("f3").get("3")).orElseThrow().fingerprint()).isNull();
        source.removeFolder("c");
    }

    @Test void duplicateMessageIdsWithDifferentMetadataStillAllowUniqueMove() throws Exception {
        String key = seedMove(false);
        source.putFolder("c", "C", "7", "3");
        var different = new MessageFingerprint(IDENTITY.messageId(), IDENTITY.size(), IDENTITY.sentAt(),
                IDENTITY.from(), "Different subject");
        source.fingerprint("c", "3", different);
        run();
        String retained = refs("f3").get("3");
        var bytes = content(key);
        moveRemote();
        source.resetCounts();
        run();
        assertThat(source.downloadCalls).isZero();
        assertThat(refs("f2")).containsEntry("2", key);
        assertThat(refs("f3")).containsEntry("3", retained);
        assertThat(store().keys()).containsExactlyInAnyOrder(key, retained);
        assertThat(content(key)).isEqualTo(bytes);
    }

    @Test void newlySeededRetainedCopyDisablesMoveReuse() throws Exception {
        String key = seedMove(false);
        source.putFolder("c", "C", "7", "3");
        run(); // This retained copy has no saved fingerprint yet.
        source.fingerprint("c", "3", IDENTITY);
        moveRemote();
        source.resetCounts();
        run();
        assertThat(source.downloadCalls).isEqualTo(1);
        assertThat(store().messageDir(key)).doesNotExist();
        assertThat(store().readMeta(refs("f3").get("3")).orElseThrow().fingerprint()).isEqualTo(IDENTITY);
    }

    @Test void interruptionDuringOtherDownloadsKeepsTheCommittedMove() throws Exception {
        String key = seedMove(false);
        var old = store().readMeta(key).orElseThrow();
        var bytes = content(key);
        moveRemote();
        source.putFolder("b", "B", "7", "2", "3");
        source.failOnDownload(1);
        assertThatThrownBy(this::run).isInstanceOf(ImapException.class);
        assertThat(refs("f2")).containsEntry("2", key);
        assertThat(store().folders()).allSatisfy(f -> assertThat(f.cursor()).isEqualTo("7"));
        source.failOnDownload(0);
        source.resetCounts();
        run();
        assertThat(source.downloadCalls).isEqualTo(1); // Only the unrelated addition.
        assertThat(refs("f2")).containsEntry("2", key).containsKey("3");
        assertThat(content(key)).isEqualTo(bytes);
        assertThat(store().readMeta(key).orElseThrow().downloadedAt()).isEqualTo(old.downloadedAt());
    }

    @Test void firstSyncSeedsFingerprintWithoutRedownloadingKnownMime() throws Exception {
        String key = seedMove(false);
        var old = store().readMeta(key).orElseThrow();
        var legacy = new MessageMeta(key, old.folderKey(), old.folder(), old.ref(), old.size(), old.sha256(),
                old.downloadedAt(), old.state(), old.error());
        store().writeMeta(legacy);
        var bytes = content(key);
        run();
        assertThat(store().readMeta(key).orElseThrow().fingerprint()).isEqualTo(IDENTITY);
        assertThat(content(key)).isEqualTo(bytes);
        assertThat(source.downloadCalls).isZero();
        assertThat(source.metadataCalls).isEqualTo(2);
        source.fingerprint("a", "1", new MessageFingerprint(IDENTITY.messageId(), null, IDENTITY.sentAt(), IDENTITY.from(), IDENTITY.subject()));
        run();
        assertThat(store().readMeta(key).orElseThrow().fingerprint()).isNull();
        moveRemote();
        run();
        assertThat(source.downloadCalls).isEqualTo(1);
        assertThat(store().messageDir(key)).doesNotExist();
    }

    @Test void uidValidityResetNeverUsesMoveHeuristic() throws Exception {
        Path base = connectionDir;
        for (boolean sourceReset : List.of(false, true)) {
            connectionDir = base.resolve("reset" + sourceReset);
            String key = seedMove(false);
            moveRemote();
            if (sourceReset) { source.putFolder("a", "A", "8"); }
            else { source.putFolder("b", "B", "8", "2"); }
            assertThat(run()).filteredOn(FolderProgress::reset).hasSize(1);
            assertThat(source.downloadCalls).isEqualTo(1);
            assertThat(store().messageDir(key)).doesNotExist();
        }
    }

    @Test void moveDiscoveryFailureDoesNotDeleteCandidatesOrAdvanceCursors() throws Exception {
        Path base = connectionDir;
        for (boolean vanish : List.of(false, true)) {
            connectionDir = base.resolve("discovery" + vanish);
            String key = seedMove(false);
            var old = store().readMeta(key).orElseThrow();
            var bytes = content(key);
            var folders = store().folders();
            moveRemote();
            if (vanish) { source.removeFolder("a"); }
            source.failMetadataAt = vanish ? 1 : 2;
            assertThatThrownBy(this::run).isInstanceOf(ImapException.class);
            assertThat(store().readMeta(key)).contains(old);
            assertThat(content(key)).isEqualTo(bytes);
            assertThat(store().folders()).isEqualTo(folders);
            assertThat(refs("f1")).containsEntry("1", key);
            assertThat(source.downloadCalls).isZero();
            source.failMetadataAt = 0;
            run();
            assertMoved(key, old, bytes, "f2");
        }
    }

    @Test void interruptedMoveConvergesWithoutContentLossOrRepeatDownload() throws Exception {
        Path base = connectionDir;
        for (String point : List.of("metadataReassigned", "movesCommitted", "beforeCursorSave")) {
            connectionDir = base.resolve(point);
            String key = seedMove(false);
            var old = store().readMeta(key).orElseThrow();
            var bytes = content(key);
            moveRemote();
            source.removeFolder("a");
            source.removeFolder("b");
            source.putFolder("new", "B", "new-cursor", "2");
            source.fingerprint("new", "2", IDENTITY);
            try (var index = MailIndex.openWriter(connectionDir)) {
                assertThatThrownBy(() -> new SyncRunner(store(), index, source, processor(), p -> {
                    if (p.equals(point)) { throw new IllegalStateException("Synthetic interruption"); }
                }).run(p -> { })).hasMessage("Synthetic interruption");
            }
            assertThat(store().folders()).containsExactly(new LocalFolder("f3", "new", "B", null),
                    new LocalFolder("f1", "a", "A", "7"), new LocalFolder("f2", "b", "B", "7"));
            assertThat(store().readMeta(key).orElseThrow().folderKey()).isEqualTo("f3");
            try (var index = MailIndex.openWriter(connectionDir)) {
                assertThat(index.folderRefs("f1").containsKey("1")).isEqualTo(point.equals("metadataReassigned"));
                SyncRunner.restore(store(), index, processor());
                assertThat(index.folderRefs("f1")).isEmpty();
                assertThat(index.folderRefs("f3")).containsEntry("2", key);
            }
            run();
            assertMoved(key, old, bytes, "f3");
            run();
            assertMoved(key, old, bytes, "f3");
            assertThat(store().folders()).containsExactly(new LocalFolder("f3", "new", "B", "new-cursor"));
            source.removeFolder("new");
        }
    }

    @Test void failedDownloadedUnseededAndOtherConnectionEntriesCannotSupplyMoves() throws Exception {
        Path base = connectionDir;
        for (String kind : List.of("failed", "downloaded", "unseeded", "missingMime", "otherConnection")) {
            connectionDir = base.resolve(kind);
            String key = seedMove(false);
            var old = store().readMeta(key).orElseThrow();
            if (kind.equals("otherConnection")) {
                connectionDir = base.resolve("other-destination");
            } else {
                var meta = new MessageMeta(key, old.folderKey(), old.folder(), old.ref(), old.size(), old.sha256(), old.downloadedAt(),
                        kind.equals("failed") ? MessageState.FAILED : kind.equals("downloaded") ? MessageState.DOWNLOADED : old.state(),
                        null, kind.equals("unseeded") ? null : old.fingerprint());
                store().writeMeta(meta);
                if (kind.equals("missingMime")) { Files.delete(store().messageDir(key).resolve(MessageStore.MESSAGE_FILE)); }
                try (var index = MailIndex.openWriter(connectionDir)) { index.put(meta, null, null, null); }
            }
            moveRemote();
            source.resetCounts();
            run();
            assertThat(source.downloadCalls).isEqualTo(1);
            assertThat(store().keys()).doesNotContain(key);
        }
    }

    @Test void statusIsRunningDuringTheRunAndCompletedAfter() {
        source.putFolder("inbox", "INBOX", "7", messageRefs(101));
        List<FolderProgress> seen = new ArrayList<>();
        try (var index = MailIndex.openWriter(connectionDir)) {
            new SyncRunner(store(), index, source, processor()).run(p -> {
                seen.add(p);
                var status = store().readSyncStatus().orElseThrow();
                assertThat(status.state()).isEqualTo("running");
                assertThat(status.pid()).isEqualTo(ProcessHandle.current().pid());
                assertThat(status.finishedAt()).isNull();
                assertThat(status.folders()).containsExactly(p);
            });
        }
        assertThat(seen).hasSize(2);
        var status = store().readSyncStatus().orElseThrow();
        assertThat(status.state()).isEqualTo("completed");
        assertThat(status.finishedAt()).isAfterOrEqualTo(status.startedAt());
        assertThat(status.folders()).containsExactly(seen.getLast());
        assertThat(status.signInRejected()).isFalse();
    }

    @Test void failureWritesFailedStatusAndRethrows() {
        source.putFolder("inbox", "INBOX", "7", "1");
        source.failOnDownload(1);
        try (var index = MailIndex.openWriter(connectionDir)) {
            assertThatThrownBy(() -> new SyncRunner(store(), index, source, processor()).run(p -> { }))
                    .isInstanceOf(ImapException.class);
        }
        var status = store().readSyncStatus().orElseThrow();
        assertThat(status.state()).isEqualTo("failed");
        assertThat(status.finishedAt()).isAfterOrEqualTo(status.startedAt());
        assertThat(status.error()).isEqualTo(SyncErrors.describe(new ImapException(ImapException.Kind.CONNECTION)));
        assertThat(status.signInRejected()).isFalse();
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
                assertThat(meta.state()).isEqualTo(MessageState.CONVERTED);
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
            @Override public Map<String, mail.mcp.source.RemoteMessageInfo> metadata(RemoteFolder f, String c, java.util.Collection<String> r) {
                return source.metadata(f, c, r);
            }
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
            new SyncRunner(store(), index, resetSource, processor()).run(progress::add);
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
            assertThatThrownBy(() -> new SyncRunner(store(), index, source, processor()).run(progress::add))
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

    @Test void downloadedMessageWithoutIndexEntryIsProcessedOnReconcile() throws Exception {
        var store = store();
        store.saveFolders(List.of(new LocalFolder("f1", "inbox", "INBOX", "7")));
        String key = store.newKey();
        var dir = store.createMessageDir(key);
        Files.write(dir.resolve(MessageStore.MESSAGE_FILE), FakeMailSource.mime("1"));
        var meta = new mail.mcp.model.MessageMeta(key, "f1", "INBOX", "1", 12, "a".repeat(64),
                Instant.now(), MessageState.DOWNLOADED, null);
        store.writeMeta(meta);
        source.putFolder("inbox", "INBOX", "7", "1");
        run();
        assertThat(source.downloadCalls).isZero();
        assertThat(store.readMeta(key).orElseThrow().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(Files.readString(dir.resolve(MessageStore.BODY_FILE))).isEqualTo("Message 1\r\n");
        assertThat(refs("f1")).containsEntry("1", key);
    }

    @Test void indexRebuildRestoresHeaderFields() throws Exception {
        source.putFolder("inbox", "INBOX", "7", "1");
        run();
        String key = refs("f1").get("1");
        Path body = store().messageDir(key).resolve(MessageStore.BODY_FILE);
        Files.writeString(body, "Saved Markdown rather than reparsed text");
        Path indexDir = connectionDir.resolve("index").toAbsolutePath().normalize();
        assertThat(indexDir.getParent()).isEqualTo(connectionDir.toAbsolutePath().normalize());
        try (var paths = Files.walk(indexDir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
        source.resetCounts();
        run();
        assertThat(source.downloadCalls).isZero();
        try (var directory = org.apache.lucene.store.FSDirectory.open(indexDir);
             var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
            var document = reader.storedFields().document(0);
            assertThat(document.get("subject")).isEqualTo("Synthetic message");
            assertThat(document.get("body")).isEqualTo(Files.readString(body));
            assertThat(document.get("state")).isEqualTo("converted");
        }
    }
    @Test void ewsRescanPreservesKnownFilesAndReportsRescanned() throws Exception {
        source.putFolder("inbox", "INBOX", "7", "1");
        run();
        String key = refs("f1").get("1");
        byte[] before = Files.readAllBytes(store().messageDir(key).resolve(MessageStore.MESSAGE_FILE));
        source.putFolder("inbox", "INBOX", "7", messageRefs(102));
        source.resetCounts();
        MailSource rescan = new MailSource() {
            @Override public Map<String, mail.mcp.source.RemoteMessageInfo> metadata(RemoteFolder f, String c, java.util.Collection<String> r) {
                return source.metadata(f, c, r);
            }
            @Override public List<RemoteFolder> folders() { return source.folders(); }
            @Override public FolderChanges changes(RemoteFolder f, String c, Set<String> known) {
                var result = source.changes(f, c, known);
                return new FolderChanges(result.added(), result.removed(), result.cursor(), false, true);
            }
            @Override public Downloaded download(RemoteFolder f, String r, Path t) { return source.download(f, r, t); }
            @Override public void close() { }
        };
        List<FolderProgress> progress = new ArrayList<>();
        try (var index = MailIndex.openWriter(connectionDir)) {
            new SyncRunner(store(), index, rescan, processor()).run(progress::add);
        }
        assertThat(progress).hasSize(2).allSatisfy(p -> {
            assertThat(p.rescanned()).isTrue();
            assertThat(p.reset()).isFalse();
        });
        assertThat(store().readSyncStatus().orElseThrow().folders().getFirst().rescanned()).isTrue();
        assertThat(refs("f1")).containsEntry("1", key);
        assertThat(Files.readAllBytes(store().messageDir(key).resolve(MessageStore.MESSAGE_FILE))).isEqualTo(before);
        assertThat(source.downloadCalls).isEqualTo(101);
    }

    @Test void uidValidityResetStillReplacesKnownMessages() {
        source.putFolder("inbox", "INBOX", "7", "1");
        run();
        String old = refs("f1").get("1");
        source.putFolder("inbox", "INBOX", "8", "1");
        var progress = run().getFirst();
        assertThat(progress.reset()).isTrue();
        assertThat(progress.rescanned()).isFalse();
        assertThat(store().messageDir(old)).doesNotExist();
        assertThat(refs("f1").get("1")).isNotEqualTo(old);
    }


    @Test void flagOnlySyncUpdatesExistingMessageWithoutDownloadOrConversion() throws Exception {
        String key = seedMove(false);
        var old = store().readMeta(key).orElseThrow();
        var before = content(key);
        source.flags("a", "1", true, false);
        assertThat(run()).allSatisfy(p -> {
            assertThat(p.downloaded()).isZero(); assertThat(p.failed()).isZero(); assertThat(p.removed()).isZero();
        });
        var updated = store().readMeta(key).orElseThrow();
        assertThat(updated.read()).isTrue();
        assertThat(updated.flagged()).isFalse();
        assertThat(updated.sha256()).isEqualTo(old.sha256());
        assertThat(updated.downloadedAt()).isEqualTo(old.downloadedAt());
        assertThat(updated.state()).isEqualTo(old.state());
        assertThat(updated.error()).isEqualTo(old.error());
        assertThat(content(key)).isEqualTo(before);
        assertThat(source.downloadCalls).isZero();
        assertThat(source.metadataCalls).isEqualTo(2);
        try (var reader = mail.mcp.index.MailIndexReader.open(connectionDir).orElseThrow()) {
            assertThat(reader.cards("f1", 0, 10).getFirst().read()).isTrue();
            assertThat(reader.cards("f1", 0, 10).getFirst().flagged()).isFalse();
        }
        var metaTime = Files.getLastModifiedTime(store().messageDir(key).resolve(MessageStore.META_FILE));
        int documentCount;
        try (var directory = org.apache.lucene.store.FSDirectory.open(connectionDir.resolve("index"));
             var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
            documentCount = reader.maxDoc();
        }
        run();
        assertThat(Files.getLastModifiedTime(store().messageDir(key).resolve(MessageStore.META_FILE))).isEqualTo(metaTime);
        try (var directory = org.apache.lucene.store.FSDirectory.open(connectionDir.resolve("index"));
             var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
            assertThat(reader.maxDoc()).isEqualTo(documentCount);
        }
        source.flags("a", "1", false, true);
        try (var index = MailIndex.openWriter(connectionDir)) {
            assertThatThrownBy(() -> new SyncRunner(store(), index, source, processor(), point -> {
                if (point.equals("metadataRefreshed")) { throw new IllegalStateException("synthetic stop"); }
            }).run(p -> { })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(store().readMeta(key).orElseThrow().read()).isFalse();
        try (var reader = mail.mcp.index.MailIndexReader.open(connectionDir).orElseThrow()) {
            assertThat(reader.cards("f1", 0, 10).getFirst().read()).isTrue();
        }
        try (var index = MailIndex.openWriter(connectionDir)) {
            SyncRunner.restore(store(), index, processor());
            assertThat(index.reconcile(store())).isEmpty();
        }
        try (var reader = mail.mcp.index.MailIndexReader.open(connectionDir).orElseThrow()) {
            assertThat(reader.cards("f1", 0, 10).getFirst().read()).isFalse();
            assertThat(reader.cards("f1", 0, 10).getFirst().flagged()).isTrue();
        }
        assertThat(content(key)).isEqualTo(before);
    }

    @Test void missingFlagDataDoesNotClearKnownValues() {
        source.putFolder("a", "A", "7", "1", "2");
        source.flags("a", "1", true, true);
        source.flags("a", "2", false, true);
        source.markTooLarge("a", "2");
        run();
        String key = refs("f1").get("1");
        var failed = store().readMeta(refs("f1").get("2")).orElseThrow();
        assertThat(failed.read()).isFalse();
        assertThat(failed.flagged()).isTrue();
        source.flags("a", "1", null, null);
        source.missingMetadata.add("a/2");
        source.resetCounts();
        run();
        assertThat(store().readMeta(key).orElseThrow().read()).isTrue();
        assertThat(store().readMeta(key).orElseThrow().flagged()).isTrue();
        assertThat(store().readMeta(failed.key())).contains(failed);
        source.flags("a", "1", false, null);
        run();
        assertThat(store().readMeta(key).orElseThrow().read()).isFalse();
        assertThat(store().readMeta(key).orElseThrow().flagged()).isTrue();
        source.flags("a", "1", null, false);
        run();
        assertThat(store().readMeta(key).orElseThrow().read()).isFalse();
        assertThat(store().readMeta(key).orElseThrow().flagged()).isFalse();
        assertThat(source.downloadCalls).isZero();
    }

    @Test void moveUsesDestinationFlagsWithoutChangingItsIdentityMatch() throws Exception {
        String key = seedMove(false);
        source.flags("a", "1", true, true);
        run();
        var before = store().readMeta(key).orElseThrow();
        var bytes = content(key);
        moveRemote();
        source.flags("b", "2", false, false);
        source.resetCounts();
        assertThat(run()).allSatisfy(p -> {
            assertThat(p.downloaded()).isZero(); assertThat(p.failed()).isZero(); assertThat(p.removed()).isZero();
        });
        var moved = store().readMeta(key).orElseThrow();
        assertThat(moved.folderKey()).isEqualTo("f2");
        assertThat(moved.ref()).isEqualTo("2");
        assertThat(moved.fingerprint()).isEqualTo(IDENTITY);
        assertThat(moved.read()).isFalse();
        assertThat(moved.flagged()).isFalse();
        assertThat(moved.downloadedAt()).isEqualTo(before.downloadedAt());
        assertThat(moved.sha256()).isEqualTo(before.sha256());
        assertThat(content(key)).isEqualTo(bytes);
        assertThat(source.downloadCalls).isZero();
    }

    @Test void uidValidityResetDoesNotInheritOldUidFlags() {
        source.putFolder("a", "A", "7", "1", "2");
        source.flags("a", "1", true, true);
        source.flags("a", "2", true, true);
        run();
        var old = store().keys();
        source.putFolder("a", "A", "8", "1", "2");
        source.flags("a", "1", null, null);
        source.flags("a", "2", false, false);
        source.resetCounts();
        assertThat(run()).containsExactly(new FolderProgress("A", 2, 0, 2, true, true));
        assertThat(store().keys()).doesNotContainAnyElementsOf(old);
        var unknown = store().readMeta(refs("f1").get("1")).orElseThrow();
        var cleared = store().readMeta(refs("f1").get("2")).orElseThrow();
        assertThat(unknown.read()).isNull(); assertThat(unknown.flagged()).isNull();
        assertThat(cleared.read()).isFalse(); assertThat(cleared.flagged()).isFalse();
        assertThat(source.downloadCalls).isEqualTo(2);
    }

}
