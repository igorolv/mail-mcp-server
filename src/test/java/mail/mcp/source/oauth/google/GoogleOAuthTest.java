package mail.mcp.source.oauth.google;

import mail.mcp.source.oauth.OAuthException;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.net.WWWFormCodec;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No real Google requests, accounts, browser windows or credentials. */
class GoogleOAuthTest {
    @TempDir Path temp;
    private static final String CLIENT = "synthetic.apps.googleusercontent.com";
    private static final String USER = "user@example.com";
    private static final String REFRESH = "FICTION_ONLY_REFRESH";

    private GoogleOAuthSettings settings() throws Exception {
        Path client = temp.resolve("desktop-client.json");
        Files.writeString(client, """
                {"installed":{"client_id":"synthetic.apps.googleusercontent.com","client_secret":"FICTION_ONLY_CLIENT"}}
                """);
        return new GoogleOAuthSettings(client, temp.resolve("oauth/google-tokens.json"));
    }

    private static GoogleTokenResponse token(String access, String refresh) {
        return new GoogleTokenResponse(access, refresh, 3600, GoogleOAuthFlow.MAIL_SCOPE);
    }

    private static void callback(URI authorization, String response) {
        var fields = query(authorization);
        URI callback = URI.create(fields.get("redirect_uri") + "?" + GoogleTokenHttpClient.form(
                Map.of("state", fields.get("state"), response.contains("denied") ? "error" : "code", response)));
        assertThat(get(callback)).isEqualTo(200);
    }

