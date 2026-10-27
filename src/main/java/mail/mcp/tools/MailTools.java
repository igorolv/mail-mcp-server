package mail.mcp.tools;

import jakarta.mail.MessagingException;
import mail.mcp.api.AttachmentRef;
import mail.mcp.api.FolderInfo;
import mail.mcp.api.GetMessageResult;
import mail.mcp.api.GetAttachmentResult;
import mail.mcp.api.ListFoldersResult;
import mail.mcp.api.ListMessagesResult;
import mail.mcp.api.MessageSummary;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.index.MailIndexReader;
import mail.mcp.mime.MimeReader;
import mail.mcp.mime.Containers;
import mail.mcp.model.Address;
import mail.mcp.model.DateRange;
import mail.mcp.model.MailMessage;
import mail.mcp.redaction.SecretRedactor;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;

/** Local-only mailbox reading; does not resolve a source or require pandoc. */
@Service
public class MailTools {
    private static final Logger log = LoggerFactory.getLogger(MailTools.class);
    private final ConnectionRegistry registry;
    private final ObjectMapper mapper;
    private final SecretRedactor redactor;

    public MailTools(ConnectionRegistry registry, SyncService syncService, ObjectMapper mapper) {
        this(registry, syncService, mapper, new SecretRedactor(false));
    }

    @Autowired
    public MailTools(ConnectionRegistry registry, SyncService syncService, ObjectMapper mapper, SecretRedactor redactor) {
        this.registry = registry;
        this.mapper = mapper;
        this.redactor = redactor;
    }

    @McpTool(description = "List locally stored folders and committed message counts. synced is true after " +
            "a folder has completed at least one pass. Data may be incomplete during sync; check syncStatus. " +
            "No mailbox or pandoc access.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ListFoldersResult listFolders(@McpToolParam(description = "Connection name from listConnections") String connection) {
        long start = System.nanoTime();
        try {
            var definition = registry.definition(connection);
            var store = new MessageStore(definition.connectionDir(), mapper);
            try (var reader = MailIndexReader.open(definition.connectionDir()).orElse(null)) {
                var folders = store.folders().stream().map(f -> new FolderInfo(f.key(), redactor.text(f.path()),
                        reader == null ? 0 : reader.count(f.key()), f.cursor() != null)).toList();
                ToolLogger.completed(log, "listFolders", start);
                return new ListFoldersResult(definition.name(), folders);
            }
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listFolders", start, e.getClass().getSimpleName());
            throw e;
        }
    }

    public ListMessagesResult listMessages(String connection, String folder, Integer offset, Integer limit) {
        return listMessages(connection, folder, offset, limit, null, null);
    }

