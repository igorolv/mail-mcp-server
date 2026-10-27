package mail.mcp.source;

import mail.mcp.model.MessageFingerprint;

/** Metadata from one present remote item; optional identity fields and flag observations may be missing. */
public record RemoteMessageInfo(String ref, MessageFingerprint fingerprint, Boolean read, Boolean flagged) {
    public RemoteMessageInfo(String ref, MessageFingerprint fingerprint) { this(ref, fingerprint, null, null); }
}
