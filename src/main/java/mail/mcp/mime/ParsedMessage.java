package mail.mcp.mime;

import mail.mcp.model.MailMessage;

/** Parsed MIME headers, attachments and the selected body, before HTML conversion. */
public record ParsedMessage(MailMessage message, String body, boolean html) { }
