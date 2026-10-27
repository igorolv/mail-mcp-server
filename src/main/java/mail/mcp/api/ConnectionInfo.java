package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A configured mailbox.")
public record ConnectionInfo(
        @Schema(description = "Value to pass as 'connection'.", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "What this mailbox is for.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description,
        @Schema(description = "Mailbox type: ews (Exchange) or imap; absent when misconfigured.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "Sign-in method: ntlm, basic, app-password or oauth2; absent when misconfigured.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String auth,
        @Schema(description = "True when sync supports this mailbox type.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean sourceAvailable,
        @Schema(description = "True when a local copy exists; when false, run sync before reading.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean localDataAvailable,
        @Schema(description = "True when this server process has already used the connection; not a sign-in result.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean initialized,
        @Schema(description = "Why this entry of the connections file cannot be used; absent when it is valid.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String configError
) {
}
