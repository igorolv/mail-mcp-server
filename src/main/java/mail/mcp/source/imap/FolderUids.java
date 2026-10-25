package mail.mcp.source.imap;

import java.util.List;

/** UIDs in ascending order, meaningful only with this folder's UIDVALIDITY. */
public record FolderUids(long uidValidity, List<Long> uids) {
    public FolderUids { uids = List.copyOf(uids); }
}
