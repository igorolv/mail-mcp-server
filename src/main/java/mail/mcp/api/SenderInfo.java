package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@Schema(description = "Counts for one sender address or domain.")
public record SenderInfo(
        @Schema(description = "Lowercase sender address or domain, depending on groupBy.", requiredMode = Schema.RequiredMode.REQUIRED)
        String sender,
        @Schema(description = "Matching messages; copies of one message count once.", requiredMode = Schema.RequiredMode.REQUIRED)
        int messages,
        @Schema(description = "Earliest message date; absent when no message has a date.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant earliest,
        @Schema(description = "Latest message date; absent when no message has a date.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant latest,
        @Schema(description = "Folders holding these messages, most messages first. Every copy counts, so the sum can exceed messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SenderFolderInfo> folders
) { }
