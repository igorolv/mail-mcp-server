package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.time.Instant;

@Schema(description = "A page of a folder's messages.")
public record ListMessagesResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Folder key.", requiredMode = Schema.RequiredMode.REQUIRED)
        String folder,
        @Schema(description = "Messages in the folder within the date bounds.", requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
        @Schema(description = "Position of this page.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Pass as offset to get the next page; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "Messages, newest first; undated last.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<MessageSummary> messages,
        @Schema(description = "Applied since bound, in UTC.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant since,
        @Schema(description = "Applied until bound, in UTC.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant until
) { }
