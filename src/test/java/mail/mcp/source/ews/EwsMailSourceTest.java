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

    @Test void metadataIsBatchedAndMissingItemsAreOmitted() {
        var batches = new ArrayList<Integer>();
        EwsTransport transport = new EwsTransport() {
            @Override public <T> T exchange(EwsOperation op, byte[] request, ResponseHandler<T> handler) {
                String xml = new String(request, java.nio.charset.StandardCharsets.UTF_8);
                assertThat(op).isEqualTo(GET_ITEM);
                assertThat(xml).doesNotContain("IncludeMimeContent", "item:Body", "<t:Body");
                int count = (int) java.util.regex.Pattern.compile("<t:ItemId ").matcher(xml).results().count();
                batches.add(count);
                String reply = """
                        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                         xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"><s:Body>
                        <m:GetItemResponse><m:ResponseMessages>%s</m:ResponseMessages></m:GetItemResponse>
                        </s:Body></s:Envelope>
                        """.formatted(("<m:GetItemResponseMessage ResponseClass=\"Error\">"
                        + "<m:ResponseCode>ErrorItemNotFound</m:ResponseCode></m:GetItemResponseMessage>").repeat(count));
                try { return handler.handle(new java.io.ByteArrayInputStream(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
                catch (java.io.IOException | javax.xml.stream.XMLStreamException e) { throw new AssertionError(e); }
            }
            @Override public int requestCount() { return batches.size(); }
            @Override public void close() { }
        };
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            var refs = java.util.stream.IntStream.rangeClosed(1, 205).mapToObj(i -> "id" + i).toList();
            var result = source.metadata(INBOX, "opaque", refs);
            assertThat(result).isEmpty();
            assertThatThrownBy(() -> result.put("bad", null)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(source.metadata(INBOX, "opaque", List.of())).isEmpty();
        }
        assertThat(batches).containsExactly(100, 100, 5);
    }

    @Test void metadataSignInAndProtocolErrorsAbortWithoutRetry() {
        for (var error : List.of(new EwsAuthenticationException("Synthetic rejected sign-in", "NTLM"),
                new EwsResponseException(GET_ITEM, "ErrorAccessDenied", "Synthetic denied"),
                new EwsTransportException("Synthetic connection failure", null))) {
            var transport = new ScriptedEwsTransport().fail(GET_ITEM, error);
            try (var source = new EwsMailSource(new EwsClient(transport))) {
                assertThatThrownBy(() -> source.metadata(INBOX, "cursor", List.of("one", "two"))).isSameAs(error);
            }
            assertThat(transport.requestCount()).isEqualTo(1);
            transport.assertDrained();
        }
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
                .answer(GET_ITEM, "getitem-metadata-two.xml")
                .answer(GET_ITEM, "getitem-mime.xml").answer(GET_ITEM, "getitem-mime.xml");
        var store = new MessageStore(temp, new JsonConfig().mailMcpObjectMapper());
        List<FolderProgress> progress = new ArrayList<>();
        try (var index = MailIndex.openWriter(temp); var source = new EwsMailSource(new EwsClient(first))) {
            new SyncRunner(store, index, source, new mail.mcp.sync.MessageProcessor(store, new mail.mcp.convert.Pandoc("pandoc"))).run(progress::add);
            assertThat(index.folderRefs("f1")).containsOnlyKeys("AAMkSYNTH-b", "AAMkSYNTH-a");
        }
        assertThat(progress).containsExactly(new FolderProgress("Входящие", 2, 0, 0, false, true));
        assertThat(first.requests.get(3)).doesNotContain("IncludeMimeContent");
        assertThat(first.requests.get(4)).contains("Id=\"AAMkSYNTH-b\"");
        assertThat(first.requests.get(5)).contains("Id=\"AAMkSYNTH-a\"");
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
        assertThat(second.requests).noneMatch(xml -> xml.contains("IncludeMimeContent"));
        assertThat(second.requests).as("no changes: no GetItem for known items (Д29)").hasSize(3);
        assertThat(store.keys()).isEqualTo(originalKeys);
        first.assertDrained();
        second.assertDrained();
    }
    @Test void knownItemFlagsComeFromSyncChangesAndUpdatedItemsOnly() {
        var transport = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-flag-changes.xml")
                .answer(GET_ITEM, "getitem-metadata-flag.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            var changes = source.changes(INBOX, "state", Set.of("AAMkSYNTH-a", "AAMkSYNTH-b"));
            assertThat(changes.added()).isEmpty();
            assertThat(changes.removed()).isEmpty();
            assertThat(changes.observed()).containsOnlyKeys("AAMkSYNTH-a", "AAMkSYNTH-b");
            var updated = changes.observed().get("AAMkSYNTH-a");
            assertThat(updated.fingerprint()).isNull();
            assertThat(updated.read()).isTrue();
            assertThat(updated.flagged()).isTrue();
            var readOnly = changes.observed().get("AAMkSYNTH-b");
            assertThat(readOnly.read()).as("the later ReadFlagChange wins").isFalse();
            assertThat(readOnly.flagged()).isNull();
        }
        transport.assertDrained();
        assertThat(transport.requests.get(1)).contains("Id=\"AAMkSYNTH-a\"").doesNotContain("AAMkSYNTH-b");
    }

    @Test void invalidSyncStateReportsRescanWithoutResetOrKnownDownloads() {
        var transport = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-invalid-state.xml")
                .answer(SYNC_FOLDER_ITEMS, "sync-changes.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            var changes = source.changes(INBOX, "invalid", Set.of("AAMkSYNTH-a"));
            assertThat(changes.rescanned()).isTrue();
            assertThat(changes.reset()).isFalse();
            assertThat(changes.added()).containsExactly("AAMkSYNTH-b");
        }
        transport.assertDrained();
    }

    @Test void initialFullSyncIsNotARescan() {
        var transport = new ScriptedEwsTransport().answer(SYNC_FOLDER_ITEMS, "sync-changes.xml");
        try (var source = new EwsMailSource(new EwsClient(transport))) {
            assertThat(source.changes(INBOX, null, Set.of()).rescanned()).isFalse();
        }
    }

}
