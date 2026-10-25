package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Latest cumulative progress of one folder in this run.")
public record FolderSyncInfo(
        @Schema(description = "Folder path.", requiredMode = Schema.RequiredMode.REQUIRED)
        String path,
        @Schema(description = "New converted messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int downloaded,
        @Schema(description = "New failed messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int failed,
        @Schema(description = "Removed messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int removed,
        @Schema(description = "Whether the folder cursor was reset.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean reset,
        @Schema(description = "Whether the folder finished.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean done
) { }
