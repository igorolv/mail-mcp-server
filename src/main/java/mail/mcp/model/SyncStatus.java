package mail.mcp.model;

import java.time.Instant;
import java.util.List;

/**
 * Current or last synchronization run, persisted by the writer-lock holder.
 * phase: the stage reached ({@code mail.mcp.sync.SyncPhase} id); null in status files written before phases.
 */
public record SyncStatus(String state, long pid, Instant startedAt, Instant finishedAt,
                         List<FolderProgress> folders, String error, boolean signInRejected, String phase) { }
