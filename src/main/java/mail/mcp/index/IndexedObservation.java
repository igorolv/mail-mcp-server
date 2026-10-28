package mail.mcp.index;

import java.util.List;

/**
 * What the index holds about one message's remote observations: read/star, labels and the provider
 * stableId, each null when unknown.
 */
public record IndexedObservation(String key, Boolean read, Boolean flagged, List<String> labels, String stableId) { }
