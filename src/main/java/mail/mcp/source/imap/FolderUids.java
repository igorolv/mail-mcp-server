package mail.mcp.source.imap;

import jakarta.mail.Flags;

import java.util.List;
import java.util.Map;

/**
 * UIDs in ascending order, meaningful only with this folder's UIDVALIDITY, and the FLAGS and Gmail
 * items each one returned in the same FETCH; a UID without a parsed item is absent from these maps.
 */
public record FolderUids(long uidValidity, List<Long> uids, Map<Long, Flags> flags,
                         Map<Long, GmailMessageInfo> gmail) {
    public FolderUids(long uidValidity, List<Long> uids) { this(uidValidity, uids, Map.of(), Map.of()); }

    public FolderUids(long uidValidity, List<Long> uids, Map<Long, Flags> flags) {
        this(uidValidity, uids, flags, Map.of());
    }

    public FolderUids {
        uids = List.copyOf(uids);
        flags = Map.copyOf(flags);
        gmail = Map.copyOf(gmail);
    }
}
