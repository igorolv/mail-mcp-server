package mail.mcp.source.ews;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static javax.xml.stream.XMLStreamConstants.CDATA;
import static javax.xml.stream.XMLStreamConstants.CHARACTERS;
import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.SPACE;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

/**
 * Streaming StAX readers for EWS responses.
 *
 * <p>Every reader applies the same checks first: a SOAP fault becomes {@link EwsSoapFaultException},
 * a response message with {@code ResponseClass="Error"} becomes {@link EwsResponseException}, and a
 * response without any response message is a protocol error. Only then are the payload elements of
 * the operation read. Unknown elements are skipped, so additional properties in a response do not
 * break parsing.
 */
final class EwsResponseReader {

    private static final Logger log = LoggerFactory.getLogger(EwsResponseReader.class);

    static final String ERRORS_NS = "http://schemas.microsoft.com/exchange/services/2006/errors";

    private EwsResponseReader() {
    }

    /** {@code GetFolder} for one folder. */
    static EwsFolder readFolder(InputStream in) throws XMLStreamException, IOException {
        FolderFields f = new FolderFields();
        read(EwsOperation.GET_FOLDER, in, r -> {
            if (!EwsXml.TYPES_NS.equals(r.getNamespaceURI())) {
                return;
            }
            switch (r.getLocalName()) {
                case "FolderId" -> {
                    if (f.id == null) {
                        f.id = r.getAttributeValue(null, "Id");
                    }
                }
                case "DisplayName" -> f.displayName = r.getElementText();
                case "FolderClass" -> f.folderClass = r.getElementText();
                case "TotalCount" -> f.totalCount = parseInt(r.getElementText(), "TotalCount");
                case "UnreadCount" -> f.unreadCount = parseInt(r.getElementText(), "UnreadCount");
                case "ChildFolderCount" -> f.childFolderCount = parseInt(r.getElementText(), "ChildFolderCount");
                default -> {
                }
            }
        });
        if (f.id == null) {
            throw new EwsProtocolException("GetFolder response has no FolderId");
        }
        return new EwsFolder(f.id, f.displayName, f.folderClass, f.totalCount, f.unreadCount, f.childFolderCount);
    }

    /** One {@code FindItem} page. */
    static EwsItemPage readItemPage(InputStream in) throws XMLStreamException, IOException {
        List<ItemFields> items = new ArrayList<>();
        Integer[] totalItemsInView = {null};
        boolean[] includesLast = {false};
        read(EwsOperation.FIND_ITEM, in, r -> {
            if (EwsXml.MESSAGES_NS.equals(r.getNamespaceURI()) && "RootFolder".equals(r.getLocalName())) {
                String total = r.getAttributeValue(null, "TotalItemsInView");
                totalItemsInView[0] = total == null ? null : parseInt(total, "TotalItemsInView");
                includesLast[0] = Boolean.parseBoolean(r.getAttributeValue(null, "IncludesLastItemInRange"));
                return;
            }
            if (!EwsXml.TYPES_NS.equals(r.getNamespaceURI())) {
                return;
            }
            switch (r.getLocalName()) {
                case "ItemId" -> {
                    ItemFields item = new ItemFields();
                    item.id = r.getAttributeValue(null, "Id");
                    item.changeKey = r.getAttributeValue(null, "ChangeKey");
                    items.add(item);
                }
                case "DateTimeReceived" -> {
                    if (!items.isEmpty()) {
                        items.getLast().dateTimeReceived = r.getElementText();
                    }
                }
                case "IsRead" -> {
                    if (!items.isEmpty()) {
                        items.getLast().read = parseBoolean(r.getElementText(), "IsRead");
                    }
                }
                default -> {
                }
            }
        });
        List<EwsItemRef> refs = new ArrayList<>(items.size());
        for (ItemFields item : items) {
            if (item.id == null) {
                throw new EwsProtocolException("FindItem response has an ItemId without Id");
            }
            refs.add(new EwsItemRef(item.id, item.changeKey, item.dateTimeReceived, item.read));
        }
        return new EwsItemPage(List.copyOf(refs), totalItemsInView[0], includesLast[0]);
    }

    /**
     * {@code GetItem} with {@code IncludeMimeContent} for one item. The base64 MIME is decoded into
     * {@code mimeOut} chunk by chunk.
     *
     * @return the item id and {@code IsRead}; {@code file}, {@code bytes} and {@code sha256} are filled
     *         in by the caller, which owns the output file
     */
    static MimeFields readMime(InputStream in, OutputStream mimeOut, long maxMimeBytes)
            throws XMLStreamException, IOException {
        MimeFields m = new MimeFields();
        read(EwsOperation.GET_ITEM, in, r -> {
            if (!EwsXml.TYPES_NS.equals(r.getNamespaceURI())) {
                return;
            }
            switch (r.getLocalName()) {
                case "ItemId" -> {
                    if (m.itemId == null) {
                        m.itemId = r.getAttributeValue(null, "Id");
                    }
                }
                case "MimeContent" -> {
                    if (m.bytes >= 0) {
                        throw new EwsProtocolException("GetItem response has more than one MimeContent");
                    }
                    m.bytes = decodeText(r, new Base64ChunkDecoder(mimeOut, maxMimeBytes));
                }
                case "IsRead" -> m.read = parseBoolean(r.getElementText(), "IsRead");
                default -> {
                }
            }
        });
        if (m.bytes < 0) {
            throw new EwsProtocolException("GetItem response has no MimeContent");
        }
        return m;
    }

