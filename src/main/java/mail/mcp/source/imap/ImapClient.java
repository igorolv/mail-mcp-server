package mail.mcp.source.imap;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.MessageRemovedException;
import jakarta.mail.internet.InternetAddress;
import mail.mcp.model.MessageFingerprint;
import mail.mcp.source.RemoteMessageInfo;
import mail.mcp.connections.AuthKind;
import mail.mcp.source.Downloaded;
import mail.mcp.source.MessageUnavailableException;
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
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collection;
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
    private IMAPFolder currentFolder;

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
            // Return the selected folder's socket before Angus requests a store connection for LIST.
            if (currentFolder != null) {
                currentFolder.close(false);
                currentFolder = null;
            }
            var result = new ArrayList<ImapFolderInfo>();
            for (Folder folder : store.getDefaultFolder().list("*")) {
                result.add(new ImapFolderInfo(folder.getFullName(),
                        (folder.getType() & Folder.HOLDS_MESSAGES) != 0,
                        List.of(((IMAPFolder) folder).getAttributes())));
            }
            return List.copyOf(result);
        } catch (MessagingException e) { throw fail(e); }
    }

    private IMAPFolder open(String name) throws MessagingException {
        connect();
        if (currentFolder != null) {
            if (currentFolder.getFullName().equals(name) && currentFolder.isOpen()) { return currentFolder; }
            currentFolder.close(false);
        }
        currentFolder = (IMAPFolder) store.getFolder(name);
        currentFolder.open(Folder.READ_ONLY); // EXAMINE, never SELECT
        return currentFolder;
    }

    public synchronized FolderUids folderUids(String folderName) {
        try {
            var folder = open(folderName);
            long uidValidity = folder.getUIDValidity();
            if (uidValidity <= 0) { throw new ImapException(ImapException.Kind.PROTOCOL); }
            var uids = new ArrayList<Long>();
            if (folder.getMessageCount() > 0) {
                var messages = folder.getMessages();
                var profile = new FetchProfile();
                profile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, profile);
                for (var message : messages) {
                    long uid = folder.getUID(message);
                    if (uid <= 0) { throw new ImapException(ImapException.Kind.PROTOCOL); }
                    uids.add(uid);
                }
                uids.sort(Long::compareTo);
            }
            return new FolderUids(uidValidity, uids);
        } catch (MessagingException e) { throw fail(e); }
        catch (ImapException e) { close(); throw e; }
    }

    /** Refresh EXAMINE before trusting UIDs from the preceding change snapshot. */
    public synchronized Map<String, RemoteMessageInfo> metadata(String folderName, long expectedUidValidity,
                                                               Collection<Long> uids) {
        if (folderName == null || folderName.isBlank() || expectedUidValidity <= 0
                || uids.stream().anyMatch(uid -> uid == null || uid <= 0)) {
            throw new IllegalArgumentException("Metadata requires a folder, UIDVALIDITY and positive UIDs");
        }
        var ids = List.copyOf(uids);
        try {
            if (currentFolder != null) { currentFolder.close(false); currentFolder = null; }
            var folder = open(folderName);
            if (folder.getUIDValidity() != expectedUidValidity) { throw new ImapException(ImapException.Kind.PROTOCOL); }
            var result = new LinkedHashMap<String, RemoteMessageInfo>();
            for (int i = 0; i < ids.size(); i += 100) {
                long[] batch = ids.subList(i, Math.min(i + 100, ids.size())).stream().mapToLong(Long::longValue).toArray();
                var messages = java.util.Arrays.stream(folder.getMessagesByUID(batch))
                        .filter(java.util.Objects::nonNull).filter(m -> !m.isExpunged()).toArray(Message[]::new);
                var profile = new FetchProfile();
                // Angus 2.0.5 ENVELOPE already includes INTERNALDATE and RFC822.SIZE.
                profile.add(FetchProfile.Item.ENVELOPE);
                profile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, profile);
                for (var entry : messages) {
                    var message = (IMAPMessage) entry;
                    try {
                        if (message.isExpunged()) { continue; }
                        long uid = folder.getUID(message);
                        if (uid <= 0 || java.util.Arrays.stream(batch).noneMatch(id -> id == uid)) {
                            throw new ImapException(ImapException.Kind.PROTOCOL);
                        }
                        String ref = Long.toString(uid);
                        var senders = message.getFrom();
                        String from = senders != null && senders.length == 1 && senders[0] instanceof InternetAddress address
                                && !address.isGroup() ? address.getAddress() : null;
                        var date = message.getSentDate();
                        var fingerprint = new MessageFingerprint(message.getMessageID(), message.getSizeLong(),
                                date == null ? null : date.toInstant(), from, message.getSubject());
                        if (result.putIfAbsent(ref, new RemoteMessageInfo(ref, fingerprint)) != null) {
                            throw new ImapException(ImapException.Kind.PROTOCOL);
                        }
                    } catch (MessageRemovedException e) { /* vanished since UID discovery */ }
                }
            }
            if (folder.getUIDValidity() != expectedUidValidity) { throw new ImapException(ImapException.Kind.PROTOCOL); }
            return Map.copyOf(result);
        } catch (MessagingException e) { throw fail(e); }
        catch (ImapException e) { close(); throw e; }
    }

    public synchronized Downloaded download(String folderName, long uid, Path target, long maxBytes) {
        if (maxBytes <= 0) { throw new IllegalArgumentException("MIME byte limit must be positive"); }
        if (folderName == null || folderName.isBlank()) { throw new IllegalArgumentException("Folder is required"); }
        if (uid <= 0) { throw new IllegalArgumentException("UID must be positive"); }
        Path partial = null;
        try {
            var folder = open(folderName);
            var message = (IMAPMessage) folder.getMessageByUID(uid);
            if (message == null) { throw new MessageUnavailableException(MessageUnavailableException.Reason.GONE); }
            message.setPeek(true);
            var profile = new FetchProfile();
            profile.add(FetchProfile.Item.SIZE);
            folder.fetch(new Message[] {message}, profile);
            if (message.getSizeLong() > maxBytes) {
                throw new MessageUnavailableException(MessageUnavailableException.Reason.TOO_LARGE);
            }
            Path absolute = target.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            partial = Files.createTempFile(absolute.getParent(), ".imap-", ".part");
            MessageDigest digest = sha256();
            long bytes = 0;
            try (InputStream in = message.getMimeStream(); OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n > maxBytes - bytes) {
                        throw new MessageUnavailableException(MessageUnavailableException.Reason.TOO_LARGE);
                    }
                    bytes += n;
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                }
            }
            if (bytes == 0) { throw new ImapException(ImapException.Kind.PROTOCOL); }
            Files.move(partial, absolute); // no replacement: never overwrite another original
            partial = null;
            return new Downloaded(bytes, HexFormat.of().formatHex(digest.digest()));
        } catch (MessagingException e) { throw fail(e); }
        catch (IOException e) { close(); throw new ImapException(ImapException.Kind.LOCAL_IO); }
        catch (ImapException e) { close(); throw e; }
        finally {
            if (partial != null) {
                try { Files.deleteIfExists(partial); } catch (IOException ignored) { /* local partial only */ }
            }
        }
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
