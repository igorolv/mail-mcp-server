package mail.mcp.source;

import java.util.List;

/**
 * added/removed: ascending UID order for IMAP; server order for EWS (removed sorted).
 * reset: remote refs are invalid; rescanned: EWS recovered an invalid SyncState.
 */
public record FolderChanges(List<String> added, List<String> removed, String cursor, boolean reset, boolean rescanned) {
    public FolderChanges(List<String> added, List<String> removed, String cursor, boolean reset) {
        this(added, removed, cursor, reset, false);
    }
    public FolderChanges {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }
}
