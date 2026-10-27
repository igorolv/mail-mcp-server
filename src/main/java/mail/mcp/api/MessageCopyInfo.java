package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Another stored copy of the same message, usually in another folder.")
public record MessageCopyInfo(
        @Schema(description = "Message key of the copy.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Folder key of the copy.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder
) { }
