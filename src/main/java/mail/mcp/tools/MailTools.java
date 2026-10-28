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

    @McpTool(description = "List locally known folders in one connection, with folder keys, paths and " +
            "local indexed message counts. Pass a folder's key as folder to listMessages or search; " +
            "keep the same connection. synced=false means a completed folder sync has not been recorded " +
            "and messages may be missing. synced=true does not guarantee current mail; use syncStatus " +
            "for the latest sync's time and outcome. This tool does not discover folders on the server.", generateOutputSchema = true,
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

    @McpTool(description = "Browse locally indexed messages in one folder, newest first, optionally within " +
            "a date range. Undated messages come last without date bounds and are excluded with bounds. " +
            "For keyword search use search; for a date range across all folders use search without query.\n\n" +
            "Returns cards without bodies. Pass a card's key and the same connection to getMessage to " +
            "read it, or to getThread for its conversation. Every stored copy counts separately here; " +
            "search and getThread group recognized copies. read/flagged describe the last sync observation.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ListMessagesResult listMessages(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "One folder key from listFolders in this connection, not its display name") String folder,
            @McpToolParam(description = "Default 0. For the next page, repeat the same arguments and use the " +
                    "previous nextOffset. Stop when there is no nextOffset", required = false) Integer offset,
            @McpToolParam(description = "Results per page: 1 to 200, default 50; larger values are capped at 200", required = false) Integer limit,
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
                        store.messageDir(c.key()).toAbsolutePath().toString(), c.read(), c.flagged())).toList();
                Integer next = (long) position + messages.size() < total ? position + messages.size() : null;
                ToolLogger.completed(log, "listMessages", start);
                return new ListMessagesResult(definition.name(), folder, total, position, next, messages, range.since(), range.until());
            }
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "listMessages", start, e.getClass().getSimpleName());
            throw e;
        }
    }

    @McpTool(description = "Read one locally stored message: headers, a body chunk as Markdown, and an " +
            "attachment list. nextOffset continues only the body; headers and the attachment list are " +
            "returned on each call. If body is absent, saved body text is unavailable; inspect state " +
            "and error rather than assuming the original message was empty.\n\n" +
            "To read an attachment, call getAttachment with the same connection and key, and " +
            "file=attachments[].file. Use file, not name or path. The list includes files extracted " +
            "from attached archives and messages; parent is the containing attachment's file. " +
            "For the conversation, call getThread with the same connection and key.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public GetMessageResult getMessage(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Local message key from listMessages, search or getThread in this " +
                    "connection; use key, not the Message-ID header") String key,
            @McpToolParam(description = "Body character offset, default 0. To continue, keep the same " +
                    "connection and key and use nextOffset from this tool. Stop when there is no nextOffset", required = false) Integer offset,
            @McpToolParam(description = "Body characters per chunk: 1 to 100000, default 20000; larger values " +
                    "are capped at 100000", required = false) Integer maxChars) {
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
                    chunk, position, next, length, attachments, directory.toString(), meta.read(), meta.flagged());
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

    @McpTool(description = "Read an attachment's saved extracted text as Markdown, in chunks, with its " +
            "metadata and local file paths. This reads existing text; it does not convert the file on demand. " +
            "If text is absent, extracted text is unavailable, not proof that the original attachment is " +
            "empty. The original may still be available at path; path is absent if the file is missing.\n\n" +
            "Get file from getMessage's attachments[].file or a search hit's snippetAttachmentFile, " +
            "keeping that message's connection and key. To continue a long text, call this tool again " +
            "with the same connection, key and file and offset=nextOffset. Stop when there is no nextOffset.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public GetAttachmentResult getAttachment(
            @McpToolParam(description = "Connection name from listConnections") String connection,
            @McpToolParam(description = "Local key of the message containing this attachment, from getMessage " +
                    "or the search hit; use the same connection") String key,
            @McpToolParam(description = "Exact attachments[].file from getMessage or snippetAttachmentFile " +
                    "from search for this message. Use this value unchanged, not name, path or textPath") String file,
            @McpToolParam(description = "Text character offset, default 0. Use nextOffset from this tool to " +
                    "continue this attachment, not an offset from message-body or search results", required = false) Integer offset,
            @McpToolParam(description = "Text characters per chunk: 1 to 100000, default 20000; larger values " +
                    "are capped at 100000", required = false) Integer maxChars) {
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
