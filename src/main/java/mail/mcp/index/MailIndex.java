package mail.mcp.index;

import mail.mcp.model.MessageMeta;
import mail.mcp.store.MessageStore;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Derived catalog for one connection, owned by the process holding Lucene's write lock. */
public final class MailIndex implements AutoCloseable {
    private final FSDirectory directory;
    private final IndexWriter writer;

    private MailIndex(FSDirectory directory, IndexWriter writer) {
        this.directory = directory;
        this.writer = writer;
    }

    public static MailIndex openWriter(Path connectionDir) {
        try {
            Path path = Files.createDirectories(connectionDir.resolve("index"));
            FSDirectory directory = FSDirectory.open(path);
            try {
                return new MailIndex(directory, new IndexWriter(directory, new IndexWriterConfig()));
            } catch (IOException e) {
                try { directory.close(); }
                catch (IOException closeError) { e.addSuppressed(closeError); }
                if (e instanceof LockObtainFailedException) { throw new IndexLockedException(); }
                throw e;
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void put(MessageMeta meta) {
        Document document = new Document();
        document.add(new StringField("key", meta.key(), Field.Store.YES));
        document.add(new StringField("ref", meta.folderKey() + "/" + meta.ref(), Field.Store.YES));
        document.add(new StringField("folder", meta.folder(), Field.Store.YES));
        document.add(new StringField("state", meta.state().id(), Field.Store.YES));
        if (meta.error() != null) { document.add(new StoredField("error", meta.error())); }
        try { writer.updateDocument(new Term("key", meta.key()), document); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void delete(String key) {
        try { writer.deleteDocuments(new Term("key", key)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void commit() {
        try { writer.commit(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public Set<String> keys() {
        try (var reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            Set<String> keys = new HashSet<>();
            for (var hit : searcher.search(new MatchAllDocsQuery(), Math.max(1, reader.numDocs())).scoreDocs) {
                keys.add(searcher.storedFields().document(hit.doc).get("key"));
            }
            return keys;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public Map<String, String> folderRefs(String folderKey) {
        String prefix = folderKey + "/";
        try (var reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            Map<String, String> refs = new HashMap<>();
            var query = new PrefixQuery(new Term("ref", prefix));
            for (var hit : searcher.search(query, Math.max(1, reader.numDocs())).scoreDocs) {
                Document document = searcher.storedFields().document(hit.doc);
                refs.put(document.get("ref").substring(prefix.length()), document.get("key"));
            }
            return refs;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void reconcile(MessageStore store) {
        Set<String> diskKeys = new HashSet<>(store.keys());
        for (String key : Set.copyOf(diskKeys)) {
            if (!store.hasMeta(key)) {
                store.delete(key);
                diskKeys.remove(key);
            }
        }
        Set<String> indexedKeys = keys();
        for (String key : indexedKeys) {
            if (!diskKeys.contains(key)) { delete(key); }
        }
        for (String key : diskKeys) {
            if (!indexedKeys.contains(key)) { store.readMeta(key).ifPresent(this::put); }
        }
        commit();
    }

    @Override public void close() {
        try {
            try { writer.close(); }
            finally { directory.close(); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
