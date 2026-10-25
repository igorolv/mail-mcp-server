package mail.mcp.convert;

/** Fixed conversion failure message; never contains mail or process output. */
public final class PandocException extends RuntimeException {
    public PandocException(String message) { super(message); }
}
