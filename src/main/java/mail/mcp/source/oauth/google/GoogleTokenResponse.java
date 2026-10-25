package mail.mcp.source.oauth.google;

import mail.mcp.source.oauth.OAuthException;

public record GoogleTokenResponse(String accessToken, String refreshToken, long expiresIn, String scope) {
    public GoogleTokenResponse {
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > 8192
                || accessToken.indexOf('\r') >= 0 || accessToken.indexOf('\n') >= 0
                || expiresIn <= 0 || expiresIn > 86400) {
            throw new OAuthException(OAuthException.Kind.TOKEN_REQUEST);
        }
    }

    @Override public String toString() { return "GoogleTokenResponse[accessToken=***, refreshToken=***]"; }
}
