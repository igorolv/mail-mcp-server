package mail.mcp.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Local message card with direct file access.")
public record MessageSummary(
        @Schema(description = "Local message UUID.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Message date when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant date,
        @Schema(description = "Displayed sender addresses.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String from,
        @Schema(description = "Decoded subject.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String subject,
        @Schema(description = "Whether attachment names are present in the catalog.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasAttachments,
        @Schema(description = "downloaded, converted or failed.", requiredMode = Schema.RequiredMode.REQUIRED)
        String state,
        @Schema(description = "Absolute message directory; files may be read directly.", requiredMode = Schema.RequiredMode.REQUIRED)
        String localPath,
        @Schema(description = "Read state at the latest successful observation; absent when unknown.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean read,
        @Schema(description = "User flag/star at the latest successful observation, not sender importance; absent when unknown.",
                nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Boolean flagged
) { }
