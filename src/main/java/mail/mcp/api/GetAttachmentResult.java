package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An attachment and a chunk of its text.")
public record GetAttachmentResult(
        @Schema(description = "Message key.", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Attachment file, as requested.", requiredMode = Schema.RequiredMode.REQUIRED)
        String file,
        @Schema(description = "Original file name.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "MIME type, e.g. application/pdf.", requiredMode = Schema.RequiredMode.REQUIRED)
        String contentType,
        @Schema(description = "Size in bytes.", requiredMode = Schema.RequiredMode.REQUIRED)
        long size,
        @Schema(description = "Absolute path of the original file; absent when it is not saved.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String path,
        @Schema(description = "Absolute path of the extracted Markdown text; absent when there is no text.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String textPath,
        @Schema(description = "Chunk of the extracted text as Markdown; [redacted:<kind>] marks a hidden secret. Absent when there is no text.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String text,
        @Schema(description = "Character position of this chunk.", requiredMode = Schema.RequiredMode.REQUIRED)
        int offset,
        @Schema(description = "Pass as offset to get the next chunk; absent at the end.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer nextOffset,
        @Schema(description = "Length of the whole text in characters; 0 without text.", requiredMode = Schema.RequiredMode.REQUIRED)
        int textLength
) { }
