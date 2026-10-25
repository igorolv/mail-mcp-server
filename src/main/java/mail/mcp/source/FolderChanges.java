package mail.mcp.source;

import java.util.List;

/**
 * added/removed: ascending UID order for IMAP; server order for EWS (removed sorted).
 * reset: the old cursor is invalid.
 */
public record FolderChanges(List<String> added, List<String> removed, String cursor, boolean reset) {
    public FolderChanges {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }
}
