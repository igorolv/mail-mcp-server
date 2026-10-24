package mail.mcp.source.ews;

/**
 * One item from a {@code FindItem} page.
 *
 * @param id               server item id; changes when the item moves
 * @param changeKey        version of the item
 * @param dateTimeReceived ISO-8601 time as sent by the server, {@code null} when absent
 * @param read             {@code IsRead}, {@code null} for items without the property
 */
public record EwsItemRef(String id, String changeKey, String dateTimeReceived, Boolean read) {
}
