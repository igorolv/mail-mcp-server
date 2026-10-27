package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.time.Instant;

@Schema(description = "A page of search results.")
public record SearchResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Text query; absent for date-only search.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String query,
        @Schema(description = "Field searched.", requiredMode = Schema.RequiredMode.REQUIRED)
        String field,
        @Schema(description = "Folder key searched; absent for all folders.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String folder,
        @Schema(description = "Matching messages; copies of one message count once.", requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
        @Schema(description = "Position of this page.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Pass as offset to get the next page; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "Hits, most relevant first; newest first for date-only search.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SearchHitInfo> hits,
        @Schema(description = "Applied since bound, in UTC.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant since,
        @Schema(description = "Applied until bound, in UTC.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant until
) { }
