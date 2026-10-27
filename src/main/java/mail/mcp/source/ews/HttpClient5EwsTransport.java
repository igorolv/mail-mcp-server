package mail.mcp.source.ews;

import mail.mcp.connections.AuthKind;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.NTCredentials;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.auth.BasicSchemeFactory;
import org.apache.hc.client5.http.impl.auth.NTLMSchemeFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EWS transport on Apache HttpClient 5 classic with NTLM registered explicitly (decision P1).
 *
 * <p>HttpClient 5.3+ marks NTLM deprecated and leaves it out of the default scheme registry, so this
 * class builds its own registry that contains <em>only</em> the configured scheme: NTLM for
 * {@code auth: ntlm}, Basic for {@code auth: basic}. Negotiate/Kerberos is never tried. Credentials
 * are scoped to the host and port of the EWS URL. A bare login gets no domain, which leaves the domain
 * field of the NTLM message empty and lets the server apply its own domain — the combination that
 * signed in from .NET (docs/results.md, 1).
 *
 * <p>One client, one connection and one {@link HttpClientContext} per mailbox: NTLM authenticates
 * the TCP connection, and reusing the context keeps the authenticated connection for later
 * requests. Automatic retries and redirects are off: a rejected sign-in is reported once, and
 * credentials never follow a redirect to another host.
 */
@SuppressWarnings("deprecation") // NTLMSchemeFactory and NTCredentials: deprecated, still the chosen transport (P1)
public final class HttpClient5EwsTransport implements EwsTransport {

    private static final ContentType SOAP_XML = ContentType.create("text/xml", StandardCharsets.UTF_8);

    private final EwsSettings settings;
    private final CloseableHttpClient client;
    private final HttpClientContext context = HttpClientContext.create();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger authorizedRequests = new AtomicInteger();
    // Filled before the first request: a password command runs at sign-in, not when a session opens.
    private final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
    private boolean credentialsSet;

    public HttpClient5EwsTransport(EwsSettings settings) {
        this.settings = settings;
        this.client = buildClient(settings, credentialsProvider, requests, authorizedRequests);
    }

    /** The scheme registry: only the configured scheme, so the client cannot fall back to another. */
    static Lookup<AuthSchemeFactory> authSchemes(AuthKind auth) {
        return switch (auth) {
            case NTLM -> RegistryBuilder.<AuthSchemeFactory>create()
                    .register(StandardAuthScheme.NTLM, NTLMSchemeFactory.INSTANCE)
                    .build();
            case BASIC -> RegistryBuilder.<AuthSchemeFactory>create()
                    .register(StandardAuthScheme.BASIC, BasicSchemeFactory.INSTANCE)
                    .build();
            default -> throw new IllegalArgumentException("auth '" + auth.id() + "' is not an EWS sign-in method");
        };
    }

    /** Name of the configured scheme as HttpClient knows it. */
    static String schemeName(AuthKind auth) {
        return auth == AuthKind.BASIC ? StandardAuthScheme.BASIC : StandardAuthScheme.NTLM;
    }

    /**
     * Credentials for the configured scheme. NTLM keeps login and domain apart; a missing domain
     * stays {@code null}, so the NTLM messages carry an empty domain. Basic has no domain field, so a
     * configured domain is written as {@code DOMAIN\login}.
     */
    static Credentials credentials(EwsSettings settings) {
        char[] password = settings.password().value().toCharArray();
        String domain = settings.domain() == null || settings.domain().isBlank() ? null : settings.domain().trim();
        if (settings.auth() == AuthKind.BASIC) {
            String login = domain == null ? settings.username() : domain + "\\" + settings.username();
            return new UsernamePasswordCredentials(login, password);
        }
        return new NTCredentials(settings.username(), password, null, domain);
    }

