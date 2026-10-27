package mail.mcp.model;

import java.time.Instant;

/** Source-reported identity evidence, never a checksum of remote MIME. */
public record MessageFingerprint(String messageId, Long size, Instant sentAt, String from, String subject) {
    public MessageFingerprint {
        messageId = messageId == null ? null : messageId.strip();
        from = from == null ? null : from.strip();
    }

    public boolean complete() {
        return messageId != null && messageId.matches("<[^<>\\s]+>")
                && size != null && size > 0 && sentAt != null
                && from != null && !from.isBlank() && subject != null && !subject.isBlank();
    }
}
