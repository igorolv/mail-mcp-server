package mail.mcp.source.imap;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import mail.mcp.connections.AuthKind;
import mail.mcp.source.oauth.google.GoogleAccessTokenProvider;
import mail.mcp.source.oauth.OAuthException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Supplier;

/** Lazy, single-connection Angus source. No mail or provider object escapes this wrapper. */
public final class ImapClient implements AutoCloseable {
    public static final long DEFAULT_MAX_MIME_BYTES = 64L * 1024 * 1024;
    private final ImapSettings settings;
    private final ReadOnlyImapStore store;
    private final Supplier<String> credential;
    private boolean connected;
    private boolean closed;

    public ImapClient(ImapSettings settings) { this(settings, true); }

    /** Plain transport is available only to package-local fake-server tests. */
    ImapClient(ImapSettings settings, boolean implicitTls) {
        this(settings, implicitTls, null);
    }

    /** Credential supplier is injectable only in package-local offline protocol tests. */
    ImapClient(ImapSettings settings, boolean implicitTls, Supplier<String> testCredential) {
        if (settings == null || !settings.tls() || (settings.auth() != AuthKind.APP_PASSWORD && settings.auth() != AuthKind.OAUTH2)) {
            throw new IllegalArgumentException("IMAP requires implicit TLS and a supported authentication kind");
        }
        this.settings = settings;
        boolean oauth = settings.auth() == AuthKind.OAUTH2;
        if (oauth && (!"imap.gmail.com".equalsIgnoreCase(settings.host()) || settings.port() != 993 || settings.oauth2() == null)
                && testCredential == null) {
            throw new IllegalArgumentException("OAuth2 requires Gmail TLS and Google OAuth settings");
        }
        this.credential = testCredential != null ? testCredential : oauth
                ? new GoogleAccessTokenProvider(settings.oauth2(), settings.username()) : settings::password;
        Logger.getLogger("org.eclipse.angus.mail").setLevel(Level.OFF);
        Logger.getLogger("jakarta.mail").setLevel(Level.OFF);
        Logger.getLogger(ReadOnlyImapStore.class.getName()).setLevel(Level.OFF);
        var properties = properties();
        if (oauth) {
            properties.setProperty("mail.imaps.auth.mechanisms", "XOAUTH2");
            properties.setProperty("mail.imaps.auth.xoauth2.disable", "false");
        }
        var session = Session.getInstance(properties);
        session.setDebug(false);
        session.setDebugOut(new PrintStream(OutputStream.nullOutputStream()));
        this.store = new ReadOnlyImapStore(session, implicitTls, oauth);
    }

    static Properties properties() {
        var p = new Properties();
        p.setProperty("mail.debug", "false");
        p.setProperty("mail.debug.auth", "false");
        p.setProperty("mail.imaps.ssl.checkserveridentity", "true");
        p.setProperty("mail.imaps.connectiontimeout", "15000");
        p.setProperty("mail.imaps.timeout", "120000");
        p.setProperty("mail.imaps.writetimeout", "15000");
        p.setProperty("mail.imaps.peek", "true");
        p.setProperty("mail.imaps.partialfetch", "true");
        p.setProperty("mail.imaps.fetchsize", "16384");
        p.setProperty("mail.imaps.connectionpoolsize", "1");
        p.setProperty("mail.imaps.separatestoreconnection", "false");
        p.setProperty("mail.imaps.auth.mechanisms", "PLAIN LOGIN");
        p.setProperty("mail.imaps.sasl.enable", "false");
        return p;
    }

    private void connect() throws MessagingException {
        if (closed) { throw new ImapException(ImapException.Kind.CLOSED); }
        if (!connected) {
            String secret;
            try { secret = credential.get(); }
            catch (OAuthException e) { close(); throw e; }
            store.connect(settings.host(), settings.port(), settings.username(), secret);
            connected = true;
        }
    }

