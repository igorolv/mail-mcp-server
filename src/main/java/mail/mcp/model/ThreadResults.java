package mail.mcp.model;

import java.util.List;

/** Exact component size and one chronological page from a committed catalog. */
public record ThreadResults(int total, List<MessageCard> cards) { }
