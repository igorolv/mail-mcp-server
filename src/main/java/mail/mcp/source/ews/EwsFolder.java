package mail.mcp.source.ews;

/**
 * Properties of one folder from {@code GetFolder} with the {@code Default} shape.
 *
 * @param id               server folder id; may change, never used as a local key
 * @param displayName      localized name, e.g. "Входящие"
 * @param folderClass      {@code IPF.Note} for mail folders; {@code null} when the server omits it
 * @param totalCount       items in the folder, {@code null} when absent
 * @param unreadCount      unread items, {@code null} when absent
 * @param childFolderCount direct subfolders, {@code null} when absent
 */
public record EwsFolder(
        String id,
        String displayName,
        String folderClass,
        Integer totalCount,
        Integer unreadCount,
        Integer childFolderCount
) {
}
