package mail.mcp.source.oauth.google;

import mail.mcp.source.oauth.OAuthException;
import mail.mcp.source.oauth.OAuthLoopbackReceiver;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Google Desktop authorization-code flow: PKCE S256, state and an IPv4 loopback receiver. */
public final class GoogleOAuthFlow {
    public static final String MAIL_SCOPE = "https://mail.google.com/";
    private final GoogleTokenEndpoint endpoint;

    public GoogleOAuthFlow(GoogleTokenEndpoint endpoint) { this.endpoint = endpoint; }

    public void authorize(GoogleOAuthSettings settings, String username, Consumer<URI> browser) {
        var client = GoogleDesktopClient.read(settings.clientFile());
        var store = new GoogleTokenStore(settings.tokenFile());
        // Hold the OS lock across browser consent, exchange and atomic publication.
        store.locked(() -> {
            String state = random(), verifier = random();
            try (var receiver = new OAuthLoopbackReceiver(state)) {
                browser.accept(authorizationUri(client.clientId(), receiver.redirectUri(), state, verifier));
                String code = receiver.await(Duration.ofMinutes(5));
                var form = new LinkedHashMap<String, String>();
                form.put("grant_type", "authorization_code");
                form.put("client_id", client.clientId());
                if (client.clientSecret() != null && !client.clientSecret().isBlank()) {
                    form.put("client_secret", client.clientSecret());
                }
                form.put("code", code);
                form.put("code_verifier", verifier);
                form.put("redirect_uri", receiver.redirectUri().toString());
                var response = endpoint.exchange(Map.copyOf(form));
                if (!hasMailScope(response.scope()) || response.refreshToken() == null || response.refreshToken().isBlank()) {
                    throw new OAuthException(OAuthException.Kind.AUTHORIZE);
                }
                store.write(new GoogleRefreshCredential("google", client.clientId(), username,
                        MAIL_SCOPE, response.refreshToken()));
            }
            return null;
        });
    }

    static URI authorizationUri(String clientId, URI redirect, String state, String verifier) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("client_id", clientId);
        fields.put("redirect_uri", redirect.toString());
        fields.put("response_type", "code");
        fields.put("scope", MAIL_SCOPE);
        fields.put("state", state);
        fields.put("code_challenge", challenge(verifier));
        fields.put("code_challenge_method", "S256");
        fields.put("access_type", "offline");
        fields.put("prompt", "consent select_account");
        return URI.create("https://accounts.google.com/o/oauth2/v2/auth?" + GoogleTokenHttpClient.form(fields));
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    static boolean hasMailScope(String scope) {
        return scope != null && Arrays.asList(scope.split("\\s+")).contains(MAIL_SCOPE);
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
