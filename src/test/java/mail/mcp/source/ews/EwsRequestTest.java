package mail.mcp.source.ews;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Request envelopes and the read-only allow-list. No network. */
class EwsRequestTest {

    /** Write operations named in research RT-03 (B10). None may ever be in the allow-list. */
    private static final List<String> WRITE_OPERATIONS = List.of(
            "CreateItem", "UpdateItem", "DeleteItem", "MoveItem", "CopyItem", "SendItem", "ArchiveItem",
            "MarkAllItemsAsRead", "MarkAsJunk", "CreateAttachment", "DeleteAttachment", "CreateFolder",
            "CreateFolderPath", "CreateManagedFolder", "UpdateFolder", "DeleteFolder", "EmptyFolder",
            "MoveFolder", "CopyFolder", "ApplyConversationAction", "UpdateInboxRules", "SetUserOofSettings",
            "AddDelegate", "UpdateDelegate", "RemoveDelegate", "CreateUserConfiguration",
            "UpdateUserConfiguration", "DeleteUserConfiguration", "UploadItems", "Subscribe", "Unsubscribe");

    @Test
    void allowListHasExactlyTheTenReadOperations() {
        assertThat(Arrays.stream(EwsOperation.values()).map(EwsOperation::elementName))
                .containsExactlyInAnyOrder("GetFolder", "FindFolder", "SyncFolderHierarchy", "FindItem",
                        "SyncFolderItems", "GetItem", "GetAttachment", "FindConversation",
                        "GetConversationItems", "ConvertId")
                .doesNotContainAnyElementsOf(WRITE_OPERATIONS);
    }

    @Test
    void soapActionIsTheMessagesNamespacePlusTheOperation() {
        assertThat(EwsOperation.GET_ITEM.soapAction())
                .isEqualTo("http://schemas.microsoft.com/exchange/services/2006/messages/GetItem");
    }

    @Test
    void envelopeCarriesTheExchange2013ServerVersion() {
        RecordingTransport transport = new RecordingTransport();

        try (EwsClient client = new EwsClient(transport)) {
            client.getFolder(DistinguishedFolder.INBOX);
        } catch (UnsupportedOperationException expected) {
            // the recording transport does not answer
        }

        String xml = transport.lastRequest();
        assertThat(transport.lastOperation).isEqualTo(EwsOperation.GET_FOLDER);
        assertThat(xml).contains("<t:RequestServerVersion Version=\"Exchange2013_SP1\"")
                .contains("<m:GetFolder>")
                .contains("<t:BaseShape>Default</t:BaseShape>")
                .contains("<t:DistinguishedFolderId Id=\"inbox\"");
    }

    @Test
    void getItemRequestsMimeAndEscapesTheItemId(@TempDir Path dir) {
        RecordingTransport transport = new RecordingTransport();

        try (EwsClient client = new EwsClient(transport)) {
            client.getItemMime("id\"with<markup>&", dir.resolve("unused.eml"));
        } catch (UnsupportedOperationException expected) {
            // the recording transport does not answer
        }

        assertThat(transport.lastRequest())
                .contains("<t:IncludeMimeContent>true</t:IncludeMimeContent>")
                .contains("Id=\"id&quot;with&lt;markup&gt;&amp;\"");
        assertThat(dir).isEmptyDirectory(); // the partial file of a failed call is removed
    }

    @Test
    void findFolderAsksForADeepPagedListingOfTheMessageRoot() {
        var transport = new ScriptedEwsTransport().answer(EwsOperation.FIND_FOLDER, "findfolder-empty.xml");
        try (var client = new EwsClient(transport)) {
            client.findFolders(DistinguishedFolder.MSG_FOLDER_ROOT, 7);
        }
        String xml = transport.requests.getFirst();
        assertThat(xml).contains("<m:FindFolder Traversal=\"Deep\">",
                "<t:BaseShape>IdOnly</t:BaseShape>", "FieldURI=\"folder:DisplayName\"",
                "FieldURI=\"folder:FolderClass\"", "FieldURI=\"folder:ParentFolderId\"",
                "<m:IndexedPageFolderView MaxEntriesReturned=\"1000\" Offset=\"7\" BasePoint=\"Beginning\"",
                "<t:DistinguishedFolderId Id=\"msgfolderroot\"");
        assertThat(xml.indexOf("<m:FolderShape>")).isLessThan(xml.indexOf("<m:IndexedPageFolderView"));
        assertThat(xml.indexOf("<m:IndexedPageFolderView")).isLessThan(xml.indexOf("<m:ParentFolderIds>"));
    }

