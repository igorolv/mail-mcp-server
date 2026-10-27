package mail.mcp.sync;

import mail.mcp.source.Downloaded;
import mail.mcp.source.FolderChanges;
import mail.mcp.source.MailSource;
import mail.mcp.source.MessageUnavailableException;
import mail.mcp.source.RemoteFolder;
import mail.mcp.source.RemoteMessageInfo;
import mail.mcp.model.MessageFingerprint;
import mail.mcp.source.imap.ImapException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;

/** Mutable in-memory mailbox with synthetic MIME; never opens a network connection. */
public final class FakeMailSource implements MailSource {
    private static final Comparator<String> REF_ORDER = Comparator
            .comparing((String ref) -> ref.matches("[0-9]+") ? new BigInteger(ref) : null,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Comparator.naturalOrder());
    private final Map<String, RemoteFolder> folders = new LinkedHashMap<>();
    private final Map<String, String> cursors = new LinkedHashMap<>();
    private final Map<String, Map<String, byte[]>> messages = new LinkedHashMap<>();
    private final Set<String> tooLarge = new HashSet<>();
    private int failAt;
    private final Map<String, MessageFingerprint> fingerprints = new LinkedHashMap<>();
    public int metadataCalls;
    public int failMetadataAt;
    public int foldersCalls;
    public int changesCalls;
    public int downloadCalls;

    public void putFolder(String remoteId, String path, String cursor, String... refs) {
        folders.put(remoteId, new RemoteFolder(remoteId, path));
        cursors.put(remoteId, cursor);
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (String ref : refs) { bytes.put(ref, mime(ref)); }
        messages.put(remoteId, bytes);
    }

    public void removeFolder(String remoteId) {
        folders.remove(remoteId);
        cursors.remove(remoteId);
        messages.remove(remoteId);
    }

    public void markTooLarge(String remoteId, String ref) { tooLarge.add(remoteId + "/" + ref); }

    public void failOnDownload(int call) { failAt = call; }

    public void fingerprint(String folder, String ref, MessageFingerprint fingerprint) {
        fingerprints.put(folder + "/" + ref, fingerprint);
    }

    public void putMime(String folder, String ref, byte[] bytes) { messages.get(folder).put(ref, bytes); }

    public void resetCounts() { foldersCalls = 0; changesCalls = 0; downloadCalls = 0; metadataCalls = 0; }

    public static byte[] mime(String ref) {
        return ("Subject: Synthetic message\r\n\r\nMessage " + ref + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    @Override public List<RemoteFolder> folders() {
        foldersCalls++;
        return List.copyOf(folders.values());
    }

    @Override public FolderChanges changes(RemoteFolder folder, String cursor, Set<String> known) {
        changesCalls++;
        var present = messages.get(folder.remoteId()).keySet();
        String nextCursor = cursors.get(folder.remoteId());
        boolean reset = cursor != null && !cursor.equals(nextCursor);
        var added = present.stream().filter(ref -> reset || !known.contains(ref)).sorted(REF_ORDER).toList();
        var removed = known.stream().filter(ref -> reset || !present.contains(ref)).sorted(REF_ORDER).toList();
        return new FolderChanges(added, removed, nextCursor, reset);
    }

    @Override public Map<String, RemoteMessageInfo> metadata(RemoteFolder folder, String expectedCursor, Collection<String> refs) {
        if (++metadataCalls == failMetadataAt) { throw new ImapException(ImapException.Kind.PROTOCOL); }
        var result = new LinkedHashMap<String, RemoteMessageInfo>();
        for (String ref : refs) {
            if (messages.get(folder.remoteId()).containsKey(ref)) {
                result.put(ref, new RemoteMessageInfo(ref, fingerprints.get(folder.remoteId() + "/" + ref)));
            }
        }
        return Map.copyOf(result);
    }

    @Override public Downloaded download(RemoteFolder folder, String ref, Path target) {
        downloadCalls++;
        if (downloadCalls == failAt) { throw new ImapException(ImapException.Kind.CONNECTION); }
        if (tooLarge.contains(folder.remoteId() + "/" + ref)) {
            throw new MessageUnavailableException(MessageUnavailableException.Reason.TOO_LARGE);
        }
        byte[] bytes = messages.get(folder.remoteId()).get(ref);
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Downloaded(bytes.length, hash);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    @Override public void close() { }
}
