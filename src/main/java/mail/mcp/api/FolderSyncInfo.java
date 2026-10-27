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
        @Schema(description = "Whether remote references were invalidated (IMAP UIDVALIDITY change).", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean reset,
        @Schema(description = "Whether the folder finished.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean done,
        @Schema(description = "Whether EWS recovered an invalid SyncState by rescanning.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean rescanned
) {
    public FolderSyncInfo(String path, int downloaded, int failed, int removed, boolean reset, boolean done) {
        this(path, downloaded, failed, removed, reset, done, false);
    }
}
