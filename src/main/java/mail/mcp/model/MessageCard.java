package mail.mcp.model;

import java.time.Instant;

/** Local catalog entry for one message. */
public record MessageCard(String key, String folderKey, Instant date, String from, String subject,
                          boolean hasAttachments, String state, Boolean read, Boolean flagged) {
    public MessageCard(String key, String folderKey, Instant date, String from, String subject,
                       boolean hasAttachments, String state) {
        this(key, folderKey, date, from, subject, hasAttachments, state, null, null);
    }
}
