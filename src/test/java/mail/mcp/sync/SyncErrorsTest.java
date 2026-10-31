package mail.mcp.sync;

import mail.mcp.source.ews.EwsTransportException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class SyncErrorsTest {

    private static String transport(Throwable cause) {
        return SyncErrors.describe(new EwsTransportException(
                "SyncFolderItems request failed: webmail.example.com", cause));
    }

    @Test void ewsTransportFailuresNameTheirKindWithoutTheHost() {
        assertThat(transport(new UnknownHostException("webmail.example.com")))
                .isEqualTo("EWS connection failed: server name not found; check the url and the network or VPN");
        assertThat(transport(new SSLHandshakeException("webmail.example.com: PKIX path building failed")))
                .isEqualTo("EWS connection failed: TLS handshake failed; check the url and the server certificate");
        assertThat(transport(new NoRouteToHostException("webmail.example.com")))
                .isEqualTo("EWS connection failed: server unreachable; check the network or VPN");
        assertThat(transport(new ConnectException("webmail.example.com refused")))
                .isEqualTo("EWS connection failed: connection refused; check the url and the network or VPN");
        assertThat(transport(new HttpHostConnectException("webmail.example.com refused")))
                .startsWith("EWS connection failed: connection refused");
        assertThat(transport(new SocketTimeoutException("Read timed out from webmail.example.com")))
                .isEqualTo("EWS connection failed: timed out; check the network or VPN");
        assertThat(transport(new ConnectTimeoutException("webmail.example.com")))
                .startsWith("EWS connection failed: timed out");
    }

    @Test void ewsTransportCauseIsFoundDeeperInTheChain() {
        assertThat(transport(new IOException("webmail.example.com", new UnknownHostException("webmail.example.com"))))
                .startsWith("EWS connection failed: server name not found");
    }

    @Test void unknownEwsTransportCauseFallsBackToItsClassName() {
        assertThat(transport(new IOException("webmail.example.com")))
                .isEqualTo("EWS connection failed: IOException; check the url and the network");
        assertThat(transport(null))
                .isEqualTo("EWS connection failed: EwsTransportException; check the url and the network");
    }
}
