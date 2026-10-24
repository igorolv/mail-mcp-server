package mail.mcp.source.ews;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
    void findItemReadsEveryItemTypeInServerOrder() throws Exception {
        EwsItemPage page = EwsResponseReader.readItemPage(fixture("finditem-newest.xml"));

        assertThat(page.totalItemsInView()).isEqualTo(1987);
        assertThat(page.includesLastItemInRange()).isFalse();
        assertThat(page.items()).containsExactly(
                new EwsItemRef("AAMkAD-synthetic-item-1=", "CQAAAB-synthetic-1", "2026-10-24T18:05:11Z", false),
                new EwsItemRef("AAMkAD-synthetic-item-2=", "CwAAAB-synthetic-2", "2026-10-24T17:00:00Z", true));
    }

    @Test
    void findItemOnAnEmptyFolderReturnsNoItems() throws Exception {
        EwsItemPage page = EwsResponseReader.readItemPage(fixture("finditem-empty.xml"));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalItemsInView()).isZero();
        assertThat(page.includesLastItemInRange()).isTrue();
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
                .isInstanceOf(EwsProtocolException.class)
                .hasMessageContaining("limit of 100 bytes");
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
}
