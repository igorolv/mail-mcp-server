package mail.mcp.model;

import java.time.Instant;
import java.util.List;

/** Headers and attachment descriptions read from MIME; date is null when missing or invalid. */
public record MailMessage(String subject, List<Address> from, List<Address> to, List<Address> cc,
                          Instant date, String messageId, String inReplyTo, List<String> references,
                          List<AttachmentInfo> attachments) {
    public MailMessage {
        from = from == null ? List.of() : List.copyOf(from);
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        references = references == null ? List.of() : List.copyOf(references);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
