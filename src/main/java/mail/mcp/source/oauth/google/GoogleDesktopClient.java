package mail.mcp.source.oauth.google;

import mail.mcp.source.oauth.OAuthException;

import java.nio.file.Path;

/** Desktop clients are public clients; their downloaded JSON still stays outside the repository. */
public record GoogleDesktopClient(String clientId, String clientSecret) {
    public static GoogleDesktopClient read(Path file) {
        var root = OAuthJson.read(file);
        var installed = root == null ? null : root.get("installed");
        String id = OAuthJson.string(installed, "client_id");
        String secret = OAuthJson.string(installed, "client_secret");
        if (id == null || !id.matches("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com")) {
            throw new OAuthException(OAuthException.Kind.CONFIGURATION);
        }
        // Endpoints from downloaded JSON are deliberately not used: credentials go only to Google.
        return new GoogleDesktopClient(id, secret);
    }

    @Override public String toString() { return "GoogleDesktopClient[clientId=***, clientSecret=***]"; }
}
