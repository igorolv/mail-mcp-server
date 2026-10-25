package mail.mcp.source.oauth.google;

import java.nio.file.Path;

/** Only file locations; creating settings must never read credentials or contact Google. */
public record GoogleOAuthSettings(Path clientFile, Path tokenFile) {
    @Override public String toString() { return "GoogleOAuthSettings[clientFile=***, tokenFile=***]"; }
}
