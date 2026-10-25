package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One page of matches from the committed local search index.")
public record SearchResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Requested query.", requiredMode = Schema.RequiredMode.REQUIRED)
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
        @Schema(description = "Hits sorted by score descending, then key.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SearchHitInfo> hits
) { }
