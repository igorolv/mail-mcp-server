package mail.mcp.index;

public final class IndexLockedException extends RuntimeException {
    public IndexLockedException() {
        super("Connection is being synchronized by another process");
    }
}
