package mail.mcp.source.ews;

/** The server answered with an HTTP status that carries no EWS response, e.g. 403 or 503. */
public class EwsHttpException extends EwsException {

    private final int status;

    public EwsHttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
