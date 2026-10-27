package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.time.Instant;

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
        List<MessageSummary> messages,
        @Schema(description = "Inclusive UTC lower date bound.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant since,
        @Schema(description = "Exclusive UTC upper date bound.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant until
) { }
