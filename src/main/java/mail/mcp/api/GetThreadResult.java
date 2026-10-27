package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@Schema(description = "One page of a conversation in the committed local catalog.")
public record GetThreadResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Anchor local message UUID.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Exact number of local messages in the conversation at this commit; copies of one message count once.", requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
        @Schema(description = "Requested starting position.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Next page position; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer nextOffset,
        @Schema(description = "Messages sorted by ascending date, undated last, then key.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ThreadMessageInfo> messages
) { }
