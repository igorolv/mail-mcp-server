package mail.mcp.tools;

import mail.mcp.api.SearchHitInfo;
import mail.mcp.api.SearchResult;
import mail.mcp.api.MessageCopyInfo;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.index.MailIndexReader;
import mail.mcp.index.SearchField;
import mail.mcp.model.SearchResults;
import mail.mcp.model.DateRange;
import mail.mcp.redaction.SecretRedactor;
import mail.mcp.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Local search only; never resolves a source or requires pandoc. */
@Service
public class SearchTools {
    private static final Logger log = LoggerFactory.getLogger(SearchTools.class);
    private final ConnectionRegistry registry;
    private final ObjectMapper mapper;
    private final SecretRedactor redactor;

    public SearchTools(ConnectionRegistry registry, ObjectMapper mapper) {
        this(registry, mapper, new SecretRedactor(false));
    }

    @Autowired
    public SearchTools(ConnectionRegistry registry, ObjectMapper mapper, SecretRedactor redactor) {
        this.registry = registry;
        this.mapper = mapper;
        this.redactor = redactor;
    }

    public SearchResult search(String connection, String query, String field, String folder, Integer offset, Integer limit) {
        return search(connection, query, field, folder, offset, limit, null, null);
    }

    @McpTool(description = "Search local messages by words or date bounds. since is inclusive, until exclusive; dates are UTC midnight or datetimes with explicit offset. A date-only search sorts newest first, with score 0 and no snippet. field: any (default; every field below, including " +
            "attachment text), subject, body, from, to (including cc), attachmentName, attachmentText (text " +
            "extracted from DOCX, ODT, RTF, EPUB, HTML, CSV, PDF, XLSX, PPTX and plain-text attachments up to " +
            "20 MiB of decoded bytes, except XLSX up to 5 MiB; conversion is best effort). Compressed XLSX size " +
            "does not bound POI memory and smaller files can still be expensive. Existing text stays searchable " +
            "until mail-reprocess replaces it; mail-reindex alone retains saved text. " +
            "Every word is required; \"...\" means a phrase. Russian and English word forms are matched; " +
            "full addresses must be complete; a query that is a single full address is matched against " +
            "addresses only, not display names; file names match whole or by parts split at non-letters. " +
            "Results use local data only; listFolders.synced shows coverage. limit defaults to 20, capped at 100. " +
            "Use nextOffset to page. snippet marks matches with **. For any, body matches win, otherwise the first " +
            "matching saved attachment text is used; attachmentText uses attachments first. Other fields keep body " +
            "context. Missing local texts fall back to body context. snippetAttachmentFile is the exact file for " +
            "getAttachment; snippetAttachmentName is its display name. localPath points at files " +
            "the agent may read. Optional secret filtering changes output text only, not matching or ranking; " +
            "the complete selected text is filtered before highlighting. No reindexing is needed. Original files stay unfiltered. " +
            "Copies of one message in several folders are returned once, with the others under copies, and totals count them once.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public SearchResult search(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Text query, at most 1000 characters; optional with a date bound", required = false) String query,
            @McpToolParam(description = "any, subject, body, from, to, attachmentName, attachmentText; default any", required = false) String field,
            @McpToolParam(description = "Optional folder key from listFolders", required = false) String folder,
            @McpToolParam(description = "Nonnegative starting position; default 0", required = false) Integer offset,
            @McpToolParam(description = "Positive page size; default 20, at most 100", required = false) Integer limit,
            @McpToolParam(description = "Inclusive UTC date or datetime with offset", required = false) String since,
            @McpToolParam(description = "Exclusive UTC date or datetime with offset", required = false) String until) {
        long start = System.nanoTime();
        try {
            var range = DateRange.parse(since, until);
            if (((query == null || query.isBlank()) && !range.present()) || (query != null && query.length() > 1_000)) {
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
                        : reader.search(query, group, folder, position, size, redactor.enabled() ? redactor::text : null, store, range);
                var hits = results.hits().stream().map(hit -> {
                    var c = hit.card();
                    return new SearchHitInfo(c.key(), c.folderKey(), c.date(), redactor.text(c.from()), redactor.text(c.subject()),
                            c.hasAttachments(), c.state(), hit.score(), hit.snippet(),
                            store.messageDir(c.key()).toAbsolutePath().toString(), hit.snippetAttachmentFile(),
                            redactor.text(hit.snippetAttachmentName()), c.read(), c.flagged(),
                            hit.copies().stream().map(copy -> new MessageCopyInfo(copy.key(), copy.folderKey())).toList());
                }).toList();
                Integer next = (long) position + hits.size() < results.total() ? position + hits.size() : null;
                ToolLogger.completed(log, "search", start);
                return new SearchResult(definition.name(), query == null || query.isBlank() ? null : redactor.text(query), group.id(), folder, results.total(), position, next, hits, range.since(), range.until());
            }
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "search", start, e.getClass().getSimpleName());
            throw e;
        }
    }
}
