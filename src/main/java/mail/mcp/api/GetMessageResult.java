package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import java.util.List;

@Schema(description = "Local message headers, a body chunk and attachment file paths.")
public record GetMessageResult(
        @Schema(description = "Local message UUID.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Folder path from metadata.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "downloaded, converted or failed.", requiredMode = Schema.RequiredMode.REQUIRED)
        String state,
        @Schema(description = "Message failure explanation.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error,
        @Schema(description = "Decoded subject.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String subject,
        @Schema(description = "Displayed sender addresses.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> from,
        @Schema(description = "Displayed recipient addresses.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> to,
        @Schema(description = "Displayed copied addresses.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> cc,
        @Schema(description = "Message date when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant date,
        @Schema(description = "Message-ID header.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String messageId,
        @Schema(description = "In-Reply-To header.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String inReplyTo,
        @Schema(description = "References header identifiers.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> references,
        @Schema(description = "Chunk of body.md; absent if there is no saved body.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String body,
        @Schema(description = "Requested character position in body.md.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Next character position; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "Full body length in Java characters; zero without a body.", requiredMode = Schema.RequiredMode.REQUIRED)
        int bodyLength,
        @Schema(description = "Attachments parsed from MIME; empty if unavailable.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<AttachmentRef> attachments,
        @Schema(description = "Absolute message directory; files may be read directly.", requiredMode = Schema.RequiredMode.REQUIRED)
        String localPath
) { }
