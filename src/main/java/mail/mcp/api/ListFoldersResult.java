package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Folders of the local copy.")
public record ListFoldersResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Folders.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<FolderInfo> folders
) { }
