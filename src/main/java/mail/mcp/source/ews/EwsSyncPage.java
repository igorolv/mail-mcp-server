package mail.mcp.source.ews;

import java.util.List;
import java.util.Map;

/**
 * One page of {@code SyncFolderItems}.
 *
 * @param syncState opaque cursor returned by the server
 * @param includesLastItemInRange true when there are no more changes
 * @param created created item ids in server order
 * @param deleted deleted item ids in server order
 * @param updated ids of {@code Update} changes in server order, e.g. a follow-up flag change
 * @param readFlags {@code ReadFlagChange} values by item id; a later change of the same item wins
 */
public record EwsSyncPage(String syncState, boolean includesLastItemInRange,
                          List<String> created, List<String> deleted,
                          List<String> updated, Map<String, Boolean> readFlags) {
    public EwsSyncPage(String syncState, boolean includesLastItemInRange, List<String> created, List<String> deleted) {
        this(syncState, includesLastItemInRange, created, deleted, List.of(), Map.of());
    }
}
