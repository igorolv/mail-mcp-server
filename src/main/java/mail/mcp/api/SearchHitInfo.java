package mail.mcp.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** A scored message with an optional body or saved attachment Markdown passage and its identity. */
@Schema(description = "A matching message.")
public record SearchHitInfo(
        @Schema(description = "Message key for getMessage and getThread.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Folder key.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "Date from the message header; absent when missing.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant date,
        @Schema(description = "Senders as Name <address>.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String from,
        @Schema(description = "Subject.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String subject,
        @Schema(description = "True when the message has named attachments.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasAttachments,
        @Schema(description = "converted (complete), downloaded (saved, text not processed yet) or failed (see getMessage error).", requiredMode = Schema.RequiredMode.REQUIRED)
        String state,
        @Schema(description = "Relevance score, higher is better; 0 for date-only search.", requiredMode = Schema.RequiredMode.REQUIRED)
        float score,
        @Schema(description = "Passage from the body or an attachment text with matches in **bold**; absent for date-only search or without text.", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String snippet,
        @Schema(description = "Absolute message directory, readable directly: message.eml (original), body.md, attachments/.", requiredMode = Schema.RequiredMode.REQUIRED)
        String localPath,
        @Schema(description = "Attachment the snippet comes from; pass as file to getAttachment. " +
                "Absent when the snippet is from the body.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String snippetAttachmentFile,
        @Schema(description = "Original name of the attachment the snippet comes from.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String snippetAttachmentName,
        @Schema(description = "Read in the mailbox as of the last sync; absent when unknown.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean read,
        @Schema(description = "Flagged or starred in the mailbox as of the last sync (not sender importance); absent when unknown.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean flagged,
        @Schema(description = "Other stored copies of this message (same Message-ID, date, sender and subject) that also match; absent when none.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<MessageCopyInfo> copies
) { }
