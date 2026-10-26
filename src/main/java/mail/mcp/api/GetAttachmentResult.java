package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Local attachment file and extracted Markdown chunk.")
public record GetAttachmentResult(
        @Schema(description = "Local message UUID.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Stored filename from getMessage.", requiredMode = Schema.RequiredMode.REQUIRED)
        String file,
        @Schema(description = "Original decoded filename.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "MIME base content type.", requiredMode = Schema.RequiredMode.REQUIRED)
        String contentType,
        @Schema(description = "Decoded size in bytes.", requiredMode = Schema.RequiredMode.REQUIRED)
        long size,
        @Schema(description = "Absolute original file path; absent if missing.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String path,
        @Schema(description = "Absolute Markdown path; absent without extracted text.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String textPath,
        @Schema(description = "Markdown chunk with optional secret replacement; absent without extracted text.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String text,
        @Schema(description = "Requested Java character position in the returned view (after optional filtering).", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Next character position; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "Full returned Markdown length in Java characters, after optional filtering; zero without text.", requiredMode = Schema.RequiredMode.REQUIRED)
        int textLength
) { }
