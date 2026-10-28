package mail.mcp.model;

import java.time.Instant;
import java.util.List;

/** Local catalog entry for one message. labels is null when unknown, an empty list when known empty. */
public record MessageCard(String key, String folderKey, Instant date, String from, String subject,
                          boolean hasAttachments, String state, Boolean read, Boolean flagged,
                          List<String> labels) {
    public MessageCard {
        labels = labels == null ? null : List.copyOf(labels);
    }

    public MessageCard(String key, String folderKey, Instant date, String from, String subject,
                       boolean hasAttachments, String state) {
        this(key, folderKey, date, from, subject, hasAttachments, state, null, null, null);
    }
}
