package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A folder of the local copy.")
public record FolderInfo(
        @Schema(description = "Folder key for listMessages and search.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Folder path in the mailbox.", requiredMode = Schema.RequiredMode.REQUIRED)
        String path,
        @Schema(description = "Number of locally stored messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int messages,
        @Schema(description = "True once the folder's first sync finished; when false, messages may be missing.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean synced
) { }
