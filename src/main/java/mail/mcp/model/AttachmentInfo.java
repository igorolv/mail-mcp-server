package mail.mcp.model;

/** Decoded attachment: file is relative to attachments/, name is the original decoded name;
 * parent is the containing attachment's stored file, null for the message's own attachments. */
public record AttachmentInfo(String file, String name, String contentType, long size,
                             String contentId, boolean inline, String charset, String parent) { }
