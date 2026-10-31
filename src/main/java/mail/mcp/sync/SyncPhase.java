package mail.mcp.sync;

/** Stages of one run in order; {@link #id()} is the value in sync-status.json and syncStatus. */
public enum SyncPhase {
    /** Saved messages are compared with the index, without network access. */
    CHECKING_LOCAL("checkingLocal"),
    /** Signing in and listing the included folders. */
    SIGNING_IN("signingIn"),
    /** Collecting changes and metadata of new messages from every folder. */
    CHECKING_FOLDERS("checkingFolders"),
    /** Applying recognized moves and flag changes. */
    APPLYING_CHANGES("applyingChanges"),
    /** Removing and downloading messages folder by folder. */
    DOWNLOADING("downloading");

    private final String id;

    SyncPhase(String id) { this.id = id; }

    public String id() { return id; }
}
