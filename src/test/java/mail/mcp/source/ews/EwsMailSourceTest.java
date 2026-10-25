package mail.mcp.source.ews;

import mail.mcp.config.JsonConfig;
import mail.mcp.index.MailIndex;
import mail.mcp.source.MessageUnavailableException;
import mail.mcp.source.RemoteFolder;
import mail.mcp.store.MessageStore;
import mail.mcp.model.FolderProgress;
import mail.mcp.sync.SyncRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static mail.mcp.source.ews.EwsOperation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offline EWS sessions and synchronization over synthetic response queues. */
class EwsMailSourceTest {
    @TempDir Path temp;
    private static final RemoteFolder INBOX = new RemoteFolder("AAMkSYNTH-inbox", "Входящие");

    @Test void foldersKeepMailFoldersOnly() {
        var transport = new ScriptedEwsTransport().answer(GET_FOLDER, "getfolder-excluded.xml")
                .answer(FIND_FOLDER, "findfolder-all.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            assertThat(source.folders()).containsExactly(INBOX,
                    new RemoteFolder("AAMkSYNTH-projects", "Входящие/Проекты"),
                    new RemoteFolder("AAMkSYNTH-calendar-mail", "Календарь/Архив"));
        }
        transport.assertDrained();
        assertThat(transport.closed).isTrue();
    }

    @Test void foldersBuildFullPathsAndPageTheListing() {
        var transport = new ScriptedEwsTransport().answer(GET_FOLDER, "getfolder-excluded.xml")
                .answer(FIND_FOLDER, "findfolder-page-1.xml").answer(FIND_FOLDER, "findfolder-page-2.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            assertThat(source.folders()).containsExactly(INBOX,
                    new RemoteFolder("AAMkSYNTH-projects", "Входящие/Проекты"),
                    new RemoteFolder("AAMkSYNTH-letters", "Входящие/Проекты/Письма"),
                    new RemoteFolder("AAMkSYNTH-group-mail", "Группа/Архив"));
        }
        assertThat(transport.requests.get(1)).contains("Offset=\"0\"");
        assertThat(transport.requests.get(2)).contains("Offset=\"2\"");
        transport.assertDrained();
    }

    @Test void emptyFolderPageEndsTheListing() {
        var transport = new ScriptedEwsTransport().answer(GET_FOLDER, "getfolder-excluded.xml")
                .answer(FIND_FOLDER, "findfolder-empty.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            assertThat(source.folders()).isEmpty();
        }
        assertThat(transport.requestCount()).isEqualTo(2);
    }

    @Test void firstSyncPagesUntilTheLastItemInRange() {
        var transport = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-first-1.xml")
                .answer(SYNC_FOLDER_ITEMS, "sync-first-2.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            var changes = source.changes(INBOX, null, Set.of());
            assertThat(changes.added()).containsExactly("AAMkSYNTH-b", "AAMkSYNTH-a", "AAMkSYNTH-c");
            assertThat(changes.removed()).isEmpty();
            assertThat(changes.cursor()).isEqualTo("state-page-2");
            assertThat(changes.reset()).isFalse();
        }
        assertThat(transport.requests.getFirst()).doesNotContain("SyncState");
        assertThat(transport.requests.get(1)).contains("<m:SyncState>state-page-1</m:SyncState>");
        transport.assertDrained();
    }

    @Test void firstSyncSkipsKnownAndRemovesUnlisted() {
        var transport = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-first-1.xml")
                .answer(SYNC_FOLDER_ITEMS, "sync-first-2.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            var changes = source.changes(INBOX, null, Set.of("AAMkSYNTH-a", "z-unlisted", "a-unlisted"));
            assertThat(changes.added()).containsExactly("AAMkSYNTH-b", "AAMkSYNTH-c");
            assertThat(changes.removed()).containsExactly("a-unlisted", "z-unlisted");
            assertThat(changes.reset()).isFalse();
        }
    }

    @Test void laterSyncTurnsDeletesIntoRemovedRefs() {
        var transport = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-later-1.xml")
                .answer(SYNC_FOLDER_ITEMS, "sync-later-2.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            var changes = source.changes(INBOX, "old-state",
                    Set.of("AAMkSYNTH-z-known", "AAMkSYNTH-a-known", "AAMkSYNTH-still-here"));
            assertThat(changes.added()).containsExactly("AAMkSYNTH-new-b", "AAMkSYNTH-new-a");
            assertThat(changes.removed()).containsExactly("AAMkSYNTH-a-known", "AAMkSYNTH-z-known");
            assertThat(changes.cursor()).isEqualTo("state-final");
            assertThat(changes.reset()).isFalse();
        }
        assertThat(transport.requests.getFirst()).contains("<m:SyncState>old-state</m:SyncState>");
    }

    @Test void invalidSyncStateStartsOverWithoutReset() {
        var transport = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-invalid-state.xml")
                .answer(SYNC_FOLDER_ITEMS, "sync-changes.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            var changes = source.changes(INBOX, "invalid-state", Set.of("AAMkSYNTH-a", "AAMkSYNTH-gone"));
            assertThat(changes.added()).containsExactly("AAMkSYNTH-b");
            assertThat(changes.removed()).containsExactly("AAMkSYNTH-gone");
            assertThat(changes.cursor()).isEqualTo("state-final");
            assertThat(changes.reset()).isFalse();
        }
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.getFirst()).contains("<m:SyncState>invalid-state</m:SyncState>");
        assertThat(transport.requests.get(1)).doesNotContain("SyncState");
        transport.assertDrained();
    }

    @Test void invalidSyncStateOnALaterPageOrWithoutCursorPropagates() {
        var later = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-first-1.xml")
                .answer(SYNC_FOLDER_ITEMS, "sync-invalid-state.xml");
        try (var source = new EwsMailSource(new EwsClient(later))) {
            assertThatThrownBy(() -> source.changes(INBOX, "old-state", Set.of()))
                    .isInstanceOf(EwsResponseException.class);
        }
        assertThat(later.requestCount()).isEqualTo(2);
        var first = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-invalid-state.xml");
        try (var source = new EwsMailSource(new EwsClient(first))) {
            assertThatThrownBy(() -> source.changes(INBOX, null, Set.of()))
                    .isInstanceOf(EwsResponseException.class);
        }
        assertThat(first.requestCount()).isEqualTo(1);
        var twice = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-invalid-state.xml")
                .answer(SYNC_FOLDER_ITEMS, "sync-invalid-state.xml");
        try (var source = new EwsMailSource(new EwsClient(twice))) {
            assertThatThrownBy(() -> source.changes(INBOX, "invalid-state", Set.of()))
                    .isInstanceOf(EwsResponseException.class);
        }
        assertThat(twice.requestCount()).isEqualTo(2);
    }

    @Test void downloadWritesMimeAndReturnsSizeAndHash() throws Exception {
        var transport = new ScriptedEwsTransport().answer(GET_ITEM, "getitem-mime.xml");
        Path target = temp.resolve("message.eml");
        byte[] expected = EwsResponseReaderTest.fixtureBytes("sample.eml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            var result = source.download(INBOX, "AAMkSYNTH-a", target);
            assertThat(Files.readAllBytes(target)).isEqualTo(expected);
            assertThat(result.size()).isEqualTo(expected.length);
            assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(expected)));
        }
        assertThat(temp.resolve("message.eml.part")).doesNotExist();
    }

    @Test void missingItemIsGone() {
        var transport = new ScriptedEwsTransport().answer(GET_ITEM, "getitem-error-not-found.xml")
                .answer(GET_ITEM, "getitem-mime.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            assertThatThrownBy(() -> source.download(INBOX, "AAMkSYNTH-gone", temp.resolve("gone.eml")))
                    .isInstanceOfSatisfying(MessageUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(MessageUnavailableException.Reason.GONE));
            assertThat(temp).isEmptyDirectory();
            source.download(INBOX, "AAMkSYNTH-a", temp.resolve("present.eml"));
        }
        transport.assertDrained();
    }

    @Test void tooLargeItemIsUnavailable() {
        var transport = new ScriptedEwsTransport().answer(GET_ITEM, "getitem-mime.xml")
                .answer(SYNC_FOLDER_ITEMS, "sync-empty.xml");
        try (var source = new EwsMailSource(new EwsClient(transport, 100))) {
            assertThatThrownBy(() -> source.download(INBOX, "AAMkSYNTH-big", temp.resolve("large.eml")))
                    .isInstanceOfSatisfying(MessageUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(MessageUnavailableException.Reason.TOO_LARGE));
            assertThat(temp).isEmptyDirectory();
            assertThat(source.changes(INBOX, "previous", Set.of()).added()).isEmpty();
        }
    }

    @Test void blankRefIsRejectedBeforeAnyRequest() {
        var transport = new ScriptedEwsTransport();
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            assertThatThrownBy(() -> source.download(INBOX, " ", temp.resolve("unused.eml")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> source.download(INBOX, null, temp.resolve("unused.eml")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(transport.requests).isEmpty();
        assertThat(temp).isEmptyDirectory();
    }

    @Test void signInFailureIsNotRetried() {
        var failure = new EwsAuthenticationException("Synthetic rejected sign-in", "NTLM");
        var transport = new ScriptedEwsTransport().fail(SYNC_FOLDER_ITEMS, failure);
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            assertThatThrownBy(() -> source.changes(INBOX, null, Set.of())).isSameAs(failure);
        }
        assertThat(transport.requestCount()).isEqualTo(1);
        transport.assertDrained();
    }

    @Test void otherEwsFailuresPropagateWithoutRetries() {
        for (var failure : List.of(
                new EwsSoapFaultException(SYNC_FOLDER_ITEMS, "s:Server", "Synthetic busy", "ErrorServerBusy"),
                new EwsHttpException(503, "Synthetic unavailable"),
                new EwsTransportException("Synthetic transport failure", null),
                new EwsResponseException(SYNC_FOLDER_ITEMS, "ErrorAccessDenied", "Synthetic denied"))) {
            var transport = new ScriptedEwsTransport().fail(SYNC_FOLDER_ITEMS, failure);
            try (var source = new EwsMailSource(new EwsClient(transport))) {
                assertThatThrownBy(() -> source.changes(INBOX, "previous", Set.of())).isSameAs(failure);
            }
            assertThat(transport.requestCount()).isEqualTo(1);
        }
        var failure = new EwsResponseException(GET_ITEM, "ErrorAccessDenied", "Synthetic denied");
        var transport = new ScriptedEwsTransport().fail(GET_ITEM, failure);
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            assertThatThrownBy(() -> source.download(INBOX, "AAMkSYNTH-a", temp.resolve("denied.eml")))
                    .isSameAs(failure);
        }
        assertThat(temp).isEmptyDirectory();
    }

    @Test void twoSyncRunsDownloadEachMessageOnce() throws Exception {
        var first = new ScriptedEwsTransport().answer(GET_FOLDER, "getfolder-excluded.xml")
                .answer(FIND_FOLDER, "findfolder-inbox.xml").answer(SYNC_FOLDER_ITEMS, "sync-changes.xml")
                .answer(GET_ITEM, "getitem-mime.xml").answer(GET_ITEM, "getitem-mime.xml");
        var store = new MessageStore(temp, new JsonConfig().mailMcpObjectMapper());
        List<FolderProgress> progress = new ArrayList<>();
        try (var index = MailIndex.openWriter(temp); var source = new EwsMailSource(new EwsClient(first))) {
            new SyncRunner(store, index, source, new mail.mcp.sync.MessageProcessor(store, new mail.mcp.convert.Pandoc("pandoc"))).run(progress::add);
            assertThat(index.folderRefs("f1")).containsOnlyKeys("AAMkSYNTH-b", "AAMkSYNTH-a");
        }
        assertThat(progress).containsExactly(new FolderProgress("Входящие", 2, 0, 0, false, true));
        assertThat(first.requests.get(3)).contains("Id=\"AAMkSYNTH-b\"");
        assertThat(first.requests.get(4)).contains("Id=\"AAMkSYNTH-a\"");
        assertThat(store.folders().getFirst().cursor()).isEqualTo("state-final");
        var originalKeys = store.keys();
        byte[] expected = EwsResponseReaderTest.fixtureBytes("sample.eml");
        for (String key : originalKeys) {
            assertThat(Files.readAllBytes(store.messageDir(key).resolve(MessageStore.MESSAGE_FILE))).isEqualTo(expected);
        }
        var second = new ScriptedEwsTransport().answer(GET_FOLDER, "getfolder-excluded.xml")
                .answer(FIND_FOLDER, "findfolder-inbox.xml").answer(SYNC_FOLDER_ITEMS, "sync-empty.xml");
        progress.clear();
        try (var index = MailIndex.openWriter(temp); var source = new EwsMailSource(new EwsClient(second))) {
            new SyncRunner(store, index, source, new mail.mcp.sync.MessageProcessor(store, new mail.mcp.convert.Pandoc("pandoc"))).run(progress::add);
            assertThat(index.folderRefs("f1")).containsOnlyKeys("AAMkSYNTH-b", "AAMkSYNTH-a");
        }
        assertThat(progress).containsExactly(new FolderProgress("Входящие", 0, 0, 0, false, true));
        assertThat(second.requests.get(2)).contains("<m:SyncState>state-final</m:SyncState>");
        assertThat(second.operations).doesNotContain(GET_ITEM);
        assertThat(store.keys()).isEqualTo(originalKeys);
        first.assertDrained();
        second.assertDrained();
    }
}
