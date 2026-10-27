package mail.mcp.tools;

import mail.mcp.api.GetThreadResult;
import mail.mcp.api.ThreadMessageInfo;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.index.MailIndexReader;
import mail.mcp.redaction.SecretRedactor;
import mail.mcp.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Local conversations from committed header fields; never resolves a source or requires pandoc. */
@Service
public class ThreadTools {
    private static final Logger log = LoggerFactory.getLogger(ThreadTools.class);
    private final ConnectionRegistry registry;
    private final ObjectMapper mapper;
    private final SecretRedactor redactor;

    public ThreadTools(ConnectionRegistry registry, ObjectMapper mapper) {
        this(registry, mapper, new SecretRedactor(false));
    }

    @Autowired
    public ThreadTools(ConnectionRegistry registry, ObjectMapper mapper, SecretRedactor redactor) {
        this.registry = registry;
        this.mapper = mapper;
        this.redactor = redactor;
    }

    @McpTool(description = "Read local conversation cards across folders of one connection, anchored by a local UUID. " +
            "Links use only case-sensitive Message-ID, In-Reply-To and References, including absent parents; " +
            "copies remain separate. Oldest first, undated last, then key. limit defaults to 50, capped at 200; " +
            "use nextOffset to page. No bodies or raw header IDs. Data may be incomplete during sync; " +
            "pages across calls may change. localPath is an absolute message directory. No mailbox or pandoc access.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public GetThreadResult getThread(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Local message UUID from listMessages or search") String key,
            @McpToolParam(description = "Nonnegative starting position; default 0", required = false) Integer offset,
            @McpToolParam(description = "Positive page size; default 50, at most 200", required = false) Integer limit) {
        long start = System.nanoTime();
        try {
            if (offset != null && offset < 0) { throw new IllegalArgumentException("offset must be nonnegative"); }
            if (limit != null && limit < 1) { throw new IllegalArgumentException("limit must be positive"); }
            int position = offset == null ? 0 : offset;
            int size = limit == null ? 50 : Math.min(limit, 200);
            var definition = registry.definition(connection);
            var store = new MessageStore(definition.connectionDir(), mapper);
            store.messageDir(key); // Validate the UUID before opening the index; no disk access.
            try (var reader = MailIndexReader.open(definition.connectionDir()).orElseThrow(() ->
                    new IllegalArgumentException("Message is not indexed; use listMessages"))) {
                if (!reader.current()) {
                    throw new IllegalStateException("The index was built by an older version; " +
                            "run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
                }
                var page = reader.thread(key, position, size);
                var messages = page.cards().stream().map(c -> new ThreadMessageInfo(c.key(), c.folderKey(),
                        c.date(), redactor.text(c.from()), redactor.text(c.subject()), c.hasAttachments(), c.state(),
                        store.messageDir(c.key()).toAbsolutePath().toString(), c.read(), c.flagged())).toList();
                Integer next = (long) position + messages.size() < page.total() ? position + messages.size() : null;
                ToolLogger.completed(log, "getThread", start);
                return new GetThreadResult(definition.name(), key, page.total(), position, next, messages);
            }
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "getThread", start, e.getClass().getSimpleName());
            throw e;
        }
    }
}
