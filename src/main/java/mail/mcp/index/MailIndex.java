package mail.mcp.index;

import mail.mcp.model.MessageMeta;
import mail.mcp.model.MailMessage;
import mail.mcp.model.Address;
import mail.mcp.store.MessageStore;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
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
import java.util.List;
import java.util.stream.Collectors;

/** Derived catalog for one connection, owned by the process holding Lucene's write lock. */
public final class MailIndex implements AutoCloseable {
    public static final String SCHEMA = "4";
    private final FSDirectory directory;
    private final IndexWriter writer;

    private MailIndex(FSDirectory directory, IndexWriter writer) {
        this.directory = directory;
        this.writer = writer;
    }

    public static MailIndex openWriter(Path connectionDir) {
        return openWriter(connectionDir, false);
    }

    public static MailIndex openWriter(Path connectionDir, boolean recreate) {
        try {
            Path path = Files.createDirectories(connectionDir.resolve("index"));
            FSDirectory directory = FSDirectory.open(path);
            try {
                boolean current = false;
                if (DirectoryReader.indexExists(directory)) {
                    try (var reader = DirectoryReader.open(directory)) {
                        current = SCHEMA.equals(reader.getIndexCommit().getUserData().get("schema"));
                    }
                }
                var config = new IndexWriterConfig(MailAnalyzers.analyzer());
                if (recreate || !current) { config.setOpenMode(IndexWriterConfig.OpenMode.CREATE); }
                var writer = new IndexWriter(directory, config);
                writer.setLiveCommitData(Map.of("schema", SCHEMA).entrySet());
                return new MailIndex(directory, writer);
            } catch (IOException e) {
                try { directory.close(); }
                catch (IOException closeError) { e.addSuppressed(closeError); }
                if (e instanceof LockObtainFailedException) { throw new IndexLockedException(); }
                throw e;
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void put(MessageMeta meta, MailMessage message, String body, String attachmentText) {
        Document document = new Document();
        document.add(new StringField("key", meta.key(), Field.Store.YES));
        document.add(new SortedDocValuesField("keySort", new BytesRef(meta.key())));
        document.add(new StringField("ref", meta.folderKey() + "/" + meta.ref(), Field.Store.YES));
        document.add(new StringField("folder", meta.folder(), Field.Store.YES));
        document.add(new StringField("state", meta.state().id(), Field.Store.YES));
        if (meta.read() != null) { document.add(new StringField("read", meta.read().toString(), Field.Store.YES)); }
        if (meta.flagged() != null) { document.add(new StringField("flagged", meta.flagged().toString(), Field.Store.YES)); }
        if (meta.error() != null) { document.add(new StoredField("error", meta.error())); }
        if (message != null) {
            if (message.date() != null) {
                long date = message.date().toEpochMilli();
                document.add(new LongPoint("date", date));
                document.add(new StoredField("date", date));
                document.add(new NumericDocValuesField("date", date));
            }
            text(document, "subject", message.subject());
            unstoredText(document, "subject_en", message.subject());
            addresses(document, "from", message.from());
            addresses(document, "to", message.to());
            addresses(document, "cc", message.cc());
            if (message.messageId() != null) {
                document.add(new StringField("messageId", message.messageId(), Field.Store.YES));
            }
            if (message.inReplyTo() != null) {
                document.add(new StringField("inReplyTo", message.inReplyTo(), Field.Store.YES));
            }
            message.references().forEach(id -> document.add(new StringField("references", id, Field.Store.YES)));
            document.add(new StoredField("attachmentNames", message.attachments().stream()
                    .map(a -> a.name() == null ? a.file() : a.name()).collect(Collectors.joining("\n"))));
            message.attachments().forEach(a -> {
                String name = a.name() == null ? a.file() : a.name();
                unstoredText(document, "attachmentName", name);
                unstoredText(document, "attachmentFile", name);
            });
        }
        text(document, "body", body);
        unstoredText(document, "body_en", body);
        unstoredText(document, "attachmentText", attachmentText);
        unstoredText(document, "attachmentText_en", attachmentText);
        try { writer.updateDocument(new Term("key", meta.key()), document); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static void text(Document document, String field, String value) {
        if (value != null) { document.add(new TextField(field, value, Field.Store.YES)); }
    }

    private static void unstoredText(Document document, String field, String value) {
        if (value != null) { document.add(new TextField(field, value, Field.Store.NO)); }
    }

    private static void addresses(Document document, String field, List<Address> addresses) {
        document.add(new StoredField(field, addresses.stream().map(Address::display).collect(Collectors.joining(", "))));
        addresses.forEach(address -> {
            unstoredText(document, field + "Address", address.address());
            unstoredText(document, field + "Name", address.name());
        });
    }

    public void delete(String key) {
        try { writer.deleteDocuments(new Term("key", key)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void commit() {
        try {
            writer.setLiveCommitData(Map.of("schema", SCHEMA).entrySet());
            writer.commit();
        }
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

    public List<String> reconcile(MessageStore store) {
        Set<String> diskKeys = new HashSet<>(store.keys());
        for (String key : Set.copyOf(diskKeys)) {
            if (!store.hasMeta(key)) {
                store.delete(key);
                diskKeys.remove(key);
            }
        }
        Set<String> indexedKeys = new HashSet<>();
        Set<String> changedKeys = new HashSet<>();
        try (var reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            for (var hit : searcher.search(new MatchAllDocsQuery(), Math.max(1, reader.numDocs())).scoreDocs) {
                var document = searcher.storedFields().document(hit.doc);
                String key = document.get("key");
                indexedKeys.add(key);
                if (diskKeys.contains(key)) {
                    var meta = store.readMeta(key).orElseThrow();
                    if (!(meta.folderKey() + "/" + meta.ref()).equals(document.get("ref"))
                            || !meta.folder().equals(document.get("folder"))
                            || !java.util.Objects.equals(meta.read() == null ? null : meta.read().toString(), document.get("read"))
                            || !java.util.Objects.equals(meta.flagged() == null ? null : meta.flagged().toString(), document.get("flagged"))) { changedKeys.add(key); }
                }
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        for (String key : indexedKeys) {
            if (!diskKeys.contains(key)) { delete(key); }
        }
        return diskKeys.stream().filter(key -> !indexedKeys.contains(key) || changedKeys.contains(key)).sorted().toList();
    }

    @Override public void close() {
        try {
            try { writer.close(); }
            finally { directory.close(); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
