package mail.mcp.source;

/** Size and SHA-256 of the raw MIME written to the requested path. */
public record Downloaded(long size, String sha256) { }
