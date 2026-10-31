package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Progress of one folder in this sync.")
public record FolderSyncInfo(
        @Schema(description = "Folder path in the mailbox.", requiredMode = Schema.RequiredMode.REQUIRED)
        String path,
        @Schema(description = "New messages saved.", requiredMode = Schema.RequiredMode.REQUIRED)
        int downloaded,
        @Schema(description = "New messages that failed to download or convert.", requiredMode = Schema.RequiredMode.REQUIRED)
        int failed,
        @Schema(description = "Local messages removed because they are gone from the mailbox.", requiredMode = Schema.RequiredMode.REQUIRED)
        int removed,
        @Schema(description = "True when the server reset the folder (IMAP UIDVALIDITY change).", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean reset,
        @Schema(description = "True when the folder is finished in this sync.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean done,
        @Schema(description = "True when Exchange required rescanning the whole folder.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean rescanned,
        @Schema(description = "True when the folder left the mail server during this sync, for example deleted or " +
                "renamed: it was skipped, and the next sync removes its local messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean gone
) {
    public FolderSyncInfo(String path, int downloaded, int failed, int removed, boolean reset, boolean done) {
        this(path, downloaded, failed, removed, reset, done, false, false);
    }
}