    private static Map<String, String> query(URI uri) {
        return WWWFormCodec.parse(uri.getRawQuery(), StandardCharsets.UTF_8).stream()
                .collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));
    }

    private static int get(URI uri) {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void pkceStateExchangeAndOwnerOnlyRefreshPersistence() throws Exception {
        var settings = settings();
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> authorization = new java.util.concurrent.atomic.AtomicReference<>();
        var flow = new GoogleOAuthFlow(form -> {
            calls.incrementAndGet();
            var query = authorization.get();
            assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("client_id", CLIENT)
                    .containsEntry("code", "FICTION_CODE").containsEntry("redirect_uri", query.get("redirect_uri"));
            assertThat(form.get("code_verifier")).matches("[A-Za-z0-9_-]{43}");
            try {
                String digest = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                        .digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                assertThat(query.get("code_challenge")).isEqualTo(digest);
            } catch (Exception e) { throw new AssertionError(e); }
            return token("FICTION_ONLY_ACCESS", REFRESH);
        });
        flow.authorize(settings, USER, uri -> {
            assertThat(uri.getHost()).isEqualTo("accounts.google.com");
            assertThat(uri.toString()).doesNotContain(USER, "FICTION_ONLY_CLIENT");
            var query = query(uri);
            authorization.set(query);
            assertThat(query).containsEntry("scope", GoogleOAuthFlow.MAIL_SCOPE).containsEntry("access_type", "offline")
                    .containsEntry("code_challenge_method", "S256").containsEntry("prompt", "consent select_account");
            assertThat(query.get("state")).matches("[A-Za-z0-9_-]{43}");
            assertThat(URI.create(query.get("redirect_uri")).getHost()).isEqualTo("127.0.0.1");
            callback(uri, "FICTION_CODE");
        });
        assertThat(calls).hasValue(1);
        String saved = Files.readString(settings.tokenFile());
        assertThat(saved).contains(REFRESH).doesNotContain("FICTION_ONLY_ACCESS", "FICTION_ONLY_CLIENT");
        var store = new GoogleTokenStore(settings.tokenFile());
        assertThat(store.locked(() -> store.read(CLIENT, USER)).refreshToken()).isEqualTo(REFRESH);
        var acl = Files.getFileAttributeView(settings.tokenFile(), AclFileAttributeView.class);
        if (acl != null) {
            var owner = acl.getOwner();
            assertThat(acl.getAcl().stream().filter(a -> a.type() == AclEntryType.ALLOW))
                    .allMatch(a -> a.principal().equals(owner));
        } else { assertThat(Files.getPosixFilePermissions(settings.tokenFile())).hasSize(2); }
        assertThat(new GoogleDesktopClient(CLIENT, "hidden").toString()).doesNotContain("hidden");
        assertThat(token("secret-access", "secret-refresh").toString()).doesNotContain("secret-access", "secret-refresh");
    }

    @Test void denialNeverCallsTokenEndpointOrOverwritesExistingCredential() throws Exception {
        var settings = settings();
        var store = new GoogleTokenStore(settings.tokenFile());
        store.locked(() -> { store.write(new GoogleRefreshCredential("google", CLIENT, USER, GoogleOAuthFlow.MAIL_SCOPE, REFRESH)); return null; });
        byte[] previous = Files.readAllBytes(settings.tokenFile());
        var flow = new GoogleOAuthFlow(form -> { throw new AssertionError("Must not exchange denied code"); });
        assertThatThrownBy(() -> flow.authorize(settings, USER, uri -> callback(uri, "access_denied")))
                .isInstanceOf(OAuthException.class).hasMessageContaining("denied");
        assertThat(Files.readAllBytes(settings.tokenFile())).isEqualTo(previous);
    }

    @Test void missingRefreshOrWrongScopeDoesNotPublishCredentials() throws Exception {
        var settings = settings();
        for (var response : new GoogleTokenResponse[] {
                token("FICTION_ACCESS", null), new GoogleTokenResponse("FICTION_ACCESS", REFRESH, 3600, "gmail.readonly")}) {
            var flow = new GoogleOAuthFlow(form -> response);
            assertThatThrownBy(() -> flow.authorize(settings, USER, uri -> callback(uri, "FICTION_CODE")))
                    .isInstanceOf(OAuthException.class).hasMessageContaining("authorization is required");
            assertThat(Files.exists(settings.tokenFile())).isFalse();
        }
    }

    @Test void publicDesktopClientMayOmitSecretAndWebClientJsonIsRejected() throws Exception {
        var settings = settings();
        Files.writeString(settings.clientFile(), "{\"installed\":{\"client_id\":\"synthetic.apps.googleusercontent.com\"}}");
        var flow = new GoogleOAuthFlow(form -> {
            assertThat(form).doesNotContainKey("client_secret");
            return token("FICTION_ACCESS", REFRESH);
        });
        flow.authorize(settings, USER, uri -> callback(uri, "FICTION_CODE"));
        Files.writeString(settings.clientFile(), "{\"web\":{\"client_id\":\"synthetic.apps.googleusercontent.com\"}}");
        assertThatThrownBy(() -> GoogleDesktopClient.read(settings.clientFile()))
                .isInstanceOf(OAuthException.class).hasMessageContaining("OAuth client configuration");
    }

    @Test void refreshCachesAccessInMemoryAndPersistsRotationOnly() throws Exception {
        var settings = settings();
        var store = new GoogleTokenStore(settings.tokenFile());
        store.locked(() -> { store.write(new GoogleRefreshCredential("google", CLIENT, USER, GoogleOAuthFlow.MAIL_SCOPE, REFRESH)); return null; });
        AtomicInteger calls = new AtomicInteger();
        var clock = new TestClock();
        var provider = new GoogleAccessTokenProvider(settings, USER, form -> {
            int call = calls.incrementAndGet();
            assertThat(form).containsEntry("grant_type", "refresh_token")
                    .containsEntry("refresh_token", call == 1 ? REFRESH : "FICTION_ROTATED_1");
            return token("FICTION_ACCESS_" + call, "FICTION_ROTATED_" + call);
        }, clock);
        assertThat(provider.get()).isEqualTo("FICTION_ACCESS_1");
        assertThat(provider.get()).isEqualTo("FICTION_ACCESS_1");
        assertThat(calls).hasValue(1);
        clock.now = clock.now.plusSeconds(3600);
        assertThat(provider.get()).isEqualTo("FICTION_ACCESS_2");
        assertThat(calls).hasValue(2);
        assertThat(Files.readString(settings.tokenFile())).contains("FICTION_ROTATED_2").doesNotContain("FICTION_ACCESS_");
        assertThat(provider.toString()).doesNotContain("FICTION");
    }

    @Test void invalidGrantIsStickyAndCrossAccountOrCrossClientTokenNeverGoesToEndpoint() throws Exception {
        var settings = settings();
        var store = new GoogleTokenStore(settings.tokenFile());
        store.locked(() -> { store.write(new GoogleRefreshCredential("google", CLIENT, USER, GoogleOAuthFlow.MAIL_SCOPE, REFRESH)); return null; });
        AtomicInteger calls = new AtomicInteger();
        var failed = new GoogleAccessTokenProvider(settings, USER, form -> {
            calls.incrementAndGet(); throw new OAuthException(OAuthException.Kind.INVALID_GRANT);
        }, Clock.systemUTC());
        assertThatThrownBy(failed::get).isInstanceOf(OAuthException.class).hasMessageContaining("expired or revoked");
        assertThatThrownBy(failed::get).isInstanceOf(OAuthException.class);
        assertThat(calls).hasValue(1);
        var wrongAccount = new GoogleAccessTokenProvider(settings, "other@example.com", form -> {
            throw new AssertionError("Cross-account token must not be sent");
        }, Clock.systemUTC());
        assertThatThrownBy(wrongAccount::get).isInstanceOf(OAuthException.class).hasMessageContaining("authorization is required");
        assertThatThrownBy(() -> store.locked(() -> store.read("other.apps.googleusercontent.com", USER)))
                .isInstanceOf(OAuthException.class);
        assertThatThrownBy(() -> store.locked(() -> store.locked(() -> "unreachable")))
                .isInstanceOf(OAuthException.class).hasMessageContaining("another process");
    }

    @Test void googleHttpErrorsAndRedirectsAreBoundedSanitizedAndNeverRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("refresh_token=FICTION");
            byte[] response = "{\"error\":\"invalid_grant\",\"error_description\":\"PRIVATE_PROVIDER_TEXT user@example.com\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.createContext("/redirect", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/token");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var endpoint = new GoogleTokenHttpClient(URI.create(base + "/token"));
            assertThatThrownBy(() -> endpoint.exchange(Map.of("refresh_token", "FICTION")))
                    .isInstanceOf(OAuthException.class).hasMessageNotContaining("PRIVATE_PROVIDER_TEXT").hasMessageNotContaining(USER)
                    .hasMessageContaining("expired or revoked");
            assertThat(calls).hasValue(1);
            assertThatThrownBy(() -> new GoogleTokenHttpClient(URI.create(base + "/redirect")).exchange(Map.of("refresh_token", "FICTION")))
                    .isInstanceOf(OAuthException.class);
            assertThat(calls).hasValue(2);
        } finally { server.stop(0); }
    }

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-25T10:00:00Z");
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
