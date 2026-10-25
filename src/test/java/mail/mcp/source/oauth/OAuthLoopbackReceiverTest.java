package mail.mcp.source.oauth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider-independent callback checks, using only an IPv4 loopback endpoint. */
class OAuthLoopbackReceiverTest {
    @Test void invalidStateDuplicatesAndUnrelatedRequestsCannotCompleteAuthorization() {
        try (var receiver = new OAuthLoopbackReceiver("expected-state")) {
            assertThat(get(URI.create(receiver.redirectUri() + "?state=wrong&code=FICTION"))).isEqualTo(400);
            assertThat(get(URI.create(receiver.redirectUri() + "?state=expected-state&state=wrong&code=FICTION"))).isEqualTo(400);
            assertThat(get(URI.create(receiver.redirectUri() + "/suffix?state=expected-state&code=FICTION"))).isEqualTo(404);
            assertThat(get(URI.create(receiver.redirectUri() + "?state=expected-state&code=FICTION"))).isEqualTo(200);
            assertThat(receiver.await(Duration.ofSeconds(1))).isEqualTo("FICTION");
            assertThat(get(URI.create(receiver.redirectUri() + "?state=expected-state&code=REPLAY"))).isEqualTo(409);
        }
        try (var receiver = new OAuthLoopbackReceiver("state")) {
            assertThatThrownBy(() -> receiver.await(Duration.ofMillis(1))).isInstanceOf(OAuthException.class).hasMessageContaining("Timed out");
        }
    }

    private static int get(URI uri) {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
