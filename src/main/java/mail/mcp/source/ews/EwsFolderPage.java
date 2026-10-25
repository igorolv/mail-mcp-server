package mail.mcp.source.ews;

import java.util.List;

/**
 * One page of {@code FindFolder}.
 *
 * @param folders folders in server order
 * @param includesLastItemInRange true when this page reaches the end of the listing
 */
public record EwsFolderPage(List<EwsFolderNode> folders, boolean includesLastItemInRange) { }
