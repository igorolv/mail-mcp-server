package mail.mcp.source.ews;

/** The request did not complete: DNS, TCP, TLS or timeout failure. No response was received. */
public class EwsTransportException extends EwsException {

    public EwsTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
