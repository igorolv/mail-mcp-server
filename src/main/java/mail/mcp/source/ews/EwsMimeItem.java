package mail.mcp.source.ews;

import java.nio.file.Path;

/**
 * An item saved as MIME by {@code GetItem} with {@code IncludeMimeContent}.
 *
 * <p>Exchange rebuilds MIME from its store: the file is a faithful message, not a byte copy of the
 * original SMTP message (docs/research/RT-03-ews-operations.md).
 *
 * @param itemId server item id the MIME belongs to
 * @param read   {@code IsRead} as returned with the MIME; {@code GetItem} does not change it
 * @param file   where the decoded {@code .eml} was written
 * @param bytes  size of the decoded MIME
 * @param sha256 hex SHA-256 of the decoded MIME
 */
public record EwsMimeItem(String itemId, Boolean read, Path file, long bytes, String sha256) {
}
