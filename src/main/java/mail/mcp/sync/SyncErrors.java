package mail.mcp.sync;

import mail.mcp.index.IndexLockedException;
import mail.mcp.source.ews.EwsAuthenticationException;
import mail.mcp.source.ews.EwsException;
import mail.mcp.source.ews.EwsResponseException;
import mail.mcp.source.ews.EwsSoapFaultException;
import mail.mcp.source.imap.ImapException;
import mail.mcp.source.oauth.OAuthException;

import java.io.UncheckedIOException;

/** Fixed, non-sensitive failure descriptions shared by status files and the CLI. */
public final class SyncErrors {
    private SyncErrors() { }

    public static String describe(RuntimeException e) {
        return switch (e) {
            case IndexLockedException locked -> locked.getMessage();
            case OAuthException oauth -> oauth.getMessage();
            case ImapException imap -> imap.getMessage();
            case EwsAuthenticationException ignored ->
                    "EWS sign-in rejected; check the connection locally before the next run";
            case EwsException ews -> {
                String code = switch (ews) {
                    case EwsResponseException response -> response.responseCode();
                    case EwsSoapFaultException fault -> fault.responseCode();
                    default -> null;
                };
                yield "EWS error: " + (code == null ? ews.getClass().getSimpleName() : code);
            }
            case UncheckedIOException ignored -> "local storage error; details withheld";
            default -> "unexpected local error; details withheld";
        };
    }

    public static boolean signInRejected(RuntimeException e) {
        return e instanceof EwsAuthenticationException
                || e instanceof ImapException imap && imap.kind() == ImapException.Kind.AUTHENTICATION;
    }
}
