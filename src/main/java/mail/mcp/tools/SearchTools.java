package mail.mcp.tools;

import mail.mcp.api.SearchHitInfo;
import mail.mcp.api.SearchResult;
import mail.mcp.api.MessageCopyInfo;
import mail.mcp.api.SenderFolderInfo;
import mail.mcp.api.SenderInfo;
import mail.mcp.api.SummarizeSendersResult;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.index.MailIndexReader;
import mail.mcp.index.SearchField;
import mail.mcp.index.WordForms;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.model.SearchResults;
import mail.mcp.model.SenderSummary;
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
import java.util.function.Supplier;

/** Local search only; never resolves a source or requires pandoc. */
@Service
public class SearchTools {
    private static final Logger log = LoggerFactory.getLogger(SearchTools.class);
    private final ConnectionRegistry registry;
    private final ObjectMapper mapper;
    private final SecretRedactor redactor;
    private final Supplier<WordForms> wordForms;

    public SearchTools(ConnectionRegistry registry, ObjectMapper mapper) {
        this(registry, mapper, new SecretRedactor(false));
    }

    public SearchTools(ConnectionRegistry registry, ObjectMapper mapper, SecretRedactor redactor) {
        this(registry, mapper, redactor, WordForms::none);
    }

    @Autowired
    public SearchTools(ConnectionRegistry registry, ObjectMapper mapper, SecretRedactor redactor,
                       MailMcpProperties properties) {
        this(registry, mapper, redactor, () -> WordForms.load(properties.resolvedWordFormsFile()));
    }

    public SearchTools(ConnectionRegistry registry, ObjectMapper mapper, SecretRedactor redactor,
                       Supplier<WordForms> wordForms) {
        this.registry = registry;
        this.mapper = mapper;
        this.redactor = redactor;
        this.wordForms = wordForms;
    }

    public SearchResult search(String connection, String query, String field, String folder, Integer offset, Integer limit) {
        return search(connection, query, field, folder, offset, limit, null, null);
    }

