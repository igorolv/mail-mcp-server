package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Another local copy of a message in the same connection.")
public record MessageCopyInfo(
        @Schema(description = "Local message UUID.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Local folder key.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder
) { }
