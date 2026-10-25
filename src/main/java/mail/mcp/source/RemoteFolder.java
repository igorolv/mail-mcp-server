package mail.mcp.source;

/** remoteId: what the server needs to open the folder; path: human-readable full name. */
public record RemoteFolder(String remoteId, String path) { }
