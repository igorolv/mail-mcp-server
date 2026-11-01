package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@Schema(description = "A page of sender counts, with totals for everything the filters matched.")
public record SummarizeSendersResult(
        @Schema(description = "Connection name.", requiredMode = Schema.RequiredMode.REQUIRED)
        String connection,
        @Schema(description = "address or domain.", requiredMode = Schema.RequiredMode.REQUIRED)
        String groupBy,
        @Schema(description = "Text query; absent when not given.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String query,
        @Schema(description = "Field searched.", requiredMode = Schema.RequiredMode.REQUIRED)
        String field,
        @Schema(description = "Folder key counted; absent for all folders.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String folder,
        @Schema(description = "Applied since bound, in UTC.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant since,
        @Schema(description = "Applied until bound, in UTC.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant until,
        @Schema(description = "Applied sender address filter.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String from,
        @Schema(description = "All matching messages; copies of one message count once.", requiredMode = Schema.RequiredMode.REQUIRED)
        int messages,
        @Schema(description = "Matching messages without a parsable sender, such as failed messages; they are in no row.", requiredMode = Schema.RequiredMode.REQUIRED)
        int withoutSender,
        @Schema(description = "Matching messages without a date.", requiredMode = Schema.RequiredMode.REQUIRED)
        int undated,
        @Schema(description = "Earliest date among matching messages; absent when none has a date.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant earliest,
        @Schema(description = "Latest date among matching messages; absent when none has a date.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Instant latest,
        @Schema(description = "Sender rows across all pages.", requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
        @Schema(description = "Position of this page.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Pass as offset to get the next page; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer nextOffset,
        @Schema(description = "Senders, most messages first.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SenderInfo> senders
) { }
