package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An attachment of the message.")
public record AttachmentRef(
        @Schema(description = "Identifier to pass to getAttachment as file; also its name in attachments/.", requiredMode = Schema.RequiredMode.REQUIRED)
        String file,
        @Schema(description = "Original file name.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "MIME type, e.g. application/pdf.", requiredMode = Schema.RequiredMode.REQUIRED)
        String contentType,
        @Schema(description = "Size in bytes.", requiredMode = Schema.RequiredMode.REQUIRED)
        long size,
        @Schema(description = "True for parts shown inside the body, such as embedded images.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean inline,
        @Schema(description = "Absolute path of the original file; absent when it is not saved.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String path,
        @Schema(description = "Absolute path of the extracted Markdown text; absent when there is no text.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String textPath,
        @Schema(description = "file of the attachment this one was unpacked from; absent for the message's own attachments.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String parent
) { }
