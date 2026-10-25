package mail.mcp.index;

import mail.mcp.model.MessageCard;
import mail.mcp.model.SearchHit;
import mail.mcp.model.SearchResults;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;

/** Read-only snapshot of the last committed catalog; never takes the writer lock. */
public final class MailIndexReader implements AutoCloseable {
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
        if (offset < 0 || limit < 1) { throw new IllegalArgumentException("Invalid offset or limit"); }
        // Phrase and whitespace syntax only; fuzzy and wildcard operators are not enabled.
        var parser = new SimpleQueryParser(MailAnalyzers.analyzer(),
                field.fields().stream().collect(Collectors.toMap(name -> name, name -> 1.0f)),
                SimpleQueryParser.PHRASE_OPERATOR | SimpleQueryParser.WHITESPACE_OPERATOR);
        parser.setDefaultOperator(BooleanClause.Occur.MUST);
        var parsed = parser.parse(query);
        if (parsed == null) { return new SearchResults(0, List.of()); }
        if (folderKey != null) {
            parsed = new BooleanQuery.Builder().add(parsed, BooleanClause.Occur.MUST)
                    .add(new PrefixQuery(new Term("ref", folderKey + "/")), BooleanClause.Occur.FILTER).build();
        }
        try {
            int total = searcher.count(parsed);
            if (offset >= total) { return new SearchResults(total, List.of()); }
            int size = (int) Math.min(total, (long) offset + limit);
            var sort = new Sort(SortField.FIELD_SCORE, new SortField("keySort", SortField.Type.STRING));
            var top = searcher.search(parsed, size, sort, true);
            var page = Arrays.copyOfRange(top.scoreDocs, offset, top.scoreDocs.length);
            var highlighter = UnifiedHighlighter.builder(searcher, MailAnalyzers.analyzer())
                    .withFormatter(new DefaultPassageFormatter("**", "**", "… ", false))
                    .withMaskedFieldsFunc(name -> "body".equals(name) ? Set.of("body_en") : Set.of())
                    .build();
            String[] snippets = highlighter.highlight("body", parsed, new TopDocs(top.totalHits, page));
            List<SearchHit> hits = new ArrayList<>();
            for (int i = 0; i < page.length; i++) {
                var doc = searcher.storedFields().document(page[i].doc);
                hits.add(new SearchHit(card(doc), page[i].score, doc.get("body") == null ? null : snippets[i]));
            }
            return new SearchResults(total, List.copyOf(hits));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public List<MessageCard> cards(String folderKey, int offset, int limit) {
        if (offset < 0 || limit < 1) { throw new IllegalArgumentException("Invalid offset or limit"); }
        if (offset >= reader.numDocs()) { return List.of(); }
        var date = new SortField("date", SortField.Type.LONG, true);
        date.setMissingValue(Long.MIN_VALUE);
        var sort = new Sort(date, new SortField("keySort", SortField.Type.STRING));
        int size = (int) Math.min(reader.numDocs(), (long) offset + limit);
        try {
            var hits = searcher.search(new PrefixQuery(new Term("ref", folderKey + "/")), size, sort).scoreDocs;
            List<MessageCard> cards = new ArrayList<>();
            for (int i = offset; i < hits.length; i++) {
                var doc = searcher.storedFields().document(hits[i].doc);
                cards.add(card(doc));
            }
            return List.copyOf(cards);
        } catch (IOException e) { throw new UncheckedIOException(e); }
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