    @FunctionalInterface
    interface PayloadHandler {
        /** Called for every start element outside the envelope bookkeeping; may consume the element. */
        void onStart(XMLStreamReader r) throws XMLStreamException, IOException;
    }

    static void read(EwsOperation operation, InputStream in, PayloadHandler payload)
            throws XMLStreamException, IOException {
        XMLStreamReader r = EwsXml.inputFactory().createXMLStreamReader(in);
        try {
            int messages = 0;
            while (r.hasNext()) {
                if (r.next() != START_ELEMENT) {
                    continue;
                }
                String ns = r.getNamespaceURI();
                String local = r.getLocalName();
                if (EwsXml.SOAP_NS.equals(ns) && "Fault".equals(local)) {
                    throw readFault(operation, r);
                }
                if (EwsXml.MESSAGES_NS.equals(ns) && local.endsWith("ResponseMessage")) {
                    messages++;
                    String responseClass = r.getAttributeValue(null, "ResponseClass");
                    if ("Error".equals(responseClass)) {
                        throw readError(operation, r);
                    }
                    continue;
                }
                if (EwsXml.MESSAGES_NS.equals(ns) && "ResponseCode".equals(local)) {
                    String code = r.getElementText().strip();
                    if (!"NoError".equals(code)) {
                        log.warn("{} returned a warning: {}", operation.elementName(), code);
                    }
                    continue;
                }
                if (EwsXml.MESSAGES_NS.equals(ns) && ("MessageText".equals(local) || "MessageXml".equals(local))) {
                    skipElement(r);
                    continue;
                }
                payload.onStart(r);
            }
            if (messages == 0) {
                throw new EwsProtocolException(operation.elementName() + " response has no response message");
            }
        } finally {
            r.close();
        }
    }

    /** Reads the rest of an error response message and turns it into an exception. */
    private static EwsResponseException readError(EwsOperation operation, XMLStreamReader r)
            throws XMLStreamException {
        String code = null;
        String text = null;
        int depth = 1;
        while (depth > 0) {
            int event = r.next();
            if (event == START_ELEMENT) {
                String local = r.getLocalName();
                if (EwsXml.MESSAGES_NS.equals(r.getNamespaceURI()) && "ResponseCode".equals(local)) {
                    code = r.getElementText().strip();
                } else if (EwsXml.MESSAGES_NS.equals(r.getNamespaceURI()) && "MessageText".equals(local)) {
                    text = r.getElementText();
                } else {
                    depth++;
                }
            } else if (event == END_ELEMENT) {
                depth--;
            }
        }
        return new EwsResponseException(operation, code == null ? "unknown" : code, text);
    }

    /** Reads {@code s:Fault}: {@code faultcode}, {@code faultstring} and the EWS code in {@code detail}. */
    private static EwsSoapFaultException readFault(EwsOperation operation, XMLStreamReader r)
            throws XMLStreamException {
        String faultCode = null;
        String faultString = null;
        String responseCode = null;
        int depth = 1;
        while (depth > 0) {
            int event = r.next();
            if (event == START_ELEMENT) {
                String local = r.getLocalName();
                if ("faultcode".equals(local)) {
                    faultCode = r.getElementText().strip();
                } else if ("faultstring".equals(local)) {
                    faultString = r.getElementText();
                } else if (ERRORS_NS.equals(r.getNamespaceURI()) && "ResponseCode".equals(local)) {
                    responseCode = r.getElementText().strip();
                } else {
                    depth++;
                }
            } else if (event == END_ELEMENT) {
                depth--;
            }
        }
        return new EwsSoapFaultException(operation, faultCode, faultString, responseCode);
    }

    /** Streams the text of the current element into the decoder; leaves the reader on its end tag. */
    private static long decodeText(XMLStreamReader r, Base64ChunkDecoder decoder)
            throws XMLStreamException, IOException {
        while (true) {
            int event = r.next();
            switch (event) {
                case CHARACTERS, CDATA, SPACE ->
                        decoder.write(r.getTextCharacters(), r.getTextStart(), r.getTextLength());
                case END_ELEMENT -> {
                    return decoder.finish();
                }
                case START_ELEMENT -> throw new EwsProtocolException("MimeContent has a child element");
                default -> {
                }
            }
        }
    }

    private static void skipElement(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = r.next();
            if (event == START_ELEMENT) {
                depth++;
            } else if (event == END_ELEMENT) {
                depth--;
            }
        }
    }

    private static Integer parseInt(String text, String element) {
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            throw new EwsProtocolException(element + " is not a number: " + text, e);
        }
    }

    private static Boolean parseBoolean(String text, String element) {
        return switch (text.strip()) {
            case "true", "1" -> Boolean.TRUE;
            case "false", "0" -> Boolean.FALSE;
            default -> throw new EwsProtocolException(element + " is not a boolean: " + text);
        };
    }

    private static final class FolderFields {
        String id;
        String displayName;
        String folderClass;
        Integer totalCount;
        Integer unreadCount;
        Integer childFolderCount;
    }

    private static final class ItemFields {
        String id;
        String changeKey;
        String dateTimeReceived;
        Boolean read;
    }

    static final class MimeFields {
        String itemId;
        Boolean read;
        long bytes = -1;
    }
}
