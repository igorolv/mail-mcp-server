package mail.mcp.model;

import java.time.Instant;
import java.util.List;

/**
 * Contents of meta.json: what the .eml does not say about a message.
 * size and sha256: of message.eml; 0 and null when the download failed.
 * error: why the state is failed, otherwise null.
 * read and flagged: latest successful remote observations; null when unknown.
 * stableId: the provider's message identifier that survives moves and UID changes; null when none.
 * labels: mailbox labels of the latest observation; null when unknown, empty when it has none.
 */
public record MessageMeta(String key, String folderKey, String folder, String ref, long size,
                          String sha256, Instant downloadedAt, MessageState state, String error,
                          MessageFingerprint fingerprint, Boolean read, Boolean flagged,
                          String stableId, List<String> labels) {
    public MessageMeta {
        labels = labels == null ? null : List.copyOf(labels);
    }

    public MessageMeta(String key, String folderKey, String folder, String ref, long size,
                       String sha256, Instant downloadedAt, MessageState state, String error,
                       MessageFingerprint fingerprint) {
        this(key, folderKey, folder, ref, size, sha256, downloadedAt, state, error, fingerprint, null, null, null, null);
    }

    public MessageMeta(String key, String folderKey, String folder, String ref, long size,
                       String sha256, Instant downloadedAt, MessageState state, String error,
                       MessageFingerprint fingerprint, Boolean read, Boolean flagged) {
        this(key, folderKey, folder, ref, size, sha256, downloadedAt, state, error, fingerprint, read, flagged, null, null);
    }

    public MessageMeta(String key, String folderKey, String folder, String ref, long size,
                       String sha256, Instant downloadedAt, MessageState state, String error) {
        this(key, folderKey, folder, ref, size, sha256, downloadedAt, state, error, null);
    }
}
