package mail.mcp.source;

import mail.mcp.model.MessageFingerprint;

/** Metadata from one present remote item; optional identity fields may be missing. */
public record RemoteMessageInfo(String ref, MessageFingerprint fingerprint) { }
