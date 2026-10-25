package mail.mcp.sync;

import mail.mcp.model.MailMessage;
import mail.mcp.model.MessageMeta;

/** Conversion result ready for indexing; message and body are null on failure. */
public record Processed(MessageMeta meta, MailMessage message, String body) { }