    public synchronized List<ImapFolderInfo> listFolders() {
        try {
            connect();
            var result = new ArrayList<ImapFolderInfo>();
            for (Folder folder : store.getDefaultFolder().list("*")) {
                result.add(new ImapFolderInfo(folder.getFullName(),
                        (folder.getType() & Folder.HOLDS_MESSAGES) != 0,
                        List.of(((IMAPFolder) folder).getAttributes())));
            }
            return List.copyOf(result);
        } catch (MessagingException e) { throw fail(e); }
    }

    /** Last message by mailbox sequence order, not by a possibly forged Date header. */
    public synchronized Optional<ImapMimeItem> downloadLastMessage(String folderName, Path target, long maxBytes) {
        if (maxBytes <= 0) { throw new IllegalArgumentException("MIME byte limit must be positive"); }
        if (folderName == null || folderName.isBlank()) { throw new IllegalArgumentException("Folder is required"); }
        IMAPFolder folder = null;
        Path partial = null;
        try {
            connect();
            folder = (IMAPFolder) store.getFolder(folderName);
            folder.open(Folder.READ_ONLY); // EXAMINE, never SELECT
            int count = folder.getMessageCount();
            if (count == 0) { return Optional.empty(); }
            var message = (IMAPMessage) folder.getMessage(count);
            message.setPeek(true);
            var profile = new FetchProfile();
            profile.add(UIDFolder.FetchProfileItem.UID);
            profile.add(FetchProfile.Item.FLAGS);
            folder.fetch(new Message[] {message}, profile);
            long uid = folder.getUID(message);
            long uidValidity = folder.getUIDValidity();
            if (uid <= 0 || uidValidity <= 0) { throw new ImapException(ImapException.Kind.PROTOCOL); }
            boolean before = message.isSet(Flags.Flag.SEEN);
            Path absolute = target.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            partial = Files.createTempFile(absolute.getParent(), ".imap-", ".part");
            MessageDigest digest = sha256();
            long bytes = 0;
            try (InputStream in = message.getMimeStream(); OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n > maxBytes - bytes) { throw new ImapException(ImapException.Kind.LIMIT); }
                    bytes += n;
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                }
            }
            if (bytes == 0) { throw new ImapException(ImapException.Kind.PROTOCOL); }
            // Invalidate Angus's cached flags and fetch them again from the server.
            message.invalidateHeaders();
            folder.fetch(new Message[] {message}, flagsProfile());
            boolean after = message.isSet(Flags.Flag.SEEN);
            if (before != after) { throw new ImapException(ImapException.Kind.PROTOCOL); }
            Files.move(partial, absolute); // no replacement: never overwrite another original
            partial = null;
            return Optional.of(new ImapMimeItem(uidValidity, uid, bytes,
                    HexFormat.of().formatHex(digest.digest()), before, after, absolute));
        } catch (MessagingException e) { throw fail(e); }
        catch (IOException e) { close(); throw new ImapException(ImapException.Kind.LOCAL_IO); }
        catch (ImapException e) { close(); throw e; }
        finally {
            if (folder != null && folder.isOpen()) {
                try { folder.forceClose(); } catch (MessagingException ignored) { /* no CLOSE/EXPUNGE */ }
            }
            // A download ends this single-socket session. Reusing it must never cause
            // Angus to authenticate a replacement socket behind the caller's back.
            close();
            if (partial != null) {
                try { Files.deleteIfExists(partial); } catch (IOException ignored) { /* local partial only */ }
            }
        }
    }

    private static FetchProfile flagsProfile() {
        var profile = new FetchProfile();
        profile.add(FetchProfile.Item.FLAGS);
        return profile;
    }

    private ImapException fail(MessagingException e) {
        ImapException.Kind kind = e instanceof AuthenticationFailedException
                ? ImapException.Kind.AUTHENTICATION
                : connected ? ImapException.Kind.PROTOCOL : ImapException.Kind.CONNECTION;
        close();
        return new ImapException(kind);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }

    @Override
    public synchronized void close() {
        closed = true;
        try { store.close(); } catch (MessagingException ignored) { /* never retry shutdown */ }
    }
}
