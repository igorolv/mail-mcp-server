package mail.mcp.model;

import java.time.Instant;
import java.util.List;

/** Sender counts over a complete match set; messages count recognized copies once. */
public record SenderSummary(int messages, int withoutSender, int undated, Instant earliest, Instant latest,
                            int total, List<Sender> senders) {
    public record Sender(String sender, int messages, Instant earliest, Instant latest, List<FolderCount> folders) { }

    /** Counts every stored copy in the folder, like the folder totals. */
    public record FolderCount(String folderKey, int count) { }
}
