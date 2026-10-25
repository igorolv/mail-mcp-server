package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Immediate result of requesting background synchronization.")
public record SyncStartResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "started, alreadyRunning, lockedByOtherProcess or refused.", requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "Explanation of the start result.", requiredMode = Schema.RequiredMode.REQUIRED)
        String message
) { }
