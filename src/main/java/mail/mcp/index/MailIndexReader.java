package mail.mcp.index;

import mail.mcp.model.MessageCard;
import mail.mcp.model.DateRange;
import mail.mcp.model.SearchHit;
import mail.mcp.model.SearchResults;
import mail.mcp.model.ThreadResults;
import mail.mcp.store.MessageStore;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.function.UnaryOperator;

/** Read-only snapshot of the last committed catalog; never takes the writer lock. */
public final class MailIndexReader implements AutoCloseable {
    private static final Pattern FULL_ADDRESS = Pattern.compile("[^\\s\"@]+@[^\\s\"@]+\\.[^\\s\"@]+");
    private static final Pattern HEADER_ID = Pattern.compile("<[^<>\\s]+>", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> THREAD_FIELDS = Set.of("key", "messageId", "inReplyTo", "references");
    private static final Set<String> CARD_FIELDS = Set.of("key", "ref", "date", "from", "subject", "attachmentNames", "state");
    private final FSDirectory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;

    private MailIndexReader(FSDirectory directory, DirectoryReader reader) {
        this.directory = directory;
        this.reader = reader;
        this.searcher = new IndexSearcher(reader);
    }

    /** Empty when index/ has no commit yet. Opens the last commit; one reader per tool call. */
    public static Optional<MailIndexReader> open(Path connectionDir) {
        Path path = connectionDir.resolve("index");
        if (!Files.isDirectory(path)) { return Optional.empty(); }
        try {
            FSDirectory directory = FSDirectory.open(path);
            try {
                if (DirectoryReader.indexExists(directory)) {
                    return Optional.of(new MailIndexReader(directory, DirectoryReader.open(directory)));
                }
            } catch (IOException | RuntimeException e) {
                try { directory.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
                throw e;
            }
            directory.close();
            return Optional.empty();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public int count(String folderKey) {
        try { return searcher.count(new PrefixQuery(new Term("ref", folderKey + "/"))); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public boolean current() {
        try { return MailIndex.SCHEMA.equals(reader.getIndexCommit().getUserData().get("schema")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public SearchResults search(String query, SearchField field, String folderKey, int offset, int limit) {
        return search(query, field, folderKey, offset, limit, null);
    }

    /** Body-only snippets; the optional transform runs on the complete text before rendering. */
    public SearchResults search(String query, SearchField field, String folderKey, int offset, int limit,
                                UnaryOperator<String> outputText) {
        return search(query, field, folderKey, offset, limit, outputText, null);
    }

    /** Selects body or saved attachment passages after paging; never writes local files. */
    public SearchResults search(String query, SearchField field, String folderKey, int offset, int limit,
                                UnaryOperator<String> outputText, MessageStore store) {
        return search(query, field, folderKey, offset, limit, outputText, store, DateRange.parse(null, null));
    }

    public SearchResults search(String query, SearchField field, String folderKey, int offset, int limit,
                                UnaryOperator<String> outputText, MessageStore store, DateRange range) {
        if (offset < 0 || limit < 1) { throw new IllegalArgumentException("Invalid offset or limit"); }
        boolean dateOnly = query == null || query.isBlank();
        Query parsed = dateOnly ? new MatchAllDocsQuery() : parseText(query, field);
        if (parsed == null) { return new SearchResults(0, List.of()); }
        Query highlightQuery = parsed;
        Query filtered = filtered(parsed, folderKey, range);
        try {
            int total = searcher.count(filtered);
            if (offset >= total) { return new SearchResults(total, List.of()); }
            int size = (int) Math.min(total, (long) offset + limit);
            var sort = dateOnly ? dateSort() : new Sort(SortField.FIELD_SCORE, new SortField("keySort", SortField.Type.STRING));
            var top = searcher.search(filtered, size, sort, !dateOnly);
            var page = Arrays.copyOfRange(top.scoreDocs, offset, top.scoreDocs.length);
            var snippets = dateOnly ? null : new SearchSnippets(highlightQuery, outputText);
            List<SearchHit> hits = new ArrayList<>();
            for (var hit : page) {
                var doc = searcher.storedFields().document(hit.doc);
                if (dateOnly) { hits.add(new SearchHit(card(doc), 0, null, null, null)); }
                else {
                    var snippet = snippets.select(doc.get("key"), doc.get("body"), field, store);
                    hits.add(new SearchHit(card(doc), hit.score, snippet.text(), snippet.file(), snippet.name()));
                }
            }
            return new SearchResults(total, List.copyOf(hits));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static Query parseText(String query, SearchField field) {
        // Phrase and whitespace syntax only; fuzzy and wildcard operators are not enabled.
        boolean fullAddress = isFullAddress(query);
        var parser = new SimpleQueryParser(MailAnalyzers.analyzer(),
                field.fields().stream()
                        .filter(name -> !fullAddress || !Set.of("fromName", "toName", "ccName").contains(name))
                        .collect(Collectors.toMap(name -> name, name -> 1.0f)),
                SimpleQueryParser.PHRASE_OPERATOR | SimpleQueryParser.WHITESPACE_OPERATOR);
        parser.setDefaultOperator(BooleanClause.Occur.MUST);
        return parser.parse(query);
    }

    private static Query filtered(Query base, String folderKey, DateRange range) {
        var builder = new BooleanQuery.Builder().add(base, BooleanClause.Occur.MUST);
        if (folderKey != null) {
            builder.add(new PrefixQuery(new Term("ref", folderKey + "/")), BooleanClause.Occur.FILTER);
        }
        if (range.present()) {
            long lower = range.since() == null ? Long.MIN_VALUE : range.since().toEpochMilli();
            long upper = range.until() == null ? Long.MAX_VALUE : range.until().toEpochMilli();
            Query dates = range.until() != null && upper == Long.MIN_VALUE
                    ? new MatchNoDocsQuery() : LongPoint.newRangeQuery("date", lower, range.until() == null ? upper : upper - 1);
            builder.add(dates, BooleanClause.Occur.FILTER);
        }
        return builder.build();
    }

    private static Sort dateSort() {
        var date = new SortField("date", SortField.Type.LONG, true);
        date.setMissingValue(Long.MIN_VALUE);
        return new Sort(date, new SortField("keySort", SortField.Type.STRING));
    }

    public List<MessageCard> cards(String folderKey, int offset, int limit) {
        return cards(folderKey, offset, limit, DateRange.parse(null, null)).cards();
    }

    public record CardPage(int total, List<MessageCard> cards) { }

    public CardPage cards(String folderKey, int offset, int limit, DateRange range) {
        if (offset < 0 || limit < 1) { throw new IllegalArgumentException("Invalid offset or limit"); }
        Query query = filtered(new MatchAllDocsQuery(), folderKey, range);
        try {
            int total = searcher.count(query);
            if (offset >= total) { return new CardPage(total, List.of()); }
            int size = (int) Math.min(total, (long) offset + limit);
            var hits = searcher.search(query, size, dateSort()).scoreDocs;
            List<MessageCard> cards = new ArrayList<>();
            for (int i = offset; i < hits.length; i++) {
                cards.add(card(searcher.storedFields().document(hits[i].doc)));
            }
            return new CardPage(total, List.copyOf(cards));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Finds the complete header component before paging, using only this committed snapshot. */
    public ThreadResults thread(String key, int offset, int limit) {
        if (offset < 0 || limit < 1) { throw new IllegalArgumentException("Invalid offset or limit"); }
        try {
            Map<String, Integer> documents = new HashMap<>();
            Map<String, Set<String>> keyIds = new HashMap<>();
            Map<String, Set<String>> idKeys = new HashMap<>();
            for (var leaf : reader.leaves()) {
                var live = leaf.reader().getLiveDocs();
                var fields = leaf.reader().storedFields();
                for (int i = 0; i < leaf.reader().maxDoc(); i++) {
                    if (live != null && !live.get(i)) { continue; }
                    var doc = fields.document(i, THREAD_FIELDS);
                    String localKey = doc.get("key");
                    documents.put(localKey, leaf.docBase + i);
                    Set<String> ids = new HashSet<>();
                    String own = doc.get("messageId");
                    if (own != null && HEADER_ID.matcher(own.strip()).matches()) { ids.add(own.strip()); }
                    ids.addAll(replyIds(doc.get("inReplyTo")));
                    // MIME stores References as whitespace-separated values; retain bracket context across them.
                    ids.addAll(replyIds(String.join(" ", doc.getValues("references"))));
                    keyIds.put(localKey, ids);
                    for (String id : ids) { idKeys.computeIfAbsent(id, ignored -> new HashSet<>()).add(localKey); }
                }
            }
            if (!documents.containsKey(key)) {
                throw new IllegalArgumentException("Message is not indexed; use listMessages");
            }
            Set<String> component = new HashSet<>();
            Set<String> visitedIds = new HashSet<>();
            var pending = new ArrayDeque<String>();
            component.add(key);
            pending.add(key);
            while (!pending.isEmpty()) {
                for (String id : keyIds.get(pending.removeFirst())) {
                    if (!visitedIds.add(id)) { continue; }
                    for (String linked : idKeys.get(id)) {
                        if (component.add(linked)) { pending.addLast(linked); }
                    }
                }
            }
            int total = component.size();
            if (offset >= total) { return new ThreadResults(total, List.of()); }
            // Read dates from doc values, without loading off-page stored cards or bodies.
            var dateValues = MultiDocValues.getNumericValues(reader, "date");
            Map<String, Long> dates = new HashMap<>();
            for (String member : component.stream().sorted(Comparator.comparingInt(documents::get)).toList()) {
                if (dateValues != null && dateValues.advanceExact(documents.get(member))) {
                    dates.put(member, dateValues.longValue());
                }
            }
            var ordered = component.stream().sorted(Comparator
                    .comparing((String member) -> dates.get(member), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Comparator.naturalOrder())).toList();
            int end = (int) Math.min(total, (long) offset + limit);
            List<MessageCard> cards = new ArrayList<>();
            var fields = searcher.storedFields();
            for (String member : ordered.subList(offset, end)) {
                cards.add(card(fields.document(documents.get(member), CARD_FIELDS)));
            }
            return new ThreadResults(total, List.copyOf(cards));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static Set<String> replyIds(String header) {
        Set<String> ids = new HashSet<>();
        if (header == null) { return ids; }
        int depth = 0;
        int start = 0;
        boolean nested = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '<') {
                if (depth == 0) { start = i; nested = false; }
                else { nested = true; }
                depth++;
            } else if (c == '>' && depth > 0 && --depth == 0 && !nested) {
                String candidate = header.substring(start, i + 1);
                if (HEADER_ID.matcher(candidate).matches()) { ids.add(candidate); }
            }
        }
        return ids;
    }

    private static boolean isFullAddress(String query) {
        return FULL_ADDRESS.matcher(query.strip()).matches();
    }

    private static MessageCard card(Document doc) {
        var storedDate = doc.getField("date");
        String names = doc.get("attachmentNames");
        String ref = doc.get("ref");
        return new MessageCard(doc.get("key"), ref.substring(0, ref.indexOf('/')),
                storedDate == null ? null : Instant.ofEpochMilli(storedDate.numericValue().longValue()),
                doc.get("from"), doc.get("subject"), names != null && !names.isEmpty(), doc.get("state"));
    }

    @Override public void close() {
        try {
            try { reader.close(); }
            finally { directory.close(); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
