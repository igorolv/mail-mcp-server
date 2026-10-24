package mail.mcp.source.ews;

/** The response could not be understood or broke a local limit (malformed XML, missing element, too large). */
public class EwsProtocolException extends EwsException {

    public EwsProtocolException(String message) {
        super(message);
    }

    public EwsProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
