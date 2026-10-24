package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Mailboxes configured on this server; every other tool names one of them.")
public record ListConnectionsResult(
        @Schema(description = "Configured connections, in configuration order. Empty when nothing is configured.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ConnectionInfo> connections
) {
}
