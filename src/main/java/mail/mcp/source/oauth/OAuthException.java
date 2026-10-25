package mail.mcp.source.oauth;

/** Fixed messages only: never retain provider bodies, tokens, codes, usernames or client secrets. */
public final class OAuthException extends RuntimeException {
    public enum Kind { CONFIGURATION, AUTHORIZE, BUSY, INVALID_GRANT, DENIED, TOKEN_REQUEST, TIMEOUT, BROWSER, LOCAL_IO }
    private final Kind kind;

    public OAuthException(Kind kind) {
        super(switch (kind) {
            case CONFIGURATION -> "Check the local OAuth client configuration and connection settings";
            case AUTHORIZE -> "OAuth authorization is required; authorize this connection again";
            case BUSY -> "OAuth credentials are in use by another process; try again later";
            case INVALID_GRANT -> "OAuth refresh token is expired or revoked; authorize this connection again";
            case DENIED -> "OAuth authorization was denied; no tokens were saved";
            case TOKEN_REQUEST -> "OAuth token request failed; no automatic retry (provider details withheld)";
            case TIMEOUT -> "Timed out waiting for OAuth authorization; no tokens were saved";
            case BROWSER -> "Cannot open the system browser; run authorization in a desktop session";
            case LOCAL_IO -> "Cannot read or securely save the local OAuth credentials";
        });
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
