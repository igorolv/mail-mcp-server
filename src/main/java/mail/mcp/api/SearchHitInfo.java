package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A matching local message with a score and body snippet.")
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
        @Schema(description = "Markdown body passage with **matches**; absent without a body.", nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String snippet,
        @Schema(description = "Absolute message directory; files may be read directly.", requiredMode = Schema.RequiredMode.REQUIRED)
        String localPath
) { }
