package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.time.Instant;

@Schema(description = "One page of matches from the committed local search index.")
public record SearchResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Requested text query; absent for date-only search.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String query,
        @Schema(description = "Search field group.", requiredMode = Schema.RequiredMode.REQUIRED)
        String field,
        @Schema(description = "Optional local folder key.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String folder,
        @Schema(description = "Exact number of matching messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
        @Schema(description = "Requested starting position.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Next page position; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "Text hits sorted by score descending; date-only hits by date descending; ties by key.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SearchHitInfo> hits,
        @Schema(description = "Inclusive UTC lower date bound.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant since,
        @Schema(description = "Exclusive UTC upper date bound.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant until
) { }
