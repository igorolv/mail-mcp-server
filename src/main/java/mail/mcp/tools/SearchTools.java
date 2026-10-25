package mail.mcp.tools;

import mail.mcp.api.SearchHitInfo;
import mail.mcp.api.SearchResult;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.index.MailIndexReader;
import mail.mcp.index.SearchField;
import mail.mcp.model.SearchResults;
import mail.mcp.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Local search only; never resolves a source or requires pandoc. */
@Service
public class SearchTools {
    private static final Logger log = LoggerFactory.getLogger(SearchTools.class);
    private final ConnectionRegistry registry;
    private final ObjectMapper mapper;

    public SearchTools(ConnectionRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @McpTool(description = "Search local messages by words. field: any (default; every field below, including " +
            "attachment text), subject, body, from, to (including cc), attachmentName, attachmentText (text " +
            "extracted from DOCX, ODT, RTF, EPUB, HTML, CSV, PDF, XLSX, PPTX and plain-text attachments up to " +
            "20 MiB). Every word is required; \"...\" means a phrase. Russian and English word forms are matched; " +
            "full addresses must be complete; a query that is a single full address is matched against " +
            "addresses only, not display names; file names match whole or by parts split at non-letters. " +
            "Results use local data only; listFolders.synced shows coverage. limit defaults to 20, capped at 100. " +
            "Use nextOffset to page. snippet always comes from the body and marks body matches with **; for a " +
            "match in attachment text only, read the attachments with getAttachment. localPath points at files " +
            "the agent may read.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public SearchResult search(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Nonblank query, at most 1000 characters") String query,
            @McpToolParam(description = "any, subject, body, from, to, attachmentName, attachmentText; default any", required = false) String field,
            @McpToolParam(description = "Optional folder key from listFolders", required = false) String folder,
            @McpToolParam(description = "Nonnegative starting position; default 0", required = false) Integer offset,
            @McpToolParam(description = "Positive page size; default 20, at most 100", required = false) Integer limit) {
        long start = System.nanoTime();
        try {
            if (query == null || query.isBlank() || query.length() > 1_000) {
                throw new IllegalArgumentException("query must be nonblank and at most 1000 characters");
            }
            var group = SearchField.of(field);
            if (offset != null && offset < 0) { throw new IllegalArgumentException("offset must be nonnegative"); }
            if (limit != null && limit < 1) { throw new IllegalArgumentException("limit must be positive"); }
            int position = offset == null ? 0 : offset;
            int size = limit == null ? 20 : Math.min(limit, 100);
            var definition = registry.definition(connection);
            var store = new MessageStore(definition.connectionDir(), mapper);
            if (folder != null && store.folders().stream().noneMatch(f -> f.key().equals(folder))) {
                throw new IllegalArgumentException("Unknown folder key; use listFolders");
            }
            try (var reader = MailIndexReader.open(definition.connectionDir()).orElse(null)) {
                if (reader != null && !reader.current()) {
                    throw new IllegalStateException("The index was built by an older version; " +
                            "run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
                }
                var results = reader == null ? new SearchResults(0, List.of())
                        : reader.search(query, group, folder, position, size);
                var hits = results.hits().stream().map(hit -> {
                    var c = hit.card();
                    return new SearchHitInfo(c.key(), c.folderKey(), c.date(), c.from(), c.subject(),
                            c.hasAttachments(), c.state(), hit.score(), hit.snippet(),
                            store.messageDir(c.key()).toAbsolutePath().toString());
                }).toList();
                Integer next = (long) position + hits.size() < results.total() ? position + hits.size() : null;
                ToolLogger.completed(log, "search", start);
                return new SearchResult(definition.name(), query, group.id(), folder, results.total(), position, next, hits);
            }
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "search", start, e.getClass().getSimpleName());
            throw e;
        }
    }
}
