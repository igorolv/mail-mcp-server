package mail.mcp.source.ews;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.SecretUnavailableException;
import mail.mcp.connections.Secret;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.NTCredentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicSchemeFactory;
import org.apache.hc.client5.http.impl.auth.NTLMSchemeFactory;
import org.apache.hc.core5.http.config.Lookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NTLM registration and the full NTLM handshake of {@link HttpClient5EwsTransport}, against a fake
 * EWS endpoint on the loopback interface. No mail server and no real sign-in: the fake server issues
 * a synthetic NTLM challenge and only inspects the messages the client sends.
 */
@SuppressWarnings("deprecation") // NTLMSchemeFactory and NTCredentials
class HttpClient5EwsTransportTest {

    private static final String PATH = "/EWS/Exchange.asmx";

    private HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile boolean offerNtlm = true;
    private volatile int finalStatus = 200;
    private volatile byte[] finalBody;

    private record Recorded(String authorization, String soapAction) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(PATH, this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // --- registration, no HTTP -------------------------------------------------------------------

    @Test
    void ntlmRegistryContainsOnlyNtlm() {
        Lookup<AuthSchemeFactory> schemes = HttpClient5EwsTransport.authSchemes(AuthKind.NTLM);

        assertThat(schemes.lookup("NTLM")).isInstanceOf(NTLMSchemeFactory.class);
        assertThat(schemes.lookup("Negotiate")).isNull();
        assertThat(schemes.lookup("Basic")).isNull();
        assertThat(schemes.lookup("Kerberos")).isNull();
    }

    @Test
    void basicRegistryContainsOnlyBasic() {
        Lookup<AuthSchemeFactory> schemes = HttpClient5EwsTransport.authSchemes(AuthKind.BASIC);

        assertThat(schemes.lookup("Basic")).isInstanceOf(BasicSchemeFactory.class);
        assertThat(schemes.lookup("NTLM")).isNull();
    }

    @Test
    void bareLoginGetsNtCredentialsWithoutDomain() {
        Credentials credentials = HttpClient5EwsTransport.credentials(settings(AuthKind.NTLM, null));

        assertThat(credentials).isInstanceOf(NTCredentials.class);
        NTCredentials nt = (NTCredentials) credentials;
        assertThat(nt.getUserName()).isEqualTo("user");
        assertThat(nt.getNetbiosDomain()).isNull();
        assertThat(nt.getPassword()).isEqualTo("not-a-real-password".toCharArray());
    }

    @Test
    void configuredDomainIsPassedToNtlm() {
        NTCredentials nt = (NTCredentials) HttpClient5EwsTransport.credentials(settings(AuthKind.NTLM, "example"));

        assertThat(nt.getNetbiosDomain()).isEqualTo("EXAMPLE");
        assertThat(nt.getUserName()).isEqualTo("user");
    }

    @Test
    void basicWithDomainPrefixesTheLogin() {
        Credentials credentials = HttpClient5EwsTransport.credentials(settings(AuthKind.BASIC, "example"));

        assertThat(credentials).isInstanceOf(UsernamePasswordCredentials.class);
        assertThat(((UsernamePasswordCredentials) credentials).getUserName()).isEqualTo("example\\user");
    }

    @Test
    void settingsNeverPrintThePassword() {
        assertThat(settings(AuthKind.NTLM, null).toString())
                .doesNotContain("not-a-real-password")
                .contains("password=***");
    }

    // --- handshake against the fake endpoint ------------------------------------------------------

    @Test
    void ntlmHandshakeSendsTheBareLoginWithAnEmptyDomain() throws IOException {
        finalBody = EwsResponseReaderTest.fixtureBytes("getfolder-inbox.xml");

        EwsFolder inbox;
        int requestCount;
        try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(settings(AuthKind.NTLM, null)))) {
            inbox = client.getFolder(DistinguishedFolder.INBOX);
            requestCount = client.requestCount();
        }

        assertThat(inbox.displayName()).isEqualTo("Входящие");
        assertThat(requestCount).isEqualTo(3);
        assertThat(requests).hasSize(3);
        assertThat(requests).extracting(Recorded::soapAction)
                .containsOnly("http://schemas.microsoft.com/exchange/services/2006/messages/GetFolder");
        assertThat(requests.get(0).authorization()).isNull();
        assertThat(ntlmType(requests.get(1))).isEqualTo(1);
        assertThat(ntlmType(requests.get(2))).isEqualTo(3);

