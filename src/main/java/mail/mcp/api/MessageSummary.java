package mail.mcp.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A message card.")
public record MessageSummary(
        @Schema(description = "Message key for getMessage and getThread.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
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
