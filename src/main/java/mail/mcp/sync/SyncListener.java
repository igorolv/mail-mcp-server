package mail.mcp.sync;

import mail.mcp.model.FolderProgress;

/** What a run reports to its caller while it works; every method does nothing by default. */
public interface SyncListener {
    /** Local check starts; full after a run that did not complete, when every meta.json is compared. */
    default void checkingLocal(boolean full) { }

    /** Running count of saved messages compared with the index during a full local check. */
    default void checked(int count) { }

    /** Running count of catalog entries restored from disk before the mailbox is read. */
    default void restored(int count) { }

    /** Signing in and listing folders starts. */
    default void signingIn() { }

    /** The server listed this many included folders; their changes are collected next. */
    default void checkingFolders(int folders) { }

    /** A folder has new or removed messages; reported before any of them is applied. */
    default void changes(String path, int added, int removed) { }

    /** Recognized moves and flag changes are applied next. */
    default void applyingChanges() { }

    /** Folder progress after a committed batch, at folder end, or for a folder the server no longer lists. */
    default void progress(FolderProgress progress) { }
}