        byte[] type3 = ntlmMessage(requests.get(2));
        assertThat(securityBufferLength(type3, 28)).as("NTLM domain field").isZero();
        assertThat(securityBufferText(type3, 36)).as("NTLM user field").isEqualTo("user");
        assertThat(securityBufferLength(type3, 20)).as("NTLMv2 response is longer than v1's 24 bytes")
                .isGreaterThan(24);
    }

    @Test
    void passwordIsAskedForAtTheFirstRequestOnly() throws IOException {
        finalBody = EwsResponseReaderTest.fixtureBytes("getfolder-inbox.xml");
        AtomicInteger asked = new AtomicInteger();
        Secret secret = () -> {
            asked.incrementAndGet();
            return "not-a-real-password";
        };

        try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(settingsWith(secret)))) {
            assertThat(asked).as("opening a session runs no password command").hasValue(0);
            client.getFolder(DistinguishedFolder.INBOX);
            client.getFolder(DistinguishedFolder.INBOX);
        }

        assertThat(asked).hasValue(1);
    }

    @Test
    void failedPasswordCommandSendsNoRequest() {
        Secret secret = () -> {
            throw new SecretUnavailableException("work", "exited with code 1");
        };

        try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(settingsWith(secret)))) {
            assertThatThrownBy(() -> client.getFolder(DistinguishedFolder.INBOX))
                    .isInstanceOf(SecretUnavailableException.class);
        }

        assertThat(requests).isEmpty();
    }

    @Test
    void rejectedSignInIsReportedOnceAndNotRetried() {
        finalStatus = 401;

        try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(settings(AuthKind.NTLM, null)))) {
            assertThatThrownBy(() -> client.getFolder(DistinguishedFolder.INBOX))
                    .isInstanceOf(EwsAuthenticationException.class)
                    .hasMessageContaining("rejected the credentials")
                    .hasMessageContaining("Not retrying")
                    .extracting(e -> ((EwsAuthenticationException) e).offeredSchemes())
                    .isEqualTo("Negotiate, NTLM, Basic");
            assertThat(client.requestCount()).isEqualTo(3);
        }

        // exactly one Type 3 message: one sign-in attempt
        assertThat(requests).hasSize(3);
        assertThat(requests.stream().filter(r -> r.authorization() != null && ntlmType(r) == 3)).hasSize(1);
    }

    @Test
    void serverWithoutNtlmGetsNoCredentialsAtAll() {
        offerNtlm = false;

        try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(settings(AuthKind.NTLM, null)))) {
            assertThatThrownBy(() -> client.getFolder(DistinguishedFolder.INBOX))
                    .isInstanceOf(EwsAuthenticationException.class)
                    .hasMessageContaining("no credentials were sent")
                    .extracting(e -> ((EwsAuthenticationException) e).offeredSchemes())
                    .isEqualTo("Basic");
        }

        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().authorization()).isNull();
    }

    @Test
    void soapFaultWithHttp500IsReportedAsAFault() throws IOException {
        finalStatus = 500;
        finalBody = EwsResponseReaderTest.fixtureBytes("fault-server-busy.xml");

        try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(settings(AuthKind.NTLM, null)))) {
            assertThatThrownBy(() -> client.getFolder(DistinguishedFolder.INBOX))
                    .isInstanceOf(EwsSoapFaultException.class)
                    .hasMessageContaining("ErrorServerBusy");
        }
    }

    @Test
    void otherStatusesAreHttpErrors() {
        finalStatus = 503;
        finalBody = "busy".getBytes(StandardCharsets.UTF_8);

        try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(settings(AuthKind.NTLM, null)))) {
            assertThatThrownBy(() -> client.getFolder(DistinguishedFolder.INBOX))
                    .isInstanceOf(EwsHttpException.class)
                    .extracting(e -> ((EwsHttpException) e).status())
                    .isEqualTo(503);
        }
    }

    @Test
    void getItemMimeSavesTheDecodedEml(@TempDir Path dir) throws Exception {
        finalBody = EwsResponseReaderTest.fixtureBytes("getitem-mime.xml");
        Path target = dir.resolve("work").resolve("messages").resolve("message.eml");

        EwsMimeItem mime;
        try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(settings(AuthKind.NTLM, null)))) {
            mime = client.getItemMime("AAMkAD-synthetic-item-1=", target);
        }

        byte[] expected = EwsResponseReaderTest.fixtureBytes("sample.eml");
        assertThat(Files.readAllBytes(target)).isEqualTo(expected);
        assertThat(mime.bytes()).isEqualTo(expected.length);
        assertThat(mime.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
        assertThat(mime.read()).isFalse();
        try (var files = Files.list(target.getParent())) {
            assertThat(files).containsExactly(target); // no .part file left behind
        }
    }

    // --- fake endpoint -----------------------------------------------------------------------------

    private EwsSettings settings(AuthKind auth, String domain) {
        URI url = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + PATH);
        return new EwsSettings(url, auth, "user", "not-a-real-password", domain,
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private EwsSettings settingsWith(Secret password) {
        URI url = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + PATH);
        return new EwsSettings(url, AuthKind.NTLM, "user", password, null, Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requests.add(new Recorded(authorization, exchange.getRequestHeaders().getFirst("SOAPAction")));

        if (!offerNtlm) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"fake\"");
            send(exchange, 401, null);
        } else if (authorization == null) {
            challenge(exchange);
        } else if (ntlmType(authorization) == 1) {
            exchange.getResponseHeaders().add("WWW-Authenticate",
                    "NTLM " + Base64.getEncoder().encodeToString(type2()));
            send(exchange, 401, null);
        } else if (finalStatus == 401) {
            challenge(exchange);
        } else {
            exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            send(exchange, finalStatus, finalBody);
        }
    }

    /** The anonymous challenge of the real server (docs/results.md, 1): Negotiate, NTLM and Basic. */
    private static void challenge(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("WWW-Authenticate", "Negotiate");
        exchange.getResponseHeaders().add("WWW-Authenticate", "NTLM");
        exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"fake\"");
        send(exchange, 401, null);
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /**
     * A synthetic NTLM CHALLENGE (type 2) message with a target name and target info, so the client
     * answers with NTLMv2 as it would against Exchange.
     */
    private static byte[] type2() {
        byte[] target = "EXAMPLE".getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer info = ByteBuffer.allocate(4 + target.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        info.putShort((short) 2).putShort((short) target.length).put(target); // MsvAvNbDomainName
        info.putShort((short) 0).putShort((short) 0);                         // MsvAvEOL
        byte[] targetInfo = info.array();

        int flags = 0x00000001   // NEGOTIATE_UNICODE
                | 0x00000004     // REQUEST_TARGET
                | 0x00000200     // NEGOTIATE_NTLM
                | 0x00008000     // NEGOTIATE_ALWAYS_SIGN
                | 0x00010000     // TARGET_TYPE_DOMAIN
                | 0x00080000     // NEGOTIATE_EXTENDED_SESSIONSECURITY
                | 0x00800000;    // NEGOTIATE_TARGET_INFO
        int header = 48;
        ByteBuffer msg = ByteBuffer.allocate(header + target.length + targetInfo.length).order(ByteOrder.LITTLE_ENDIAN);
        msg.put("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII));
        msg.putInt(2);
        msg.putShort((short) target.length).putShort((short) target.length).putInt(header);
        msg.putInt(flags);
        msg.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}); // server challenge
        msg.put(new byte[8]);                         // reserved
        msg.putShort((short) targetInfo.length).putShort((short) targetInfo.length).putInt(header + target.length);
        msg.put(target).put(targetInfo);
        return msg.array();
    }

    private static int ntlmType(Recorded request) {
        return ntlmType(request.authorization());
    }

    private static int ntlmType(String authorization) {
        byte[] msg = Base64.getDecoder().decode(authorization.substring("NTLM ".length()));
        assertThat(new String(msg, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("NTLMSSP\0");
        return ByteBuffer.wrap(msg, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static byte[] ntlmMessage(Recorded request) {
        return Base64.getDecoder().decode(request.authorization().substring("NTLM ".length()));
    }

    private static int securityBufferLength(byte[] msg, int offset) {
        return ByteBuffer.wrap(msg, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private static String securityBufferText(byte[] msg, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN);
        int length = buffer.getShort(offset) & 0xFFFF;
        int start = buffer.getInt(offset + 4);
        return new String(msg, start, length, StandardCharsets.UTF_16LE);
    }
}