    @McpTool(description = "List local message cards newest first, undated last. Optional since (inclusive) and until (exclusive) select MIME header dates; dates are UTC midnight or datetimes with explicit offset. limit defaults to 50, " +
            "capped at 200. Use nextOffset to page. Data may be incomplete during sync; check listFolders.synced " +
            "and syncStatus. localPath is an absolute directory whose files may be read directly. No network access.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ListMessagesResult listMessages(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Folder key from listFolders") String folder,
            @McpToolParam(description = "Nonnegative starting position; default 0", required = false) Integer offset,
            @McpToolParam(description = "Positive page size; default 50, at most 200", required = false) Integer limit,
            @McpToolParam(description = "Inclusive UTC date or datetime with offset", required = false) String since,
            @McpToolParam(description = "Exclusive UTC date or datetime with offset", required = false) String until) {
        long start = System.nanoTime();
        try {
            var range = DateRange.parse(since, until);
            int position = offset(offset);
            int size = bounded(limit, 50, 200, "limit");
            var definition = registry.definition(connection);
            var store = new MessageStore(definition.connectionDir(), mapper);
            if (store.folders().stream().noneMatch(f -> f.key().equals(folder))) {
                throw new IllegalArgumentException("Unknown folder key; use listFolders");
            }
            try (var reader = MailIndexReader.open(definition.connectionDir()).orElse(null)) {
                if (reader != null && !reader.current()) {
                    throw new IllegalStateException("The index was built by an older version; " +
                            "run 'mail-reindex <connection>' in a terminal or start a sync to rebuild it");
                }
                var page = reader == null ? null : reader.cards(folder, position, size, range);
                int total = page == null ? 0 : page.total();
                var messages = page == null ? List.<MessageSummary>of()
                        : page.cards().stream().map(c -> new MessageSummary(c.key(),
                        c.date(), redactor.text(c.from()), redactor.text(c.subject()), c.hasAttachments(), c.state(),
                        store.messageDir(c.key()).toAbsolutePath().toString())).toList();
                Integer next = (long) position + messages.size() < total ? position + messages.size() : null;
                ToolLogger.completed(log, "listMessages", start);
                return new ListMessagesResult(definition.name(), folder, total, position, next, messages, range.since(), range.until());
            }
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listMessages", start, e.getClass().getSimpleName());
            throw e;
        }
    }

    @McpTool(description = "Read local message headers, a body.md chunk and attachment paths. " +
            "Attachments include files inside EML, ZIP, MSG and TNEF containers, with their parent file. " +
            "maxChars defaults to 20000, capped at 100000; use nextOffset for the next chunk. localPath points at files " +
            "the agent may read directly. Local data may be incomplete during sync; check listFolders.synced " +
            "and syncStatus. When secret filtering is enabled, body offsets refer to the filtered text; " +
            "original files stay unfiltered. No mailbox or pandoc access.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public GetMessageResult getMessage(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Local message UUID from listMessages") String key,
            @McpToolParam(description = "Nonnegative body character offset; default 0", required = false) Integer offset,
            @McpToolParam(description = "Positive chunk size; default 20000, at most 100000", required = false) Integer maxChars) {
        long start = System.nanoTime();
        try {
            int position = offset(offset);
            int size = bounded(maxChars, 20_000, 100_000, "maxChars");
            var definition = registry.definition(connection);
            var store = new MessageStore(definition.connectionDir(), mapper);
            var directory = store.messageDir(key).toAbsolutePath();
            var meta = store.readMeta(key).orElseThrow(() -> new IllegalArgumentException("Unknown message key"));
            MailMessage message = null;
            var eml = directory.resolve(MessageStore.MESSAGE_FILE);
            if (Files.exists(eml)) {
                try { message = Containers.expand(MimeReader.read(eml, null).message(),
                        directory.resolve(MessageStore.ATTACHMENTS_DIR), false); }
                catch (MessagingException | IOException | RuntimeException e) { /* metadata/body remain readable */ }
            }
            var bodyFile = directory.resolve(MessageStore.BODY_FILE);
            String body = redactor.text(Files.exists(bodyFile) ? Files.readString(bodyFile) : null);
            int length = body == null ? 0 : body.length();
            int end = (int) Math.min(length, (long) position + size);
            String chunk = body == null ? null : body.substring(Math.min(position, length), end);
            Integer next = end < length ? end : null;
            var attachments = message == null ? List.<AttachmentRef>of() : message.attachments().stream().map(a -> {
                var path = directory.resolve(MessageStore.ATTACHMENTS_DIR).resolve(a.file());
                return new AttachmentRef(a.file(), redactor.text(a.name()), a.contentType(), a.size(), a.inline(),
                        Files.exists(path) ? path.toString() : null,
                        Files.exists(path.resolveSibling(a.file() + ".md"))
                                ? path.resolveSibling(a.file() + ".md").toString() : null, a.parent());
            }).toList();
            var result = new GetMessageResult(key, redactor.text(meta.folder()), meta.state().id(), redactor.text(meta.error()),
                    message == null ? null : redactor.text(message.subject()), message == null ? null : display(message.from()),
                    message == null ? null : display(message.to()), message == null ? null : display(message.cc()),
                    message == null ? null : message.date(), message == null ? null : redactor.text(message.messageId()),
                    message == null ? null : redactor.text(message.inReplyTo()), message == null ? null : redactor.texts(message.references()),
                    chunk, position, next, length, attachments, directory.toString());
            ToolLogger.completed(log, "getMessage", start);
            return result;
        } catch (IOException e) {
            ToolLogger.failed(log, "getMessage", start, e.getClass().getSimpleName());
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "getMessage", start, e.getClass().getSimpleName());
            throw e;
        }
    }

    @McpTool(description = "Read a local attachment and extracted text in chunks. Text exists only for DOCX, ODT, " +
            "RTF, EPUB, HTML, CSV, PDF, DOC, XLS, PPT, XLSX, PPTX, EML, MSG, TNEF and plain text up to 20 MiB " +
            "of decoded bytes, except XLSX up to 5 MiB, when conversion succeeds. Conversion is best effort; " +
            "compressed XLSX size does not bound POI memory and smaller files can still be expensive. " +
            "Existing text stays readable until mail-reprocess replaces it; mail-reindex alone retains saved text. " +
            "Attachments include files inside EML, ZIP, MSG and TNEF containers; accepts their exact file from getMessage. " +
            "path is the original file; textPath is the Markdown. maxChars defaults to 20000, capped at 100000; " +
            "use nextOffset for the next chunk. With secret filtering, offsets refer to filtered text; " +
            "original files stay unfiltered. No mailbox or pandoc access.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public GetAttachmentResult getAttachment(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Local message UUID") String key,
            @McpToolParam(description = "Exact stored attachment filename from getMessage") String file,
            @McpToolParam(description = "Nonnegative text character offset; default 0", required = false) Integer offset,
            @McpToolParam(description = "Positive chunk size; default 20000, at most 100000", required = false) Integer maxChars) {
        long start = System.nanoTime();
        try {
            int position = offset(offset);
            int size = bounded(maxChars, 20_000, 100_000, "maxChars");
            var definition = registry.definition(connection);
            var store = new MessageStore(definition.connectionDir(), mapper);
            var directory = store.messageDir(key).toAbsolutePath();
            store.readMeta(key).orElseThrow(() -> new IllegalArgumentException("Unknown message key"));
            final MailMessage message;
            try { message = Containers.expand(MimeReader.read(directory.resolve(MessageStore.MESSAGE_FILE), null)
                    .message(), directory.resolve(MessageStore.ATTACHMENTS_DIR), false); }
            catch (MessagingException | IOException | RuntimeException e) {
                throw new IllegalArgumentException("Attachment list is unavailable");
            }
            var attachment = message.attachments().stream().filter(a -> a.file().equals(file)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown attachment file; use getMessage"));
            var path = directory.resolve(MessageStore.ATTACHMENTS_DIR).resolve(attachment.file());
            var textPath = path.resolveSibling(attachment.file() + ".md");
            String text = redactor.text(Files.exists(textPath) ? Files.readString(textPath) : null);
            int length = text == null ? 0 : text.length();
            int end = (int) Math.min(length, (long) position + size);
            String chunk = text == null ? null : text.substring(Math.min(position, length), end);
            var result = new GetAttachmentResult(key, attachment.file(), redactor.text(attachment.name()), attachment.contentType(),
                    attachment.size(), Files.exists(path) ? path.toString() : null,
                    text == null ? null : textPath.toString(), chunk, position, end < length ? end : null, length);
            ToolLogger.completed(log, "getAttachment", start);
            return result;
        } catch (IOException e) {
            ToolLogger.failed(log, "getAttachment", start, e.getClass().getSimpleName());
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "getAttachment", start, e.getClass().getSimpleName());
            throw e;
        }
    }

    private List<String> display(List<Address> addresses) {
        return addresses.stream().map(Address::display).map(redactor::text).toList();
    }

    private static int offset(Integer value) {
        if (value != null && value < 0) { throw new IllegalArgumentException("offset must be nonnegative"); }
        return value == null ? 0 : value;
    }

    private static int bounded(Integer value, int fallback, int cap, String name) {
        if (value != null && value < 1) { throw new IllegalArgumentException(name + " must be positive"); }
        return value == null ? fallback : Math.min(value, cap);
    }
}
