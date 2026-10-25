package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Folders stored locally, in stored order; coverage may be incomplete during sync.")
public record ListFoldersResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Local folders.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<FolderInfo> folders
) { }
