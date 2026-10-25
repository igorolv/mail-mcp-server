package mail.mcp.source.imap;

import java.util.List;

/** Internal source metadata; tools expose folders through {@code api/} records, not this one. */
public record ImapFolderInfo(String name, boolean holdsMessages, List<String> attributes) {
    public ImapFolderInfo { attributes = List.copyOf(attributes); }
}
