package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Attachment description and its local file when available.")
public record AttachmentRef(
        @Schema(description = "Stored filename relative to attachments/.", requiredMode = Schema.RequiredMode.REQUIRED)
        String file,
        @Schema(description = "Original decoded filename.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "MIME base content type.", requiredMode = Schema.RequiredMode.REQUIRED)
        String contentType,
        @Schema(description = "Decoded size in bytes.", requiredMode = Schema.RequiredMode.REQUIRED)
        long size,
        @Schema(description = "Whether disposition is inline.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean inline,
        @Schema(description = "Absolute attachment path; absent if the file does not exist.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String path
) { }
