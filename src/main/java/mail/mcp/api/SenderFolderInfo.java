package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@Schema(description = "A sender's stored messages in one folder.")
public record SenderFolderInfo(
        @Schema(description = "Folder key from listFolders.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "Stored messages in this folder; every copy counts.", requiredMode = Schema.RequiredMode.REQUIRED)
        int count,
        @Schema(description = "Earliest message date in this folder; absent when no message has a date.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant earliest,
        @Schema(description = "Latest message date in this folder; absent when no message has a date.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant latest
) { }
