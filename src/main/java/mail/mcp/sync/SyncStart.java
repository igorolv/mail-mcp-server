package mail.mcp.sync;

/** Immediate result of a background synchronization request. */
public record SyncStart(Status status, String message) {
    public enum Status { STARTED, ALREADY_RUNNING, LOCKED_BY_OTHER_PROCESS, REFUSED }
}
