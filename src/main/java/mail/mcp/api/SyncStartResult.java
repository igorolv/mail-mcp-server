package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of the sync request.")
public record SyncStartResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "started; alreadyRunning (already syncing in this process); lockedByOtherProcess (another " +
                "server process is syncing it, poll syncStatus); refused (see message).", requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "Explanation; relay it to the user when status is refused.", requiredMode = Schema.RequiredMode.REQUIRED)
        String message
) { }
