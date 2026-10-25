package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

import java.util.List;

@Schema(description = "Current or last synchronization status, read from local disk.")
public record SyncStatusResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "never, running, completed, failed or interrupted.", requiredMode = Schema.RequiredMode.REQUIRED)
        String state,
        @Schema(description = "Run start time.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant startedAt,
        @Schema(description = "Run end time, absent while running.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant finishedAt,
        @Schema(description = "Total new converted messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int downloaded,
        @Schema(description = "Total new failed messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int failed,
        @Schema(description = "Total removed messages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int removed,
        @Schema(description = "Latest progress in order of first appearance.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<FolderSyncInfo> folders,
        @Schema(description = "Fixed run failure explanation.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error,
        @Schema(description = "An explicit user-run mail-sync is needed after fixing rejected credentials.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean signInRejected
) { }
