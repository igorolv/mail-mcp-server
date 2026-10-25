package mail.mcp.model;

import java.util.List;

/** Exact total and one page of local search hits. */
public record SearchResults(int total, List<SearchHit> hits) { }
