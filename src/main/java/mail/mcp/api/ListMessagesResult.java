package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One page of messages in the committed local catalog.")
public record ListMessagesResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Local folder key.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "Messages in this folder at this commit.", requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
        @Schema(description = "Requested starting position.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Next page position; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "Messages sorted by descending date, undated last, then key.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<MessageSummary> messages
) { }
