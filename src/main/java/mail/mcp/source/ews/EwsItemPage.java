package mail.mcp.source.ews;

import java.util.List;

/**
 * One page of {@code FindItem}.
 *
 * @param items                   items in server order
 * @param totalItemsInView        items in the whole view, {@code null} when absent
 * @param includesLastItemInRange true when this page reaches the end of the view
 */
public record EwsItemPage(List<EwsItemRef> items, Integer totalItemsInView, boolean includesLastItemInRange) {
}
