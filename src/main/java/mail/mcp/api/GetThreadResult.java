package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@Schema(description = "A page of a conversation.")
public record GetThreadResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "Message key the conversation was requested for.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Messages in the conversation; copies of one message count once.", requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
        @Schema(description = "Position of this page.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Pass as offset to get the next page; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer nextOffset,
        @Schema(description = "Messages, oldest first; undated last.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ThreadMessageInfo> messages
) { }
