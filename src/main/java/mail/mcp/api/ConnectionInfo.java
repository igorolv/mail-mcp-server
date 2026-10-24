package mail.mcp.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One mailbox this server can be pointed at with the 'connection' tool argument.")
public record ConnectionInfo(
        @Schema(description = "Value to pass as 'connection'.", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "What this mailbox is for, as configured by the operator.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description,
        @Schema(description = "Source type: ews (Exchange) or imap. Absent when the entry has no valid type.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "Sign-in method: ntlm, basic, app-password or oauth2. Absent when it cannot be determined.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String auth,
        @Schema(description = "True when this server version can read this type of mailbox.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean sourceAvailable,
        @Schema(description = "True when a local data directory already exists for this connection.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean localDataAvailable,
        @Schema(description = "True when this connection was already opened in this server process. Says nothing about a successful sign-in.", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean initialized,
        @Schema(description = "Why this entry of the connections file cannot be used; absent when it is valid.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String configError
) {
}
