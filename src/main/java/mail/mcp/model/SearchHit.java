package mail.mcp.model;

import java.util.List;

/**
 * A scored card with an optional Markdown passage from the body or one saved attachment text.
 * Attachment fields are null for body/absent snippets; file is the exact AttachmentInfo.file()
 * lookup identifier without the sidecar suffix, and name is the display name (file when blank).
 */
public record SearchHit(MessageCard card, float score, String snippet,
                        String snippetAttachmentFile, String snippetAttachmentName, List<MessageCopy> copies) {
    public SearchHit(MessageCard card, float score, String snippet,
                     String snippetAttachmentFile, String snippetAttachmentName) {
        this(card, score, snippet, snippetAttachmentFile, snippetAttachmentName, List.of());
    }
}
