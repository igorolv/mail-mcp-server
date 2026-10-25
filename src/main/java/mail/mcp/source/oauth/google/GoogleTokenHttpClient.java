package mail.mcp.source.oauth.google;

import mail.mcp.source.oauth.OAuthException;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** One bounded HTTPS POST per exchange. No raw response or exception escapes this boundary. */
public final class GoogleTokenHttpClient implements GoogleTokenEndpoint {
    private static final URI GOOGLE = URI.create("https://oauth2.googleapis.com/token");
    private final URI endpoint;

    public GoogleTokenHttpClient() { this.endpoint = GOOGLE; }

    /** Only loopback endpoints are injectable by package-local offline tests. */
    GoogleTokenHttpClient(URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) {
            throw new IllegalArgumentException("Tests require an IPv4 loopback token endpoint");
        }
        this.endpoint = endpoint;
    }

    public static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Override
    public GoogleTokenResponse exchange(Map<String, String> fields) {
        var config = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(15))
                .setResponseTimeout(Timeout.ofSeconds(30)).build();
        try (var client = HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(15)).build()).build())
                .setDefaultRequestConfig(config).build()) {
            var request = new HttpPost(endpoint);
            request.setEntity(new StringEntity(form(fields), ContentType.APPLICATION_FORM_URLENCODED));
            return client.execute(request, response -> {
                if (response.getEntity() == null) { throw new OAuthException(OAuthException.Kind.TOKEN_REQUEST); }
                byte[] raw;
                try (var in = response.getEntity().getContent()) { raw = in.readNBytes(OAuthJson.MAX_BYTES + 1); }
                if (raw.length > OAuthJson.MAX_BYTES) { throw new OAuthException(OAuthException.Kind.TOKEN_REQUEST); }
                var json = OAuthJson.MAPPER.readTree(raw);
                if (response.getCode() != 200) {
                    String error = OAuthJson.string(json, "error");
                    throw new OAuthException("invalid_grant".equals(error) ? OAuthException.Kind.INVALID_GRANT
                            : "access_denied".equals(error) ? OAuthException.Kind.DENIED
                            : "invalid_client".equals(error) || "unauthorized_client".equals(error)
                            ? OAuthException.Kind.CONFIGURATION : OAuthException.Kind.TOKEN_REQUEST);
                }
                String type = OAuthJson.string(json, "token_type");
                var expiry = json == null ? null : json.get("expires_in");
                if (!"Bearer".equalsIgnoreCase(type) || expiry == null || !expiry.isIntegralNumber()) {
                    throw new OAuthException(OAuthException.Kind.TOKEN_REQUEST);
                }
                return new GoogleTokenResponse(OAuthJson.string(json, "access_token"),
                        OAuthJson.string(json, "refresh_token"), expiry.asLong(), OAuthJson.string(json, "scope"));
            });
        } catch (OAuthException e) { throw e; }
        catch (Exception e) { throw new OAuthException(OAuthException.Kind.TOKEN_REQUEST); }
    }
}
