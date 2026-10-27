package mail.mcp.source;

import java.util.List;
import java.util.Map;

/**
 * added/removed: ascending UID order for IMAP; server order for EWS (removed sorted).
 * reset: remote refs are invalid; rescanned: EWS recovered an invalid SyncState.
 * observed: read/star observations of known, still present refs, without fingerprints: every
 * present UID for IMAP, only changed items for EWS (docs/plan.md 3.6, Д29). Absent means not observed.
 */
public record FolderChanges(List<String> added, List<String> removed, String cursor, boolean reset, boolean rescanned,
                            Map<String, RemoteMessageInfo> observed) {
    public FolderChanges(List<String> added, List<String> removed, String cursor, boolean reset) {
        this(added, removed, cursor, reset, false);
    }
    public FolderChanges(List<String> added, List<String> removed, String cursor, boolean reset, boolean rescanned) {
        this(added, removed, cursor, reset, rescanned, Map.of());
    }
    public FolderChanges {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        observed = Map.copyOf(observed);
    }
}
