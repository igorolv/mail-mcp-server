package mail.mcp.source.ews;

import java.util.List;

/**
 * One page of {@code SyncFolderItems}; updates and read flags are omitted.
 *
 * @param syncState opaque cursor returned by the server
 * @param includesLastItemInRange true when there are no more changes
 * @param created created item ids in server order
 * @param deleted deleted item ids in server order
 */
public record EwsSyncPage(String syncState, boolean includesLastItemInRange,
                          List<String> created, List<String> deleted) { }