    private static CloseableHttpClient buildClient(EwsSettings settings, BasicCredentialsProvider credentialsProvider,
                                                   AtomicInteger requests, AtomicInteger authorizedRequests) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setTargetPreferredAuthSchemes(List.of(schemeName(settings.auth())))
                .setConnectionRequestTimeout(Timeout.of(settings.connectTimeout()))
                .setResponseTimeout(Timeout.of(settings.readTimeout()))
                .setRedirectsEnabled(false)
                .build();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(settings.connectTimeout()))
                .setSocketTimeout(Timeout.of(settings.readTimeout()))
                .build();
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build();

        return HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultAuthSchemeRegistry(authSchemes(settings.auth()))
                .setDefaultCredentialsProvider(credentialsProvider)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .setUserAgent("mail-mcp-server")
                // Runs after the protocol stage adds Authorization, once per request on the wire.
                .addExecInterceptorAfter(ChainElement.PROTOCOL.name(), "request-counter", (request, scope, chain) -> {
                    requests.incrementAndGet();
                    if (request.containsHeader(HttpHeaders.AUTHORIZATION)) {
                        authorizedRequests.incrementAndGet();
                    }
                    return chain.proceed(request, scope);
                })
                .build();
    }

    @Override
    public synchronized <T> T exchange(EwsOperation operation, byte[] envelope, ResponseHandler<T> handler) {
        if (!credentialsSet) {
            credentialsProvider.setCredentials(
                    new AuthScope(settings.url().getHost(), settings.port()), credentials(settings));
            credentialsSet = true;
        }
        HttpPost post = new HttpPost(settings.url());
        post.setHeader("SOAPAction", operation.soapAction());
        post.setHeader(HttpHeaders.ACCEPT, "text/xml");
        post.setEntity(new ByteArrayEntity(envelope, SOAP_XML)); // repeatable: NTLM resends the body
        int authorizedBefore = authorizedRequests.get();
        try {
            return client.execute(post, context, response ->
                    handle(operation, response, handler, authorizedRequests.get() > authorizedBefore));
        } catch (IOException e) {
            throw new EwsTransportException(operation.elementName() + " request failed: "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        }
    }

    private <T> T handle(EwsOperation operation, ClassicHttpResponse response, ResponseHandler<T> handler,
                         boolean credentialsSent) throws IOException {
        int status = response.getCode();
        if (status == 401) {
            String offered = offeredSchemes(response);
            String message = credentialsSent
                    ? "EWS rejected the credentials (HTTP 401, auth " + settings.auth().id() + "). Not retrying:"
                    + " repeated attempts can lock the account. Server offers: " + offered
                    : "EWS asked for a sign-in method that is not configured (HTTP 401, auth "
                    + settings.auth().id() + "); no credentials were sent. Server offers: " + offered;
            throw new EwsAuthenticationException(message, offered);
        }
        HttpEntity entity = response.getEntity();
        boolean xml = entity != null && entity.getContentType() != null
                && entity.getContentType().toLowerCase(Locale.ROOT).contains("xml");
        if (status == 200 || (status == 500 && xml)) {
            if (entity == null) {
                throw new EwsProtocolException(operation.elementName() + " response has no body (HTTP " + status + ")");
            }
            T result;
            try (InputStream body = entity.getContent()) {
                result = handler.handle(body);
            } catch (XMLStreamException e) {
                throw new EwsProtocolException(operation.elementName() + " response is not valid XML: "
                        + e.getMessage(), e);
            }
            if (status == 200) {
                return result;
            }
        }
        throw new EwsHttpException(status, operation.elementName() + " failed with HTTP " + status
                + (response.getReasonPhrase() == null ? "" : " " + response.getReasonPhrase()));
    }

    /** Scheme names from {@code WWW-Authenticate}, without challenge data. */
    private static String offeredSchemes(ClassicHttpResponse response) {
        Set<String> schemes = new LinkedHashSet<>();
        for (Header header : response.getHeaders(HttpHeaders.WWW_AUTHENTICATE)) {
            for (String part : header.getValue().split(",")) {
                // "Basic realm=..." names a scheme; a bare "charset=..." continues the previous one.
                String token = part.trim().split("\\s+", 2)[0];
                if (!token.isEmpty() && !token.contains("=")) {
                    schemes.add(token);
                }
            }
        }
        return schemes.isEmpty() ? "none" : String.join(", ", new ArrayList<>(schemes));
    }

    @Override
    public int requestCount() {
        return requests.get();
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException ignored) {
            // closing a pooled client only releases sockets
        }
    }
}