    @McpTool(description = "Search locally synced messages in one connection. Choose query and field for text " +
            "search; optionally restrict by folder and dates. Text results are most relevant first. " +
            "To list a period's mail across all folders, omit query and supply since or until; results " +
            "are cards without bodies, newest first, without snippets.\n\n" +
            "To review many messages, list the cards first and choose from sender, subject, date, read " +
            "status and the attachment flag. Then search text for what cards cannot show, such as the " +
            "user's name, and open only the chosen messages with getMessage. Automated mail can hold " +
            "assignments, questions or errors, so a routine subject does not prove a message is " +
            "unimportant. When reporting, say which messages you did not open.\n\n" +
            "Subject, body and attachment text support Russian and English word forms; irregular forms " +
            "and synonyms may be missed. If results are sparse, try fewer words, other forms, synonyms " +
            "or the other language in separate calls. An empty result does not prove the mail is absent: " +
            "only synced, indexed content is searched.\n\n" +
            "Read a hit with getMessage using the same connection and the hit's key. Snippets mark " +
            "matches as **word**, but may show only part of a match or body context. If a hit has " +
            "snippetAttachmentFile, read that attachment with getAttachment using the same connection, " +
            "the hit's key and file=snippetAttachmentFile. Recognized copies are returned once; copies " +
            "lists other matching keys and folders. total and paging count these groups.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public SearchResult search(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Search terms, a quoted phrase or a full email address; at most 1000 " +
                    "characters. Words combine with AND (common stop words may be ignored); double quotes " +
                    "make a phrase. No OR/NOT, wildcards, fuzzy matching or field-prefix syntax such as from:. " +
                    "Search alternatives in separate calls. Omit only when since or until is given", required = false) String query,
            @McpToolParam(description = "Choose one: any (default; all fields below), subject, body, from, " +
                    "to (includes Cc), attachmentName (whole name or parts), attachmentText (extracted contents), " +
                    "label (mailbox label words, such as Gmail's Inbox, Sent or user labels; connections " +
                    "without labels match nothing). " +
                    "from/to search addresses and names. For an exact address match, use an unquoted full " +
                    "address: field=from, query=user@example.com. This ignores case and display names. " +
                    "any also searches content, so an address there need not be a participant. " +
                    "Without query, field has no effect", required = false) String field,
            @McpToolParam(description = "One folder key from listFolders, not its display name. Omit to search " +
                    "all folders in the connection", required = false) String folder,
            @McpToolParam(description = "Default 0. For the next page, repeat the same search arguments and use " +
                    "the previous nextOffset. Stop when there is no nextOffset", required = false) Integer offset,
            @McpToolParam(description = "Results per page: 1 to 100, default 20; larger values are capped at 100", required = false) Integer limit,
            @McpToolParam(description = "Include messages at or after this date/time, using the message Date " +
                    "header, not receipt or download time. Format: YYYY-MM-DD (UTC midnight), or ISO-8601 " +
                    "with Z or an offset, e.g. 2026-10-01T09:00:00+03:00; at most 3 fractional second digits. " +
                    "Messages without a date are excluded", required = false) String since,
            @McpToolParam(description = "Include messages strictly before this date/time; same header and format " +
                    "as since. To include a whole day, use the following day's midnight. Must be later than " +
                    "since when both are given. Messages without a date are excluded",
                    required = false) String until) {
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
            boolean textQuery = query != null && !query.isBlank();
            var forms = textQuery ? wordForms.get() : WordForms.none();
            try (var reader = MailIndexReader.open(definition.connectionDir(), forms).orElse(null)) {
                if (reader != null && !reader.current()) {
                    throw new IllegalStateException("The index was built by an older version; " +
                            "run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
                }
                if (reader != null && textQuery && !reader.wordFormsCurrent()) {
                    throw new IllegalStateException("The word forms list changed since the index was built; run " +
                            "'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
                }
                var results = reader == null ? new SearchResults(0, List.of())
                        : reader.search(query, group, folder, position, size, redactor.enabled() ? redactor::text : null, store, range);
                var hits = results.hits().stream().map(hit -> {
                    var c = hit.card();
                    return new SearchHitInfo(c.key(), c.folderKey(), c.date(), redactor.text(c.from()), redactor.text(c.subject()),
                            c.hasAttachments(), c.state(), hit.score(), hit.snippet(),
                            store.messageDir(c.key()).toAbsolutePath().toString(), hit.snippetAttachmentFile(),
                            redactor.text(hit.snippetAttachmentName()), c.read(), c.flagged(),
                            c.labels() == null ? null : redactor.texts(c.labels()),
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

    @McpTool(description = "Count locally synced messages by sender address or domain in one connection, " +
            "with each sender's dates and folders, to see who sends how much and where it is filed. " +
            "Without filters it covers the whole local mailbox; optionally restrict by folder and dates, " +
            "or by query and field with the same syntax as search, for example to preview what a mail " +
            "rule would match. The totals state what the counts cover, including messages without a date " +
            "or a parsable sender.\n\n" +
            "Counts do not show mailbox rules or when messages were moved: old messages left in a folder " +
            "do not prove that a rule fails, so compare recent dates. A sender does not define a message's " +
            "purpose; list or search that sender's cards with search before recommending anything.\n\n" +
            "messages counts recognized copies once; folders counts every stored copy, so its sum can " +
            "exceed messages.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public SummarizeSendersResult summarizeSenders(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "address (default) or domain", required = false) String groupBy,
            @McpToolParam(description = "Search terms, a quoted phrase or a full email address, as in search; " +
                    "at most 1000 characters. Omit to count all messages", required = false) String query,
            @McpToolParam(description = "Field group for query, as in search; default any. Without query, " +
                    "field has no effect", required = false) String field,
            @McpToolParam(description = "One folder key from listFolders, not its display name. Omit to count " +
                    "all folders in the connection", required = false) String folder,
            @McpToolParam(description = "Include messages at or after this date/time, using the message Date " +
                    "header, in the same format as search. Messages without a date are excluded", required = false) String since,
            @McpToolParam(description = "Include messages strictly before this date/time; same header and format " +
                    "as since. Messages without a date are excluded", required = false) String until,
            @McpToolParam(description = "Default 0. For the next page, repeat the same arguments and use the " +
                    "previous nextOffset. Stop when there is no nextOffset", required = false) Integer offset,
            @McpToolParam(description = "Senders per page: 1 to 200, default 50; larger values are capped at 200", required = false) Integer limit) {
        long start = System.nanoTime();
        try {
            var range = DateRange.parse(since, until);
            if (query != null && query.length() > 1_000) {
                throw new IllegalArgumentException("query must be at most 1000 characters");
            }
            if (groupBy != null && !List.of("address", "domain").contains(groupBy)) {
                throw new IllegalArgumentException("groupBy must be address or domain");
            }
            var group = SearchField.of(field);
            if (offset != null && offset < 0) { throw new IllegalArgumentException("offset must be nonnegative"); }
            if (limit != null && limit < 1) { throw new IllegalArgumentException("limit must be positive"); }
            int position = offset == null ? 0 : offset;
            int size = limit == null ? 50 : Math.min(limit, 200);
            boolean byDomain = "domain".equals(groupBy);
            var definition = registry.definition(connection);
            var store = new MessageStore(definition.connectionDir(), mapper);
            if (folder != null && store.folders().stream().noneMatch(f -> f.key().equals(folder))) {
                throw new IllegalArgumentException("Unknown folder key; use listFolders");
            }
            boolean textQuery = query != null && !query.isBlank();
            var forms = textQuery ? wordForms.get() : WordForms.none();
            try (var reader = MailIndexReader.open(definition.connectionDir(), forms).orElse(null)) {
                if (reader != null && !reader.current()) {
                    throw new IllegalStateException("The index was built by an older version; " +
                            "run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
                }
                if (reader != null && textQuery && !reader.wordFormsCurrent()) {
                    throw new IllegalStateException("The word forms list changed since the index was built; run " +
                            "'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
                }
                var summary = reader == null ? new SenderSummary(0, 0, 0, null, null, 0, List.of())
                        : reader.senders(query, group, folder, range, byDomain, position, size);
                var senders = summary.senders().stream().map(s -> new SenderInfo(redactor.text(s.sender()), s.messages(),
                        s.earliest(), s.latest(),
                        s.folders().stream().map(f -> new SenderFolderInfo(f.folderKey(), f.count())).toList())).toList();
                Integer next = (long) position + senders.size() < summary.total() ? position + senders.size() : null;
                ToolLogger.completed(log, "summarizeSenders", start);
                return new SummarizeSendersResult(definition.name(), byDomain ? "domain" : "address",
                        textQuery ? redactor.text(query) : null, group.id(), folder, range.since(), range.until(),
                        summary.messages(), summary.withoutSender(), summary.undated(), summary.earliest(), summary.latest(),
                        summary.total(), position, next, senders);
            }
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "summarizeSenders", start, e.getClass().getSimpleName());
            throw e;
        }
    }
}
