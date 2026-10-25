package mail.mcp.model;

import java.time.Instant;
import java.util.List;

/** Current or last synchronization run, persisted by the writer-lock holder. */
public record SyncStatus(String state, long pid, Instant startedAt, Instant finishedAt,
                         List<FolderProgress> folders, String error, boolean signInRejected) { }
