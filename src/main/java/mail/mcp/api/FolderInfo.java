package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A folder of the local copy.")
public record FolderInfo(
        @Schema(description = "Folder key for listMessages and search.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Folder path in the mailbox.", requiredMode = Schema.RequiredMode.REQUIRED)
        String path,
        @Schema(description = "Number of messages in the local committed index for this folder; copies " +
                "count separately. Zero if no index is available.", requiredMode = Schema.RequiredMode.REQUIRED)
        int messages,
        @Schema(description = "A completed folder sync is recorded. False means messages may be missing; " +
                "true does not guarantee current mail. Check syncStatus for the latest run.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean synced
) { }
