package mail.mcp.source.imap;

/** Sanitized failure: provider responses and nested exceptions can contain mailbox data. */
public final class ImapException extends RuntimeException {
    public enum Kind { AUTHENTICATION, CONNECTION, PROTOCOL, LIMIT, LOCAL_IO, CLOSED }

    private final Kind kind;

    public ImapException(Kind kind) {
        super(switch (kind) {
            case AUTHENTICATION -> "IMAP sign-in rejected; no further sign-in attempts in this client";
            case CONNECTION -> "IMAP connection failed; check TLS, network and server settings locally";
            case PROTOCOL -> "IMAP operation failed; no automatic retry";
            case LIMIT -> "IMAP MIME exceeds the configured byte limit";
            case LOCAL_IO -> "Cannot save IMAP MIME locally";
            case CLOSED -> "IMAP client is closed; create a new client only for an explicit new request";
        });
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
