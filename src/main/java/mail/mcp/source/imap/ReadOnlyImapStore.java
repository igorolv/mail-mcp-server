package mail.mcp.source.imap;

import jakarta.mail.Session;
import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.util.MailLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Checks commands immediately before Angus writes them, including its internal reconnects. */
final class ReadOnlyImapStore extends IMAPStore {
    private final AtomicBoolean authenticationStarted = new AtomicBoolean();
    private final boolean implicitTls;
    private final boolean oauth;

    ReadOnlyImapStore(Session session, boolean implicitTls, boolean oauth) {
        super(session, null, "imaps", implicitTls);
        this.implicitTls = implicitTls;
        this.oauth = oauth;
    }

    @Override
    protected IMAPProtocol newIMAPProtocol(String host, int port) throws IOException, ProtocolException {
        var logger = new MailLogger(ReadOnlyImapStore.class, "IMAP", false,
                new PrintStream(OutputStream.nullOutputStream()));
        return new IMAPProtocol("imaps", host, port, session.getProperties(), implicitTls, logger) {
            private boolean oauthAuthenticated;

            @Override public boolean isAuthenticated() { return oauthAuthenticated || super.isAuthenticated(); }

            /** Google requires an EMPTY reply to an OAuth error challenge. Angus 2.0.5's
             * built-in loop resends credentials instead, so keep this exchange explicit and bounded.
             * Protocol: https://developers.google.com/workspace/gmail/imap/xoauth2-protocol */
            @Override public synchronized void authoauth2(String username, String token) throws ProtocolException {
                var responses = new ArrayList<Response>();
                String encoded = Base64.getEncoder().encodeToString(
                        ("user=" + username + "\001auth=Bearer " + token + "\001\001").getBytes(StandardCharsets.UTF_8));
                boolean sent = hasCapability("SASL-IR"), errorAcknowledged = false;
                var args = new Argument();
                args.writeAtom("XOAUTH2");
                if (sent) { args.writeAtom(encoded); }
                try {
                    String tag = writeCommand("AUTHENTICATE", args);
                    for (int i = 0; i < 32; i++) {
                        Response response = readResponse();
                        responses.add(response);
                        if (response.isContinuation()) {
                            if (!sent) {
                                // Only the initial empty challenge requests credentials.
                                if (!response.getRest().isBlank()) { throw new ProtocolException("Unexpected OAuth challenge"); }
                                getOutputStream().write((encoded + "\r\n").getBytes(StandardCharsets.US_ASCII));
                                sent = true;
                            } else {
                                if (errorAcknowledged) { throw new ProtocolException("Repeated OAuth challenge"); }
                                getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
                                errorAcknowledged = true;
                            }
                            getOutputStream().flush();
                        } else if (response.isBYE() || (response.isTagged() && tag.equals(response.getTag()))) {
                            handleCapabilityResponse(responses.toArray(Response[]::new));
                            notifyResponseHandlers(responses.toArray(Response[]::new));
                            handleResult(response);
                            if (!sent || errorAcknowledged) { throw new ProtocolException("OAuth sign-in failed"); }
                            oauthAuthenticated = true;
                            return;
                        }
                    }
                    throw new ProtocolException("Too many OAuth responses");
                } catch (IOException e) { throw new ProtocolException("OAuth exchange interrupted"); }
            }

            @Override
            public String writeCommand(String command, Argument arguments) throws IOException, ProtocolException {
                requireReadCommand(command);
                String upper = command.toUpperCase(Locale.ROOT);
                if (oauth && (upper.equals("LOGIN") || upper.startsWith("AUTHENTICATE "))) {
                    throw new ProtocolException("Password authentication is disabled for OAuth connections");
                }
                // Constructor may call CAPABILITY before subclass fields exist; authentication
                // only happens later. One credential exchange per client, across all sockets.
                if ((upper.equals("LOGIN") || upper.startsWith("AUTHENTICATE"))
                        && !authenticationStarted.compareAndSet(false, true)) {
                    throw new ProtocolException("Automatic IMAP reauthentication is disabled");
                }
                return super.writeCommand(command, arguments);
            }
        };
    }

    static void requireReadCommand(String command) throws ProtocolException {
        String upper = command.toUpperCase(Locale.ROOT);
        if (upper.indexOf('\r') >= 0 || upper.indexOf('\n') >= 0) {
            throw new ProtocolException("Invalid IMAP command");
        }
        if (Set.of("CAPABILITY", "NOOP", "LOGOUT", "LOGIN", "AUTHENTICATE PLAIN",
                "AUTHENTICATE LOGIN", "LIST", "LSUB", "NAMESPACE", "STATUS", "EXAMINE",
                "UNSELECT").contains(upper) || upper.equals("ENABLE") || upper.equals("AUTHENTICATE")) {
            return;
        }
        // Restrict FETCH attributes as well as its verb: BODY/RFC822 can set \\Seen.
        String item = "(?:UID|FLAGS|INTERNALDATE|RFC822\\.SIZE|BODY\\.PEEK\\[\\](?:<\\d+\\.\\d+>)?)";
        if (upper.matches("(?:UID )?FETCH [0-9:,]+ \\(" + item + "(?: " + item + ")*\\)")) {
            return;
        }
        throw new ProtocolException("IMAP command is outside the read-only allow-list");
    }
}
