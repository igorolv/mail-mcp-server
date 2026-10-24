package mail.mcp.source.ews;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Sends one SOAP request to the EWS endpoint of one mailbox and hands the response body to a
 * handler while the stream is still open.
 *
 * <p>Split from {@link EwsClient} so the HTTP library can be replaced (decision P1: Apache HttpClient
 * 5 with NTLM first, {@code HttpsURLConnection} as the fallback) and so tests can feed synthetic
 * responses. An implementation must:
 * <ul>
 *   <li>treat HTTP 401 after the sign-in handshake as {@link EwsAuthenticationException} and never
 *       retry it;</li>
 *   <li>pass HTTP 200 bodies, and HTTP 500 bodies that are XML (SOAP faults), to the handler;</li>
 *   <li>report other statuses as {@link EwsHttpException} and I/O failures as
 *       {@link EwsTransportException};</li>
 *   <li>not follow redirects, so credentials never go to another host.</li>
 * </ul>
 */
public interface EwsTransport extends AutoCloseable {

    /**
     * @param operation allow-listed operation, used for the {@code SOAPAction} header and messages
     * @param envelope  complete SOAP request; must be resent unchanged during the auth handshake
     * @param handler   reads the response body; runs before the stream is closed
     */
    <T> T exchange(EwsOperation operation, byte[] envelope, ResponseHandler<T> handler);

    /** HTTP requests sent so far, including the extra legs of the NTLM handshake. */
    int requestCount();

    @Override
    void close();

    @FunctionalInterface
    interface ResponseHandler<T> {
        T handle(InputStream body) throws IOException, XMLStreamException;
    }
}
