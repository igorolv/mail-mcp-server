package mail.mcp.sync;

import mail.mcp.connections.SecretUnavailableException;
import mail.mcp.index.IndexLockedException;
import mail.mcp.source.ews.EwsAuthenticationException;
import mail.mcp.source.ews.EwsException;
import mail.mcp.source.ews.EwsResponseException;
import mail.mcp.source.ews.EwsSoapFaultException;
import mail.mcp.source.ews.EwsTransportException;
import mail.mcp.source.imap.ImapException;
import mail.mcp.source.oauth.OAuthException;

import javax.net.ssl.SSLException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

/** Fixed, non-sensitive failure descriptions shared by status files and the CLI. */
public final class SyncErrors {
    private SyncErrors() { }

    public static String describe(RuntimeException e) {
        return switch (e) {
            case IndexLockedException locked -> locked.getMessage();
            case SecretUnavailableException command -> command.getMessage();
            case OAuthException oauth -> oauth.getMessage();
            case ImapException imap -> imap.getMessage();
            case EwsAuthenticationException ignored ->
                    "EWS sign-in rejected; check the connection locally before the next run";
            case EwsTransportException transport -> "EWS connection failed: " + transportCause(transport);
            case EwsException ews -> {
                String code = switch (ews) {
                    case EwsResponseException response -> response.responseCode();
                    case EwsSoapFaultException fault -> fault.responseCode();
                    default -> null;
                };
                yield "EWS error: " + (code == null ? ews.getClass().getSimpleName() : code);
            }
            case UncheckedIOException io -> "local storage error ("
                    + (io.getCause() == null ? "IOException" : io.getCause().getClass().getSimpleName())
                    + "); details in the log";
            default -> "unexpected local error (" + e.getClass().getSimpleName() + "); details in the log";
        };
    }

    /** Local and unexpected failures have no fixed description; their stack trace goes to the log. */
    public static boolean needsStackTrace(RuntimeException e) {
        return switch (e) {
            case IndexLockedException ignored -> false;
            case SecretUnavailableException ignored -> false;
            case OAuthException ignored -> false;
            case ImapException ignored -> false;
            case EwsException ignored -> false;
            default -> true;
        };
    }

    /** Names the kind of network failure from the cause chain, never the host or the cause's message. */
    private static String transportCause(EwsTransportException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            switch (cause) {
                case UnknownHostException ignored -> {
                    return "server name not found; check the url and the network or VPN";
                }
                case SSLException ignored -> {
                    return "TLS handshake failed; check the url and the server certificate";
                }
                case NoRouteToHostException ignored -> {
                    return "server unreachable; check the network or VPN";
                }
                case ConnectException ignored -> {
                    return "connection refused; check the url and the network or VPN";
                }
                case InterruptedIOException ignored -> {
                    return "timed out; check the network or VPN";
                }
                default -> { }
            }
        }
        Throwable first = e.getCause() == null ? e : e.getCause();
        return first.getClass().getSimpleName() + "; check the url and the network";
    }

    public static boolean signInRejected(RuntimeException e) {
        return e instanceof EwsAuthenticationException
                || e instanceof ImapException imap && imap.kind() == ImapException.Kind.AUTHENTICATION;
    }
}
