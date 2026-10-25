package mail.mcp.model;

/** A local message card with its BM25 score and optional body snippet. */
public record SearchHit(MessageCard card, float score, String snippet) { }
