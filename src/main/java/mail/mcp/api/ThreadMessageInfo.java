package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@Schema(description = "A message card in a conversation.")
public record ThreadMessageInfo(
        @Schema(description = "Message key for getMessage.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Folder key.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "Date from the message header; absent when missing.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant date,
        @Schema(description = "Senders as Name <address>.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String from,
        @Schema(description = "Subject.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String subject,
        @Schema(description = "True when the message has named attachments.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasAttachments,
        @Schema(description = "converted (complete), downloaded (saved, text not processed yet) or failed (see getMessage error).", requiredMode = Schema.RequiredMode.REQUIRED)
        String state,
        @Schema(description = "Absolute message directory, readable directly; same layout as getMessage localPath.", requiredMode = Schema.RequiredMode.REQUIRED)
        String localPath,
        @Schema(description = "Read in the mailbox as of the last sync; absent when unknown.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean read,
        @Schema(description = "Flagged or starred in the mailbox as of the last sync (not sender importance); absent when unknown.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean flagged,
        @Schema(description = "Mailbox labels of this message as of the last sync, such as Gmail's Inbox, Sent or " +
                "user labels; empty when it has none. Absent when unknown or when the mailbox has no labels.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<String> labels,
        @Schema(description = "Other stored copies of this message (same Message-ID, date, sender and subject); absent when none.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<MessageCopyInfo> copies
) { }
