package mail.mcp.source.ews;

import mail.mcp.source.MessageUnavailableException;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parses synthetic EWS responses from {@code src/test/resources/ews/}. No network. */
class EwsResponseReaderTest {

    static InputStream fixture(String name) {
        InputStream in = EwsResponseReaderTest.class.getResourceAsStream("/ews/" + name);
        assertThat(in).as("fixture " + name).isNotNull();
        return in;
    }

    static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream in = fixture(name)) {
            return in.readAllBytes();
        }
    }

    private static InputStream metadataXml(String responses) {
        String xml = """
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                 xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                 xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"><s:Body>
                <m:GetItemResponse><m:ResponseMessages>%s</m:ResponseMessages></m:GetItemResponse>
                </s:Body></s:Envelope>
                """.formatted(responses);
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String metadataItem(String id) {
        return """
                <m:GetItemResponseMessage ResponseClass="Success"><m:ResponseCode>NoError</m:ResponseCode>
                <m:Items><t:Message><t:ItemId Id="%s"/><t:Subject>Тема &amp; text</t:Subject>
                <t:Size>987654</t:Size><t:DateTimeSent>2026-10-25T12:00:00Z</t:DateTimeSent>
                <t:InternetMessageId> &lt;Case@example.com&gt; </t:InternetMessageId>
                <t:From><t:Mailbox><t:Name>Display name</t:Name><t:EmailAddress> Sender@example.com </t:EmailAddress>
                </t:Mailbox></t:From></t:Message></m:Items></m:GetItemResponseMessage>
                """.formatted(id);
    }

    @Test void metadataBatchHandlesMissingItemsWithoutRefMisassociation() throws Exception {
        String gone = """
                <m:GetItemResponseMessage ResponseClass="Error"><m:ResponseCode>ErrorItemNotFound</m:ResponseCode>
                </m:GetItemResponseMessage>
                """;
        var items = EwsResponseReader.readMetadata(metadataXml(gone + metadataItem("second") + metadataItem("third")),
                List.of("gone", "second", "third"));
        assertThat(items).containsOnlyKeys("second", "third");
        assertThat(items.get("second").ref()).isEqualTo("second");
        assertThat(items.get("second").fingerprint()).isEqualTo(new mail.mcp.model.MessageFingerprint(
                "<Case@example.com>", 987654L, java.time.Instant.parse("2026-10-25T12:00:00Z"), "Sender@example.com", "Тема & text"));
        assertThat(items.get("third").fingerprint().complete()).isTrue();
        assertThatThrownBy(() -> items.clear()).isInstanceOf(UnsupportedOperationException.class);
        var reversed = EwsResponseReader.readMetadata(metadataXml(metadataItem("third") + gone + metadataItem("second")),
                List.of("gone", "second", "third"));
        assertThat(reversed).isEqualTo(items);
        assertThatThrownBy(() -> EwsResponseReader.readMetadata(metadataXml(metadataItem("second") + metadataItem("second")),
                List.of("first", "second"))).isInstanceOf(EwsProtocolException.class);
        assertThatThrownBy(() -> EwsResponseReader.readMetadata(metadataXml(metadataItem("unexpected")), List.of("first")))
                .isInstanceOf(EwsProtocolException.class);
        assertThatThrownBy(() -> EwsResponseReader.readMetadata(metadataXml(gone), List.of("first", "second")))
                .isInstanceOf(EwsProtocolException.class);
        assertThatThrownBy(() -> EwsResponseReader.readMetadata(metadataXml(gone.replace("ErrorItemNotFound", "ErrorAccessDenied")), List.of("first")))
                .isInstanceOf(EwsResponseException.class);
        assertThatThrownBy(() -> EwsResponseReader.readMetadata(fixture("xxe.xml"), List.of("first")))
                .isInstanceOfAny(XMLStreamException.class, EwsProtocolException.class);
        assertThatThrownBy(() -> EwsResponseReader.readMetadata(fixture("fault-server-busy.xml"), List.of("first")))
                .isInstanceOf(EwsSoapFaultException.class);
        assertThatThrownBy(() -> EwsResponseReader.readMetadata(fixture("no-response-message.xml"), List.of("first")))
                .isInstanceOf(EwsProtocolException.class);
    }

    @Test
    void getFolderReadsCountsAndCyrillicDisplayName() throws Exception {
        EwsFolder inbox = EwsResponseReader.readFolder(fixture("getfolder-inbox.xml"));

        assertThat(inbox.id()).isEqualTo("AAMkAD-synthetic-inbox=");
        assertThat(inbox.displayName()).isEqualTo("Входящие");
        assertThat(inbox.folderClass()).isEqualTo("IPF.Note");
        assertThat(inbox.totalCount()).isEqualTo(1987);
        assertThat(inbox.unreadCount()).isEqualTo(691);
        assertThat(inbox.childFolderCount()).isEqualTo(3);
    }

    @Test
    void warningResponseStillReturnsThePayload() throws Exception {
        EwsFolder inbox = EwsResponseReader.readFolder(fixture("getfolder-warning.xml"));

        assertThat(inbox.displayName()).isEqualTo("Inbox");
        assertThat(inbox.folderClass()).isNull();
        assertThat(inbox.totalCount()).isEqualTo(5);
    }

    @Test
    void getItemDecodesWrappedBase64MimeByteForByte() throws Exception {
        ByteArrayOutputStream mime = new ByteArrayOutputStream();

        EwsResponseReader.MimeFields fields =
                EwsResponseReader.readMime(fixture("getitem-mime.xml"), mime, 1024 * 1024);

        byte[] expected = fixtureBytes("sample.eml");
        assertThat(mime.toByteArray()).isEqualTo(expected);
        assertThat(fields.bytes).isEqualTo(expected.length);
        assertThat(fields.itemId).isEqualTo("AAMkAD-synthetic-item-1=");
        assertThat(fields.read).isFalse();
    }

    @Test
    void mimeLargerThanTheLimitIsRejected() {
        assertThatThrownBy(() -> EwsResponseReader.readMime(fixture("getitem-mime.xml"),
                new ByteArrayOutputStream(), 100))
                .isInstanceOfSatisfying(MessageUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(MessageUnavailableException.Reason.TOO_LARGE));
    }

    @Test
    void getFolderReadsSeveralFolderIdsInOrder() throws Exception {
        assertThat(EwsResponseReader.readFolderIds(fixture("getfolder-excluded.xml"))).containsExactly(
                "AAMkSYNTH-deleted", "AAMkSYNTH-junk", "AAMkSYNTH-drafts", "AAMkSYNTH-outbox");
    }

    @Test
    void findFolderReadsEveryFolderKindWithParentAndClass() throws Exception {
        var page = EwsResponseReader.readFolderPage(fixture("findfolder-all.xml"));
        assertThat(page.includesLastItemInRange()).isTrue();
        assertThat(page.folders()).hasSize(16).contains(
                new EwsFolderNode("AAMkSYNTH-inbox", "AAMkSYNTH-root", "Входящие", "IPF.Note", "Folder"),
                new EwsFolderNode("AAMkSYNTH-projects", "AAMkSYNTH-inbox", "Проекты", "IPF.Note.X", "Folder"),
                new EwsFolderNode("AAMkSYNTH-calendar", "AAMkSYNTH-root", "Календарь", null, "CalendarFolder"),
                new EwsFolderNode("AAMkSYNTH-contacts", "AAMkSYNTH-root", "Контакты", "IPF.Contact", "ContactsFolder"),
                new EwsFolderNode("AAMkSYNTH-tasks", "AAMkSYNTH-root", "Задачи", "IPF.Task", "TasksFolder"),
                new EwsFolderNode("AAMkSYNTH-search", "AAMkSYNTH-root", "Поиск", "IPF.Note", "SearchFolder"));
    }

    @Test
    void syncFolderItemsReadsCreatesDeletesUpdatesAndReadFlags() throws Exception {
        var page = EwsResponseReader.readSyncPage(fixture("sync-changes.xml"));
        assertThat(page.created()).containsExactly("AAMkSYNTH-b", "AAMkSYNTH-a");
        assertThat(page.deleted()).containsExactly("AAMkSYNTH-gone");
        assertThat(page.updated()).containsExactly("ignored-update");
        assertThat(page.readFlags()).containsExactly(java.util.Map.entry("ignored-read", true));
        assertThat(page.syncState()).isEqualTo("state-final");
        assertThat(page.includesLastItemInRange()).isTrue();
    }

    @Test
    void syncFolderItemsWithoutSyncStateIsAProtocolError() {
        assertThatThrownBy(() -> EwsResponseReader.readSyncPage(fixture("sync-no-state.xml")))
                .isInstanceOf(EwsProtocolException.class).hasMessageContaining("no SyncState");
    }

    @Test
    void syncFolderItemsWithoutChangeIdIsAProtocolError() {
        for (String name : new String[]{"sync-create-no-id.xml", "sync-delete-no-id.xml"}) {
            assertThatThrownBy(() -> EwsResponseReader.readSyncPage(fixture(name)))
                    .isInstanceOf(EwsProtocolException.class).hasMessageContaining("no ItemId Id");
        }
    }

    @Test
    void invalidSyncStateBecomesAResponseExceptionWithItsCode() {
        assertThatThrownBy(() -> EwsResponseReader.readSyncPage(fixture("sync-invalid-state.xml")))
                .isInstanceOfSatisfying(EwsResponseException.class,
                        e -> assertThat(e.responseCode()).isEqualTo("ErrorInvalidSyncStateData"));
    }

    @Test
    void mimeSplitAcrossManyTextChunksIsDecoded() throws Exception {
        // ~200 KB of MIME: larger than one decoder buffer and than one StAX text chunk.
        byte[] large = new byte[200_000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) ('a' + i % 26);
        }
        String base64 = Base64.getMimeEncoder().encodeToString(large);
        String xml = """
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                <m:GetItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages"
                    xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"><m:ResponseMessages>
                <m:GetItemResponseMessage ResponseClass="Success"><m:ResponseCode>NoError</m:ResponseCode>
                <m:Items><t:Message><t:MimeContent CharacterSet="UTF-8">%s</t:MimeContent>
                <t:ItemId Id="big" ChangeKey="k"/></t:Message></m:Items>
                </m:GetItemResponseMessage></m:ResponseMessages></m:GetItemResponse></s:Body></s:Envelope>
                """.formatted(base64);
        ByteArrayOutputStream mime = new ByteArrayOutputStream();

        EwsResponseReader.readMime(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), mime, 1_000_000);

        assertThat(mime.toByteArray()).isEqualTo(large);
    }

    @Test
    void errorResponseClassBecomesAnExceptionWithTheResponseCode() {
        assertThatThrownBy(() -> EwsResponseReader.readMime(fixture("getitem-error-not-found.xml"),
                new ByteArrayOutputStream(), 1024))
                .isInstanceOf(EwsResponseException.class)
                .hasMessage("GetItem failed: ErrorItemNotFound (The specified object was not found in the store.)")
                .extracting(e -> ((EwsResponseException) e).responseCode())
                .isEqualTo("ErrorItemNotFound");
    }

    @Test
    void soapFaultBecomesAnExceptionWithTheEwsCode() {
        assertThatThrownBy(() -> EwsResponseReader.readFolder(fixture("fault-server-busy.xml")))
                .isInstanceOf(EwsSoapFaultException.class)
                .hasMessageStartingWith("GetFolder failed with a SOAP fault: ErrorServerBusy")
                .extracting(e -> ((EwsSoapFaultException) e).responseCode())
                .isEqualTo("ErrorServerBusy");
    }

    @Test
    void responseWithoutAResponseMessageIsAProtocolError() {
        assertThatThrownBy(() -> EwsResponseReader.readFolder(fixture("no-response-message.xml")))
                .isInstanceOf(EwsProtocolException.class)
                .hasMessageContaining("no response message");
    }

    @Test
    void externalEntitiesAreNotResolved() {
        assertThatThrownBy(() -> EwsResponseReader.readFolder(fixture("xxe.xml")))
                .isInstanceOf(XMLStreamException.class);
    }

    @Test
    void base64DecoderRejectsTruncatedInput() {
        Base64ChunkDecoder decoder = new Base64ChunkDecoder(new ByteArrayOutputStream(), 100);

        assertThatThrownBy(() -> {
            decoder.write("QUJD\nRA".toCharArray(), 0, 7);
            decoder.finish();
        }).isInstanceOf(EwsProtocolException.class).hasMessageContaining("truncated");
    }

    @Test void metadataReadsFlaggedCompleteNotFlaggedAndUnknown() throws Exception {
        String[] statuses = {"Flagged", "Complete", "NotFlagged", "Unexpected", ""};
        Boolean[] expected = {true, false, false, null, null};
        for (int i = 0; i < statuses.length; i++) {
            String flags = "<t:IsRead>" + (i == 0 ? "true" : i == 1 ? "false" : "unknown") + "</t:IsRead>"
                    + "<t:Flag><t:FlagStatus>" + statuses[i] + "</t:FlagStatus></t:Flag><t:Importance>High</t:Importance>";
            var info = EwsResponseReader.readMetadata(metadataXml(metadataItem("one")
                    .replace("</t:Message>", flags + "</t:Message>")), List.of("one")).get("one");
            assertThat(info.flagged()).isEqualTo(expected[i]);
            assertThat(info.read()).isEqualTo(i == 0 ? Boolean.TRUE : i == 1 ? Boolean.FALSE : null);
        }
        var absent = EwsResponseReader.readMetadata(metadataXml(metadataItem("one")), List.of("one")).get("one");
        assertThat(absent.read()).isNull();
        assertThat(absent.flagged()).isNull();
        var partial = EwsResponseReader.readMetadata(metadataXml(metadataItem("one")
                .replace("</t:Message>", "<t:IsRead>false</t:IsRead><t:Flag/></t:Message>")), List.of("one")).get("one");
        assertThat(partial.read()).isFalse();
        assertThat(partial.flagged()).isNull();
    }

}
