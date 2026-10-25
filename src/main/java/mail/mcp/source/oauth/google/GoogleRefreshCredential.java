package mail.mcp.source.oauth.google;

/** Binding prevents accidental reuse of a token for a different connection or Desktop client. */
public record GoogleRefreshCredential(String provider, String clientId, String username,
                                      String scope, String refreshToken) {
    @Override public String toString() { return "GoogleRefreshCredential[all fields hidden]"; }
}
