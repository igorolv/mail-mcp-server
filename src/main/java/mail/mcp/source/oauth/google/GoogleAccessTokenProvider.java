package mail.mcp.source.oauth.google;

import mail.mcp.source.oauth.OAuthException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Refreshes before IMAP sign-in. No browser, retries or fallback to a mailbox password. */
public final class GoogleAccessTokenProvider implements Supplier<String> {
    private final GoogleOAuthSettings settings;
    private final String username;
    private final GoogleTokenEndpoint endpoint;
    private final Clock clock;
    private String cached;
    private Instant expires = Instant.MIN;
    private OAuthException.Kind failed;

    public GoogleAccessTokenProvider(GoogleOAuthSettings settings, String username) {
        this(settings, username, new GoogleTokenHttpClient(), Clock.systemUTC());
    }

    GoogleAccessTokenProvider(GoogleOAuthSettings settings, String username, GoogleTokenEndpoint endpoint, Clock clock) {
        this.settings = settings;
        this.username = username;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override public synchronized String get() {
        if (failed != null) { throw new OAuthException(failed); }
        try { return accessToken(); }
        catch (OAuthException e) { failed = e.kind(); throw e; }
    }

    private String accessToken() {
        if (cached != null && clock.instant().plusSeconds(60).isBefore(expires)) { return cached; }
        var client = GoogleDesktopClient.read(settings.clientFile());
        var store = new GoogleTokenStore(settings.tokenFile());
        return store.locked(() -> {
            var credential = store.read(client.clientId(), username);
            var fields = new LinkedHashMap<String, String>();
            fields.put("grant_type", "refresh_token");
            fields.put("client_id", client.clientId());
            if (client.clientSecret() != null && !client.clientSecret().isBlank()) {
                fields.put("client_secret", client.clientSecret());
            }
            fields.put("refresh_token", credential.refreshToken());
            var response = endpoint.exchange(Map.copyOf(fields));
            if (response.scope() != null && !GoogleOAuthFlow.hasMailScope(response.scope())) {
                throw new OAuthException(OAuthException.Kind.AUTHORIZE);
            }
            if (response.refreshToken() != null && !response.refreshToken().isBlank()) {
                store.write(new GoogleRefreshCredential("google", client.clientId(), username,
                        GoogleOAuthFlow.MAIL_SCOPE, response.refreshToken()));
            }
            cached = response.accessToken();
            expires = clock.instant().plusSeconds(response.expiresIn());
            return cached;
        });
    }

    @Override public String toString() { return "GoogleAccessTokenProvider[credentials hidden]"; }
}
