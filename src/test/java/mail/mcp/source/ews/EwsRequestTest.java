package mail.mcp.source.ews;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void findItemAsksForTheNewestItemsFirst() {
        RecordingTransport transport = new RecordingTransport();

        try (EwsClient client = new EwsClient(transport)) {
            client.findNewestItems(DistinguishedFolder.INBOX, 1);
        } catch (UnsupportedOperationException expected) {
            // the recording transport does not answer
        }

        assertThat(transport.lastRequest())
                .contains("<m:FindItem Traversal=\"Shallow\">")
                .contains("MaxEntriesReturned=\"1\"")
                .contains("<t:FieldOrder Order=\"Descending\"><t:FieldURI FieldURI=\"item:DateTimeReceived\"");
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
}