    @Test
    void syncFolderItemsOmitsTheSyncStateOnTheFirstCall() {
        var transport = new ScriptedEwsTransport().answer(EwsOperation.SYNC_FOLDER_ITEMS, "sync-empty.xml");
        try (var client = new EwsClient(transport)) {
            client.syncFolderItems("AAMkSYNTH-inbox", null);
        }
        String xml = transport.requests.getFirst();
        assertThat(xml).doesNotContain("SyncState").contains("<t:BaseShape>IdOnly</t:BaseShape>",
                "<m:SyncFolderId><t:FolderId Id=\"AAMkSYNTH-inbox\"",
                "<m:MaxChangesReturned>512</m:MaxChangesReturned>", "<m:SyncScope>NormalItems</m:SyncScope>");
        assertThat(xml.indexOf("<m:ItemShape>")).isLessThan(xml.indexOf("<m:SyncFolderId>"));
        assertThat(xml.indexOf("<m:SyncFolderId>")).isLessThan(xml.indexOf("<m:MaxChangesReturned>"));
        assertThat(xml.indexOf("<m:MaxChangesReturned>")).isLessThan(xml.indexOf("<m:SyncScope>"));
    }

    @Test
    void syncFolderItemsSendsTheSyncStateAndFolderIdEscaped() {
        var transport = new ScriptedEwsTransport().answer(EwsOperation.SYNC_FOLDER_ITEMS, "sync-empty.xml");
        try (var client = new EwsClient(transport)) {
            client.syncFolderItems("id\"<>&", " state<>& ");
        }
        String xml = transport.requests.getFirst();
        assertThat(xml).contains("Id=\"id&quot;&lt;&gt;&amp;\"", "<m:SyncState> state&lt;&gt;&amp; </m:SyncState>");
        assertThat(xml.indexOf("<m:SyncFolderId>")).isLessThan(xml.indexOf("<m:SyncState>"));
        assertThat(xml.indexOf("<m:SyncState>")).isLessThan(xml.indexOf("<m:MaxChangesReturned>"));
    }

    @Test
    void folderIdsUsesOneIdOnlyRequestAndRejectsADifferentCount() {
        var transport = new ScriptedEwsTransport().answer(EwsOperation.GET_FOLDER, "getfolder-excluded.xml")
                .answer(EwsOperation.GET_FOLDER, "getfolder-excluded.xml");
        try (var client = new EwsClient(transport)) {
            assertThat(client.folderIds(List.of(DistinguishedFolder.DELETED_ITEMS, DistinguishedFolder.JUNK_EMAIL,
                    DistinguishedFolder.DRAFTS, DistinguishedFolder.OUTBOX))).containsExactly(
                    "AAMkSYNTH-deleted", "AAMkSYNTH-junk", "AAMkSYNTH-drafts", "AAMkSYNTH-outbox");
            assertThatThrownBy(() -> client.folderIds(List.of(DistinguishedFolder.INBOX)))
                    .isInstanceOf(EwsProtocolException.class);
        }
        String xml = transport.requests.getFirst();
        assertThat(xml).contains("<t:BaseShape>IdOnly</t:BaseShape>").doesNotContain("AdditionalProperties");
        assertThat(xml.indexOf("Id=\"deleteditems\"")).isLessThan(xml.indexOf("Id=\"junkemail\""));
        assertThat(xml.indexOf("Id=\"junkemail\"")).isLessThan(xml.indexOf("Id=\"drafts\""));
        assertThat(xml.indexOf("Id=\"drafts\"")).isLessThan(xml.indexOf("Id=\"outbox\""));
    }

    @Test void metadataRequestOmitsMimeAndBody() {
        var transport = new RecordingTransport();
        try (var client = new EwsClient(transport)) {
            assertThatThrownBy(() -> client.metadata(List.of("id\"<>&", "second")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(transport.lastOperation).isEqualTo(EwsOperation.GET_ITEM);
        assertThat(transport.lastRequest()).contains("<t:BaseShape>IdOnly</t:BaseShape>",
                "FieldURI=\"message:InternetMessageId\"", "FieldURI=\"item:Size\"",
                "FieldURI=\"item:DateTimeSent\"", "FieldURI=\"message:From\"", "FieldURI=\"item:Subject\"",
                "Id=\"id&quot;&lt;&gt;&amp;\"", "Id=\"second\"")
                .doesNotContain("IncludeMimeContent", "item:Body", "<t:Body", "InternetMessageHeaders");
    }

    /** Captures the request and refuses to answer. */
    private static final class RecordingTransport implements EwsTransport {
        private byte[] lastEnvelope;
        private EwsOperation lastOperation;

        @Override
        public <T> T exchange(EwsOperation operation, byte[] envelope, ResponseHandler<T> handler) {
            lastOperation = operation;
            lastEnvelope = envelope;
            throw new UnsupportedOperationException("recording only");
        }

        String lastRequest() {
            return new String(lastEnvelope, StandardCharsets.UTF_8);
        }

        @Override
        public int requestCount() {
            return 0;
        }

        @Override
        public void close() {
        }
    }

    @Test void metadataRequestsReadAndFlagWithoutMime() {
        var transport = new RecordingTransport();
        try (var client = new EwsClient(transport)) {
            assertThatThrownBy(() -> client.metadata(List.of("item"))).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(transport.lastRequest()).contains("FieldURI=\"message:IsRead\"", "FieldURI=\"item:Flag\"")
                .doesNotContain("MimeContent", "item:Body", "Importance");
    }

}
