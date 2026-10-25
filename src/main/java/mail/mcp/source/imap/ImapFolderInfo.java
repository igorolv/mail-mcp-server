package mail.mcp.source.imap;

import java.util.List;

/** Internal source metadata; folder names must not bypass the future output filter. */
public record ImapFolderInfo(String name, boolean holdsMessages, List<String> attributes) {
    public ImapFolderInfo { attributes = List.copyOf(attributes); }
}
