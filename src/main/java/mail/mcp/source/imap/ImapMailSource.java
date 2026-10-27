package mail.mcp.source.imap;

import mail.mcp.source.Downloaded;
import mail.mcp.source.FolderChanges;
import mail.mcp.source.MailSource;
import mail.mcp.source.RemoteFolder;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.Map;
import mail.mcp.source.RemoteMessageInfo;

/** One lazy IMAP session shared by all folder queries and downloads. */
public final class ImapMailSource implements MailSource {
    private static final Set<String> EXCLUDED = Set.of("\\Noselect", "\\NonExistent", "\\Trash", "\\Junk", "\\Drafts");
    private static final Comparator<String> REF_ORDER = Comparator
            .comparing((String ref) -> ref.matches("[0-9]+") ? new BigInteger(ref) : null,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Comparator.naturalOrder());
    private final ImapClient client;

    public ImapMailSource(ImapClient client) { this.client = client; }

    @Override public List<RemoteFolder> folders() {
        return client.listFolders().stream()
                .filter(ImapFolderInfo::holdsMessages)
                .filter(folder -> folder.attributes().stream()
                        .noneMatch(attribute -> EXCLUDED.stream().anyMatch(attribute::equalsIgnoreCase)))
                .map(folder -> new RemoteFolder(folder.name(), folder.name()))
                .toList();
    }

    @Override public FolderChanges changes(RemoteFolder folder, String cursor, Set<String> known) {
        if (cursor != null && !cursor.matches("[0-9]+")) {
            throw new IllegalArgumentException("Cursor must be a non-negative decimal number");
        }
        var uids = client.folderUids(folder.remoteId());
        var present = uids.uids().stream().map(String::valueOf).toList();
        var presentSet = new HashSet<>(present);
        String newCursor = String.valueOf(uids.uidValidity());
        boolean reset = cursor != null && !cursor.equals(newCursor);
        var added = present.stream().filter(ref -> reset || !known.contains(ref)).toList();
        var removed = known.stream().filter(ref -> reset || !presentSet.contains(ref)).sorted(REF_ORDER).toList();
        return new FolderChanges(added, removed, newCursor, reset);
    }

    @Override public Map<String, RemoteMessageInfo> metadata(RemoteFolder folder, String expectedCursor,
                                                           Collection<String> refs) {
        if (expectedCursor == null || !expectedCursor.matches("[0-9]+")) {
            throw new IllegalArgumentException("Metadata requires the UIDVALIDITY change cursor");
        }
        try {
            var uids = refs.stream().map(ref -> {
                if (ref == null || !ref.matches("[0-9]+")) { throw new IllegalArgumentException("Invalid metadata UID"); }
                return Long.valueOf(ref);
            }).toList();
            return client.metadata(folder.remoteId(), Long.parseLong(expectedCursor), uids);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Metadata UID or cursor outside range"); }
    }

    @Override public Downloaded download(RemoteFolder folder, String ref, Path target) {
        if (ref == null || !ref.matches("[0-9]+")) {
            throw new IllegalArgumentException("Message ref must be a positive decimal number");
        }
        long uid;
        try { uid = Long.parseLong(ref); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Message ref is outside the UID range"); }
        if (uid <= 0) { throw new IllegalArgumentException("Message ref must be positive"); }
        return client.download(folder.remoteId(), uid, target, ImapClient.DEFAULT_MAX_MIME_BYTES);
    }

    @Override public void close() { client.close(); }
}
