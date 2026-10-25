package mail.mcp.model;

/**
 * Progress of one folder, cumulative within the run.
 * done: false after a committed batch, true once the folder's cursor is saved
 * (or the folder was deleted because the server no longer lists it).
 */
public record FolderProgress(String path, int downloaded, int failed, int removed,
                             boolean reset, boolean done) { }
