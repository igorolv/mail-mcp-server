package mail.mcp.source;

/** One message cannot be downloaded; the session stays usable. Message has no mail data. */
public final class MessageUnavailableException extends RuntimeException {
    public enum Reason { GONE, TOO_LARGE }

    private final Reason reason;

    public MessageUnavailableException(Reason reason) {
        super(switch (reason) {
            case GONE -> "Message is no longer on the server";
            case TOO_LARGE -> "Message exceeds the size limit";
        });
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
