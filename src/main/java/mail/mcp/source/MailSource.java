package mail.mcp.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.Map;

/** One session with one mailbox: signs in at most once, never retries, read-only. */
public interface MailSource extends AutoCloseable {
    /** Mail folders to synchronize, in server order. */
    List<RemoteFolder> folders();

    /**
     * What changed in a folder since {@code cursor}.
     * @param cursor value returned by the previous call for this folder, or null for the first call
     * @param known refs of this folder that are already stored locally
     */
    FolderChanges changes(RemoteFolder folder, String cursor, Set<String> known);

    /**
     * One metadata snapshot of all resulting present refs, including known ones. Requests use
     * batches of at most 100, without MIME/body reads. Missing items are omitted; other errors
     * abort. The expected cursor is the one just returned by changes (IMAP checks UIDVALIDITY).
     */
    Map<String, RemoteMessageInfo> metadata(RemoteFolder folder, String expectedCursor, Collection<String> refs);

    /** Writes the raw MIME of one message to {@code target}, which must not exist yet. */
    Downloaded download(RemoteFolder folder, String ref, Path target);

    @Override void close();
}
