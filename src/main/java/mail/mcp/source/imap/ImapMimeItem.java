package mail.mcp.source.imap;

import java.nio.file.Path;

/** Original MIME stays local. UID is meaningful only with folder and UIDVALIDITY. */
public record ImapMimeItem(long uidValidity, long uid, long bytes, String sha256,
                           boolean seenBefore, boolean seenAfter, Path file) { }
