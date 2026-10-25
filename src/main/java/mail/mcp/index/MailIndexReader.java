package mail.mcp.index;

import mail.mcp.model.MessageCard;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
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
import java.util.List;
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
                var storedDate = doc.getField("date");
                String names = doc.get("attachmentNames");
                cards.add(new MessageCard(doc.get("key"), folderKey,
                        storedDate == null ? null : Instant.ofEpochMilli(storedDate.numericValue().longValue()),
                        doc.get("from"), doc.get("subject"), names != null && !names.isEmpty(), doc.get("state")));
            }
            return List.copyOf(cards);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Override public void close() {
        try {
            try { reader.close(); }
            finally { directory.close(); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
