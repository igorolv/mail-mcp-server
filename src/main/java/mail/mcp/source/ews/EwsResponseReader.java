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
import java.util.Map;
import java.util.HashMap;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import mail.mcp.model.MessageFingerprint;
import mail.mcp.source.RemoteMessageInfo;

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

    /** Folder ids from a multi-folder {@code GetFolder}, in response order. */
    static List<String> readFolderIds(InputStream in) throws XMLStreamException, IOException {
        List<String> ids = new ArrayList<>();
        read(EwsOperation.GET_FOLDER, in, r -> {
            if (EwsXml.TYPES_NS.equals(r.getNamespaceURI()) && "FolderId".equals(r.getLocalName())) {
                String id = r.getAttributeValue(null, "Id");
                if (id == null) {
                    throw new EwsProtocolException("GetFolder response has a FolderId without Id");
                }
                ids.add(id);
            }
        });
        return List.copyOf(ids);
    }

    /** One deep {@code FindFolder} page. */
    static EwsFolderPage readFolderPage(InputStream in) throws XMLStreamException, IOException {
        List<EwsFolderNode> folders = new ArrayList<>();
        boolean[] includesLast = {false};
        read(EwsOperation.FIND_FOLDER, in, r -> {
            if (EwsXml.MESSAGES_NS.equals(r.getNamespaceURI()) && "RootFolder".equals(r.getLocalName())) {
                includesLast[0] = Boolean.parseBoolean(r.getAttributeValue(null, "IncludesLastItemInRange"));
            } else if (EwsXml.TYPES_NS.equals(r.getNamespaceURI()) && "Folders".equals(r.getLocalName())) {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == END_ELEMENT) {
                        break;
                    }
                    if (event == START_ELEMENT) {
                        folders.add(readFolderNode(r));
                    }
                }
            }
        });
        return new EwsFolderPage(List.copyOf(folders), includesLast[0]);
    }

    private static EwsFolderNode readFolderNode(XMLStreamReader r) throws XMLStreamException {
        String kind = r.getLocalName();
        String id = null;
        String parentId = null;
        String name = null;
        String folderClass = null;
        while (r.hasNext()) {
            int event = r.next();
            if (event == END_ELEMENT) {
                break;
            }
            if (event != START_ELEMENT) {
                continue;
            }
            if (EwsXml.TYPES_NS.equals(r.getNamespaceURI())) {
                switch (r.getLocalName()) {
                    case "FolderId" -> id = r.getAttributeValue(null, "Id");
                    case "ParentFolderId" -> parentId = r.getAttributeValue(null, "Id");
                    case "DisplayName" -> { name = r.getElementText(); continue; }
                    case "FolderClass" -> { folderClass = r.getElementText(); continue; }
                    default -> { }
                }
            }
            skipElement(r);
        }
        return new EwsFolderNode(id, parentId, name, folderClass, kind);
    }

    /** One {@code SyncFolderItems} page, consuming each change as a whole. */
    static EwsSyncPage readSyncPage(InputStream in) throws XMLStreamException, IOException {
        String[] state = {null};
        boolean[] includesLast = {false};
        List<String> created = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        read(EwsOperation.SYNC_FOLDER_ITEMS, in, r -> {
            if (EwsXml.MESSAGES_NS.equals(r.getNamespaceURI())) {
                switch (r.getLocalName()) {
                    case "SyncState" -> state[0] = r.getElementText();
                    case "IncludesLastItemInRange" ->
                            includesLast[0] = parseBoolean(r.getElementText(), "IncludesLastItemInRange");
                    case "Changes" -> {
                        while (r.hasNext()) {
                            int event = r.next();
                            if (event == END_ELEMENT) {
                                break;
                            }
                            if (event != START_ELEMENT) {
                                continue;
                            }
                            String kind = r.getLocalName();
                            if (EwsXml.TYPES_NS.equals(r.getNamespaceURI())
                                    && ("Create".equals(kind) || "Delete".equals(kind))) {
                                ("Create".equals(kind) ? created : deleted).add(readChangeId(r));
                            } else {
                                skipElement(r);
                            }
                        }
                    }
                    default -> { }
                }
            }
        });
        if (state[0] == null) {
            throw new EwsProtocolException("SyncFolderItems response has no SyncState");
        }
        return new EwsSyncPage(state[0], includesLast[0], List.copyOf(created), List.copyOf(deleted));
    }

    private static String readChangeId(XMLStreamReader r) throws XMLStreamException {
        String id = null;
        int depth = 1;
        while (depth > 0) {
            int event = r.next();
            if (event == START_ELEMENT) {
                depth++;
                if (EwsXml.TYPES_NS.equals(r.getNamespaceURI()) && "ItemId".equals(r.getLocalName())) {
                    id = r.getAttributeValue(null, "Id");
                }
            } else if (event == END_ELEMENT) {
                depth--;
            }
        }
        if (id == null) {
            throw new EwsProtocolException("SyncFolderItems change has no ItemId Id");
        }
        return id;
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

    /** Each response is consumed separately; missing items never shift successful item ids. */
    static Map<String, RemoteMessageInfo> readMetadata(InputStream in, List<String> requested)
            throws XMLStreamException, IOException {
        var result = new HashMap<String, RemoteMessageInfo>();
        int[] responses = {0};
        read(EwsOperation.GET_ITEM, in, r -> { }, r -> {
            if (!"GetItemResponseMessage".equals(r.getLocalName()) || ++responses[0] > requested.size()) {
                throw new EwsProtocolException("Unexpected metadata response message");
            }
            String responseClass = r.getAttributeValue(null, "ResponseClass");
            String code = null;
            RemoteMessageInfo item = null;
            while (r.hasNext()) {
                int event = r.next();
                if (event == END_ELEMENT) { break; }
                if (event != START_ELEMENT) { continue; }
                if (EwsXml.MESSAGES_NS.equals(r.getNamespaceURI()) && "ResponseCode".equals(r.getLocalName())) {
                    code = r.getElementText().strip();
                } else if (EwsXml.MESSAGES_NS.equals(r.getNamespaceURI()) && "Items".equals(r.getLocalName())) {
                    while (r.hasNext()) {
                        int child = r.next();
                        if (child == END_ELEMENT) { break; }
                        if (child == START_ELEMENT) {
                            if (item != null || !EwsXml.TYPES_NS.equals(r.getNamespaceURI())) {
                                throw new EwsProtocolException("Unexpected metadata item");
                            }
                            item = readMetadataItem(r);
                        }
                    }
                } else { skipElement(r); }
            }
            if ("Error".equals(responseClass) && "ErrorItemNotFound".equals(code) && item == null) { return; }
            if (!"Success".equals(responseClass) || !"NoError".equals(code)) {
                throw new EwsResponseException(EwsOperation.GET_ITEM, code == null ? "unknown" : code, null);
            }
            if (item == null || item.ref() == null || !requested.contains(item.ref()) || result.putIfAbsent(item.ref(), item) != null) {
                throw new EwsProtocolException("Missing, duplicate or unrequested metadata item id");
            }
        });
        if (responses[0] != requested.size()) {
            throw new EwsProtocolException("GetItem returned a different number of metadata responses");
        }
        return Map.copyOf(result);
    }

    private static RemoteMessageInfo readMetadataItem(XMLStreamReader r) throws XMLStreamException {
        String ref = null, messageId = null, from = null, subject = null;
        Long size = null;
        Instant sentAt = null;
        var fields = new java.util.HashSet<String>();
        while (r.hasNext()) {
            int event = r.next();
            if (event == END_ELEMENT) { break; }
            if (event != START_ELEMENT) { continue; }
            String field = r.getLocalName();
            if (!EwsXml.TYPES_NS.equals(r.getNamespaceURI())) { skipElement(r); continue; }
            if (!fields.add(field)) { throw new EwsProtocolException("Duplicate metadata field"); }
            switch (field) {
                case "ItemId" -> { ref = r.getAttributeValue(null, "Id"); skipElement(r); }
                case "InternetMessageId" -> messageId = r.getElementText();
                case "Subject" -> subject = r.getElementText();
                case "Size" -> {
                    try { size = Long.valueOf(r.getElementText().strip()); }
                    catch (NumberFormatException e) { throw new EwsProtocolException("Invalid metadata size", e); }
                }
                case "DateTimeSent" -> {
                    try { sentAt = Instant.parse(r.getElementText().strip()); }
                    catch (DateTimeParseException e) { throw new EwsProtocolException("Invalid metadata date", e); }
                }
                case "From" -> {
                    int depth = 1, addresses = 0;
                    while (depth > 0) {
                        int nested = r.next();
                        if (nested == START_ELEMENT) {
                            if (EwsXml.TYPES_NS.equals(r.getNamespaceURI()) && "EmailAddress".equals(r.getLocalName())) {
                                from = r.getElementText();
                                addresses++;
                            } else { depth++; }
                        } else if (nested == END_ELEMENT) { depth--; }
                    }
                    if (addresses != 1) { from = null; }
                }
                default -> skipElement(r);
            }
        }
        return new RemoteMessageInfo(ref, new MessageFingerprint(messageId, size, sentAt, from, subject));
    }

    @FunctionalInterface
    interface PayloadHandler {
        /** Called for every start element outside the envelope bookkeeping; may consume the element. */
        void onStart(XMLStreamReader r) throws XMLStreamException, IOException;
    }

    static void read(EwsOperation operation, InputStream in, PayloadHandler payload)
            throws XMLStreamException, IOException {
        read(operation, in, payload, null);
    }

    private static void read(EwsOperation operation, InputStream in, PayloadHandler payload, PayloadHandler response)
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
                    if (response != null) { response.onStart(r); continue; }
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

    static final class MimeFields {
        String itemId;
        Boolean read;
        long bytes = -1;
    }
}
