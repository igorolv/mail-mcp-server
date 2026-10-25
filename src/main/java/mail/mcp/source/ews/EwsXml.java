package mail.mcp.source.ews;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SOAP envelope writing and safe StAX parser settings for EWS.
 *
 * <p>Requests are small, so they are written into a byte array: the HTTP entity must be repeatable,
 * because the NTLM handshake sends the same request up to three times. Responses are parsed as a
 * stream (see {@link EwsResponseReader}).
 */
final class EwsXml {

    static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String MESSAGES_NS = "http://schemas.microsoft.com/exchange/services/2006/messages";
    static final String TYPES_NS = "http://schemas.microsoft.com/exchange/services/2006/types";

    /** Schema version of the Exchange 2013 CU23 server (docs/plan.md, 2.1). */
    static final String SERVER_VERSION = "Exchange2013_SP1";

    private static final XMLOutputFactory OUTPUT = XMLOutputFactory.newFactory();
    private static final XMLInputFactory INPUT = newInputFactory();

    private EwsXml() {
    }

    /** Writes the body of one operation; the operation element itself is already open. */
    @FunctionalInterface
    interface BodyWriter {
        void write(XMLStreamWriter w) throws XMLStreamException;
    }

    /** A complete SOAP 1.1 request for {@code operation}, UTF-8 encoded. */
    static byte[] envelope(EwsOperation operation, BodyWriter body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try {
            XMLStreamWriter w = OUTPUT.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            w.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            w.writeStartElement("s", "Envelope", SOAP_NS);
            w.writeNamespace("s", SOAP_NS);
            w.writeNamespace("m", MESSAGES_NS);
            w.writeNamespace("t", TYPES_NS);
            w.writeStartElement("s", "Header", SOAP_NS);
            w.writeEmptyElement("t", "RequestServerVersion", TYPES_NS);
            w.writeAttribute("Version", SERVER_VERSION);
            w.writeEndElement();
            w.writeStartElement("s", "Body", SOAP_NS);
            w.writeStartElement("m", operation.elementName(), MESSAGES_NS);
            body.write(w);
            w.writeEndElement();
            w.writeEndElement();
            w.writeEndElement();
            w.writeEndDocument();
            w.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Cannot write " + operation.elementName() + " request", e);
        }
        return out.toByteArray();
    }

    static void typesElement(XMLStreamWriter w, String localName, String text) throws XMLStreamException {
        w.writeStartElement("t", localName, TYPES_NS);
        w.writeCharacters(text);
        w.writeEndElement();
    }

    static void fieldUri(XMLStreamWriter w, String fieldUri) throws XMLStreamException {
        w.writeEmptyElement("t", "FieldURI", TYPES_NS);
        w.writeAttribute("FieldURI", fieldUri);
    }

    static void distinguishedFolderId(XMLStreamWriter w, DistinguishedFolder folder) throws XMLStreamException {
        w.writeEmptyElement("t", "DistinguishedFolderId", TYPES_NS);
        w.writeAttribute("Id", folder.id());
    }

    /**
     * Parser settings for untrusted server responses: no DTDs and no external entities (JAXP
     * security guide), namespaces on, and no coalescing, so a large base64 text node arrives in
     * several chunks instead of one huge string.
     */
    static XMLInputFactory inputFactory() {
        return INPUT;
    }

    private static XMLInputFactory newInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        return factory;
    }
}
