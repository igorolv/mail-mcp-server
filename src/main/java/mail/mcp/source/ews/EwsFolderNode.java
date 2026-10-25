package mail.mcp.source.ews;

/**
 * One folder in a deep {@code FindFolder} listing.
 *
 * @param id server folder id
 * @param parentId server parent id
 * @param displayName localized folder name
 * @param folderClass folder class, or {@code null} when absent
 * @param kind local element name, such as {@code Folder} or {@code SearchFolder}
 */
public record EwsFolderNode(String id, String parentId, String displayName, String folderClass, String kind) { }
