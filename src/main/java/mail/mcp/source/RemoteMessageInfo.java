package mail.mcp.source;

import mail.mcp.model.MessageFingerprint;

import java.util.List;

/**
 * Metadata from one present remote item; optional identity fields and flag observations may be missing.
 * stableId: the provider's own message identifier that survives moves and UID changes (Gmail
 * X-GM-MSGID); labels: the labels the mailbox assigns to the message (Gmail labels). Both are null
 * when the provider has none or did not return them.
 */
public record RemoteMessageInfo(String ref, MessageFingerprint fingerprint, Boolean read, Boolean flagged,
                                String stableId, List<String> labels) {
    public RemoteMessageInfo(String ref, MessageFingerprint fingerprint) { this(ref, fingerprint, null, null); }

    public RemoteMessageInfo(String ref, MessageFingerprint fingerprint, Boolean read, Boolean flagged) {
        this(ref, fingerprint, read, flagged, null, null);
    }

    public RemoteMessageInfo {
        labels = labels == null ? null : List.copyOf(labels);
    }
}
