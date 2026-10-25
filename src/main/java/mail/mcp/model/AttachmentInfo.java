package mail.mcp.model;

/** Decoded attachment: file is relative to attachments/, name is the original decoded name. */
public record AttachmentInfo(String file, String name, String contentType, long size,
                             String contentId, boolean inline, String charset) { }
