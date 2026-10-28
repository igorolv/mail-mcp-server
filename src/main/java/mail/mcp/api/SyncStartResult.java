package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of the sync request.")
public record SyncStartResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "started (launched, not completed); alreadyRunning (a sync is already running " +
                "in this process); lockedByOtherProcess (another process holds the local write lock, possibly " +
                "for an offline command; this call started no sync); refused (see message). " +
                "Use syncStatus for started/alreadyRunning; it reports sync runs, not offline command progress.", requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "Explanation; relay it to the user when status is refused.", requiredMode = Schema.RequiredMode.REQUIRED)
        String message
) { }
