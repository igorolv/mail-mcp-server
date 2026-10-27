package mail.mcp.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** A scored message with an optional body or saved attachment Markdown passage and its identity. */
@Schema(description = "A matching local message with a score and body or attachment snippet.")
public record SearchHitInfo(
        @Schema(description = "Local message UUID.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Local folder key.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "Message date when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant date,
        @Schema(description = "Displayed sender addresses.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String from,
        @Schema(description = "Decoded subject.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String subject,
        @Schema(description = "Whether attachment names are present.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasAttachments,
        @Schema(description = "downloaded, converted or failed.", requiredMode = Schema.RequiredMode.REQUIRED)
        String state,
        @Schema(description = "BM25 relevance score.", requiredMode = Schema.RequiredMode.REQUIRED)
        float score,
        @Schema(description = "Markdown passage from the body or one saved attachment text, with **matches**; optional.", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String snippet,
        @Schema(description = "Absolute message directory; files may be read directly.", requiredMode = Schema.RequiredMode.REQUIRED)
        String localPath,
        @Schema(description = "Exact AttachmentInfo.file() lookup identifier, without the sidecar .md suffix; " +
                "pass to getAttachment(connection, key, file, ...). Absent for body or missing snippets.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String snippetAttachmentFile,
        @Schema(description = "Attachment display name, falling back to its exact filename when null or blank; " +
                "present only with an attachment snippet.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String snippetAttachmentName
) { }
