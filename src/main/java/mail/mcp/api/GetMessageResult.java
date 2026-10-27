package mail.mcp.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import java.util.List;

@Schema(description = "A message: headers, a body chunk and attachments.")
public record GetMessageResult(
        @Schema(description = "Message key.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Folder path in the mailbox.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "converted (complete), downloaded (saved, text not processed yet) or failed (see error).", requiredMode = Schema.RequiredMode.REQUIRED)
        String state,
        @Schema(description = "Why the message failed; present when state is failed.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error,
        @Schema(description = "Subject.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String subject,
        @Schema(description = "Senders as Name <address>.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> from,
        @Schema(description = "To recipients as Name <address>.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> to,
        @Schema(description = "Cc recipients as Name <address>.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> cc,
        @Schema(description = "Date from the message header; absent when missing.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant date,
        @Schema(description = "Message-ID header.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String messageId,
        @Schema(description = "In-Reply-To header.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String inReplyTo,
        @Schema(description = "References header message IDs.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> references,
        @Schema(description = "Chunk of the body as Markdown; [redacted:<kind>] marks a hidden secret. Absent when there is no body.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String body,
        @Schema(description = "Character position of this chunk.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Pass as offset to get the next chunk; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "Length of the whole body in characters; 0 without a body.", requiredMode = Schema.RequiredMode.REQUIRED)
        int bodyLength,
        @Schema(description = "Attachments, including files unpacked from attached archives and messages; empty when none.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<AttachmentRef> attachments,
        @Schema(description = "Absolute message directory, readable directly: message.eml (original), body.md, attachments/.", requiredMode = Schema.RequiredMode.REQUIRED)
        String localPath,
        @Schema(description = "Read in the mailbox as of the last sync; absent when unknown.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean read,
        @Schema(description = "Flagged or starred in the mailbox as of the last sync (not sender importance); absent when unknown.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean flagged
) { }
