package mail.mcp.source.ews;

import mail.mcp.connections.AuthKind;
import mail.mcp.connections.Secret;

import java.net.URI;
import java.time.Duration;

/**
 * Everything the EWS source needs to reach one mailbox. Built by the connections loader from one
 * {@code connections.json} entry of type {@code ews}.
 *
 * @param url            EWS endpoint, e.g. {@code https://webmail.example.com/EWS/Exchange.asmx}
 * @param auth           {@link AuthKind#NTLM} or {@link AuthKind#BASIC}
 * @param username       login; a bare login without a domain part works with NTLM (docs/plan.md, 2.1)
 * @param password       secret, asked for only at sign-in; never logged, never returned by a tool
 * @param domain         NTLM domain; {@code null} leaves the domain field of the NTLM message empty
 *                       and lets the server use its own domain
 * @param connectTimeout TCP and TLS connect timeout
 * @param readTimeout    socket read timeout while waiting for a response
 */
public record EwsSettings(
        URI url,
        AuthKind auth,
        String username,
        Secret password,
        String domain,
        Duration connectTimeout,
        Duration readTimeout
) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(120);

    /** Settings with a literal password. */
    public EwsSettings(URI url, AuthKind auth, String username, String password, String domain,
                       Duration connectTimeout, Duration readTimeout) {
        this(url, auth, username, Secret.of(password), domain, connectTimeout, readTimeout);
    }

    /** Port of {@link #url()}, with the scheme default filled in. */
    public int port() {
        if (url.getPort() > 0) {
            return url.getPort();
        }
        return "http".equalsIgnoreCase(url.getScheme()) ? 80 : 443;
    }

    /** The password is masked: records print every component by default. */
    @Override
    public String toString() {
        return "EwsSettings[url=" + url + ", auth=" + auth.id() + ", username=" + username
                + ", password=" + (password == null ? "null" : "***") + ", domain=" + domain
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
