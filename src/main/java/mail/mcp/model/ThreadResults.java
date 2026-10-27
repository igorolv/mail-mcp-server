package mail.mcp.model;

import java.util.List;
import java.util.Map;

/** Exact group count and one chronological page from a committed catalog. */
public record ThreadResults(int total, List<MessageCard> cards, Map<String, List<MessageCopy>> copies) {
    public ThreadResults(int total, List<MessageCard> cards) {
        this(total, cards, Map.of());
    }
}
