package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A sender's stored messages in one folder.")
public record SenderFolderInfo(
        @Schema(description = "Folder key from listFolders.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "Stored messages in this folder; every copy counts.", requiredMode = Schema.RequiredMode.REQUIRED)
        int count
) { }
