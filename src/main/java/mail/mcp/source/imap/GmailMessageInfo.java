package mail.mcp.source.imap;

import java.util.List;

/**
 * Gmail items of one parsed FETCH response, normalized for storage. msgId is the decimal X-GM-MSGID;
 * labels are decoded from modified UTF-7, without {@code \All} (it names the synchronized folder
 * itself), with one leading backslash dropped from system labels ({@code \Inbox} becomes
 * {@code Inbox}) and sorted for stable comparisons. Each field is null when its item was absent or
 * unparsable.
 */
record GmailMessageInfo(String msgId, List<String> labels) {
    GmailMessageInfo {
        labels = labels == null ? null : labels.stream().filter(label -> !label.equalsIgnoreCase("\\All"))
                .map(label -> label.startsWith("\\") ? label.substring(1) : label)
                .sorted().toList();
    }
}
