package mail.mcp.source.ews;

/**
 * The server rejected the credentials (HTTP 401 after the sign-in handshake).
 *
 * <p>Callers must not retry: every attempt with a wrong or stale password counts toward the domain
 * account lockout policy (docs/plan.md, 3.1). Synchronization stops for the connection until the
 * configuration changes or the server restarts.
 */
public class EwsAuthenticationException extends EwsException {

    private final String offeredSchemes;

    public EwsAuthenticationException(String message, String offeredSchemes) {
        super(message);
        this.offeredSchemes = offeredSchemes;
    }

    /** Authentication schemes the server offered in {@code WWW-Authenticate}, e.g. {@code Negotiate, NTLM}. */
    public String offeredSchemes() {
        return offeredSchemes;
    }
}
