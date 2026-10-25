package mail.mcp.model;

/**
 * One entry of folders.json. key: local folder key ("f1", "f2", ...), assigned by sync;
 * remoteId/path: from RemoteFolder; cursor: from FolderChanges, null before the first full pass.
 */
public record LocalFolder(String key, String remoteId, String path, String cursor) { }
