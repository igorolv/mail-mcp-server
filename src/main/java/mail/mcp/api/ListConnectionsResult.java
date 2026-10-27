package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Configured mailboxes.")
public record ListConnectionsResult(
        @Schema(description = "Configured mailboxes; empty when none is configured.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ConnectionInfo> connections
) {
}
