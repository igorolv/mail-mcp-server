package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Local folder and its synchronization coverage.")
public record FolderInfo(
        @Schema(description = "Local folder key for listMessages.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Folder path.", requiredMode = Schema.RequiredMode.REQUIRED)
        String path,
        @Schema(description = "Messages in the committed local catalog.", requiredMode = Schema.RequiredMode.REQUIRED)
        int messages,
        @Schema(description = "True once a full pass saved a cursor; check syncStatus for current progress.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean synced
) { }
