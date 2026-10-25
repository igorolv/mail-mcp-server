package mail.mcp.source;

import java.util.List;

/** added/removed: refs in ascending order; reset: the old cursor is invalid. */
public record FolderChanges(List<String> added, List<String> removed, String cursor, boolean reset) {
    public FolderChanges {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }
}
