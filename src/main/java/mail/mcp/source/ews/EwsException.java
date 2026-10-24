package mail.mcp.source.ews;

/** Base of every failure the EWS source reports. Messages never contain credentials or mail content. */
public class EwsException extends RuntimeException {

    public EwsException(String message) {
        super(message);
    }

    public EwsException(String message, Throwable cause) {
        super(message, cause);
    }
}
