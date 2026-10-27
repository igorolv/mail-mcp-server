package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import java.util.List;

@Schema(description = "Status of the current or last sync.")
public record SyncStatusResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "running, completed, failed, interrupted (the syncing process stopped before finishing) or never (no sync yet).", requiredMode = Schema.RequiredMode.REQUIRED)
        String state,
        @Schema(description = "When the sync started.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant startedAt,
        @Schema(description = "When the sync ended; absent while running.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant finishedAt,
        @Schema(description = "New messages saved, all folders.", requiredMode = Schema.RequiredMode.REQUIRED)
        int downloaded,
        @Schema(description = "New messages that failed to download or convert, all folders.", requiredMode = Schema.RequiredMode.REQUIRED)
        int failed,
        @Schema(description = "Local messages removed because they are gone from the mailbox, all folders.", requiredMode = Schema.RequiredMode.REQUIRED)
        int removed,
        @Schema(description = "Progress per folder.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<FolderSyncInfo> folders,
        @Schema(description = "Why the sync failed.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error,
        @Schema(description = "True when the mailbox rejected the sign-in; sync works again only after the user fixes the " +
                "credentials and runs mail-sync in a terminal.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean signInRejected
) { }
