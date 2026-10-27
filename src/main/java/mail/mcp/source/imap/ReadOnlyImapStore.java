package mail.mcp.source.imap;

import jakarta.mail.Session;
import jakarta.mail.Flags;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.MessageSet;
import org.eclipse.angus.mail.imap.protocol.UID;
import org.eclipse.angus.mail.imap.protocol.FLAGS;
import org.eclipse.angus.mail.imap.protocol.ENVELOPE;
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
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
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
    private final Map<Long, Flags> metadataFlags = new HashMap<>();
    private final Set<Long> metadataParsed = new HashSet<>();
    private int metadataUnparsable;

    /** Forgets the previous batch before the next metadata fetch. */
    void clearMetadataObservations() {
        metadataFlags.clear();
        metadataParsed.clear();
        metadataUnparsable = 0;
    }
    Flags metadataFlags(long uid) { return metadataFlags.get(uid); }
    /** True when this batch returned a parsed FETCH response with ENVELOPE for the UID. */
    boolean metadataParsed(long uid) { return metadataParsed.contains(uid); }
    /** Responses of this batch that Angus could not parse and skipped. */
    int metadataUnparsable() { return metadataUnparsable; }

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
            private int malformedResponses;

            // Angus skips a response it cannot parse; count it so the batch knows what is missing.
            @Override public Response readResponse() throws IOException, ProtocolException {
                try { return super.readResponse(); }
                catch (org.eclipse.angus.mail.iap.ParsingException e) {
                    malformedResponses++;
                    throw e;
                }
            }

            // Observe the actual batch response before Angus updates its message cache. Never
            // call getFlags(): an absent FLAGS item would trigger another fetch or use stale data.
            // A message whose response could not be parsed is left out of metadataParsed, and the
            // client skips it instead of calling getters that would fetch it again.
            @Override public Response[] fetch(MessageSet[] messages, String items) throws ProtocolException {
                malformedResponses = 0;
                Response[] responses = super.fetch(messages, items);
                if (items.contains("FLAGS") && items.contains("ENVELOPE")) {
                    metadataUnparsable = malformedResponses;
                    // IMAPFolder.fetch ignores CommandFailedException; surface it as a generic
                    // protocol failure so getters cannot silently issue replacement requests.
                    try { handleResult(responses[responses.length - 1]); }
                    catch (ProtocolException e) {
                        // The class tells NO from BAD or BYE; the server's text stays out.
                        throw new ProtocolException("Metadata fetch failed: " + e.getClass().getSimpleName());
                    }
                    for (var response : responses) {
                        if (response instanceof FetchResponse fetch) {
                            var uid = fetch.getItem(UID.class);
                            var flags = fetch.getItem(FLAGS.class);
                            if (uid != null && flags != null) { metadataFlags.put(uid.uid, new Flags(flags)); }
                            if (uid != null && fetch.getItem(ENVELOPE.class) != null) { metadataParsed.add(uid.uid); }
                        }
                    }
                }
                return responses;
            }

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

    static final String OUTSIDE_ALLOW_LIST = "IMAP command is outside the read-only allow-list";

    static void requireReadCommand(String command) throws ProtocolException {
        String upper = command.toUpperCase(Locale.ROOT);
        if (upper.indexOf('\r') >= 0 || upper.indexOf('\n') >= 0) {
            throw new ProtocolException("Invalid IMAP command");
        }
        // CLOSE cannot expunge an EXAMINE mailbox (RFC 3501 6.4.2); SELECT stays forbidden.
        if (Set.of("CAPABILITY", "NOOP", "LOGOUT", "LOGIN", "AUTHENTICATE PLAIN",
                "AUTHENTICATE LOGIN", "LIST", "LSUB", "NAMESPACE", "STATUS", "EXAMINE",
                "UNSELECT", "CLOSE").contains(upper) || upper.equals("ENABLE") || upper.equals("AUTHENTICATE")) {
            return;
        }
        // Restrict FETCH attributes as well as its verb: BODY/RFC822 can set \\Seen.
        String item = "(?:UID|FLAGS|INTERNALDATE|ENVELOPE|RFC822\\.SIZE|BODY\\.PEEK\\[\\](?:<\\d+\\.\\d+>)?)";
        if (upper.matches("(?:UID )?FETCH [0-9:,]+ \\(" + item + "(?: " + item + ")*\\)")) {
            return;
        }
        // The command line holds the verb, message numbers and item names; folder names are arguments.
        String shape = upper.replaceAll("[0-9][0-9:,*]*", "n");
        throw new ProtocolException(OUTSIDE_ALLOW_LIST + ": " + shape.substring(0, Math.min(120, shape.length())));
    }
}
