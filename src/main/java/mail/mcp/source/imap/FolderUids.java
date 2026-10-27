package mail.mcp.source.imap;

import jakarta.mail.Flags;

import java.util.List;
import java.util.Map;

/**
 * UIDs in ascending order, meaningful only with this folder's UIDVALIDITY, and the FLAGS each one
 * returned in the same FETCH; a UID without a parsed FLAGS item is absent from {@code flags}.
 */
public record FolderUids(long uidValidity, List<Long> uids, Map<Long, Flags> flags) {
    public FolderUids(long uidValidity, List<Long> uids) { this(uidValidity, uids, Map.of()); }
    public FolderUids {
        uids = List.copyOf(uids);
        flags = Map.copyOf(flags);
    }
}
