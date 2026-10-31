package mail.mcp.source;

/**
 * A folder listed earlier in this session is no longer on the server, for example deleted or
 * renamed by the user during a long run. The session stays usable; the run skips the folder.
 */
public final class FolderGoneException extends RuntimeException {
    public FolderGoneException() { super("Folder is no longer on the server"); }
}
