package mail.mcp.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Progress of one folder, cumulative within the run.
 * done: false after a committed batch, true once the folder's cursor is saved
 * (or the folder was deleted because the server no longer lists it).
 * gone: the server lost the folder during this run; it was skipped with its old cursor, and the
 * next run, which no longer sees it listed, removes its local messages. done is then true as well.
 */
public record FolderProgress(String path, int downloaded, int failed, int removed,
                             boolean reset, boolean done, boolean rescanned, boolean gone) {
    @JsonCreator
    public FolderProgress(@JsonProperty("path") String path, @JsonProperty("downloaded") int downloaded,
                          @JsonProperty("failed") int failed, @JsonProperty("removed") int removed,
                          @JsonProperty("reset") boolean reset, @JsonProperty("done") boolean done,
                          @JsonProperty("rescanned") Boolean rescanned, @JsonProperty("gone") Boolean gone) {
        this(path, downloaded, failed, removed, reset, done, Boolean.TRUE.equals(rescanned), Boolean.TRUE.equals(gone));
    }

    public FolderProgress(String path, int downloaded, int failed, int removed, boolean reset, boolean done,
                          boolean rescanned) {
        this(path, downloaded, failed, removed, reset, done, rescanned, false);
    }

    public FolderProgress(String path, int downloaded, int failed, int removed, boolean reset, boolean done) {
        this(path, downloaded, failed, removed, reset, done, false, false);
    }
}
