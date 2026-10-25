package mail.mcp.sync;

import jakarta.mail.MessagingException;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;

/** Converts downloaded MIME into local attachment files and body.md, then updates metadata. */
public final class MessageProcessor {
    private final MessageStore store;
    private final Pandoc pandoc;

    public MessageProcessor(MessageStore store, Pandoc pandoc) {
        this.store = store;
        this.pandoc = pandoc;
    }

    public Processed process(MessageMeta meta) {
        var directory = store.messageDir(meta.key());
        var attachments = directory.resolve(MessageStore.ATTACHMENTS_DIR);
        try {
            Files.deleteIfExists(directory.resolve(MessageStore.BODY_FILE));
            if (Files.exists(attachments)) {
                try (var paths = Files.walk(attachments)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
                }
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        final Processed result;
        try {
            var parsed = MimeReader.read(directory.resolve(MessageStore.MESSAGE_FILE), attachments);
            String body = parsed.html() ? pandoc.htmlToMarkdown(parsed.body()) : parsed.body();
            if (body != null) {
                try { Files.writeString(directory.resolve(MessageStore.BODY_FILE), body, StandardCharsets.UTF_8); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            result = new Processed(state(meta, MessageState.CONVERTED, null), parsed.message(), body);
        } catch (MessagingException | IOException e) {
            return failed(meta, "MIME parsing failed (" + e.getClass().getSimpleName() + ")");
        } catch (PandocException e) { return failed(meta, e.getMessage()); }
        store.writeMeta(result.meta());
        return result;
    }

    public Processed load(MessageMeta meta) {
        var directory = store.messageDir(meta.key());
        try {
            var parsed = MimeReader.read(directory.resolve(MessageStore.MESSAGE_FILE), null);
            var bodyFile = directory.resolve(MessageStore.BODY_FILE);
            String body = Files.exists(bodyFile) ? Files.readString(bodyFile, StandardCharsets.UTF_8) : null;
            return new Processed(meta, parsed.message(), body);
        } catch (MessagingException | IOException e) { return new Processed(meta, null, null); }
    }

    private Processed failed(MessageMeta meta, String error) {
        var failed = state(meta, MessageState.FAILED, error);
        store.writeMeta(failed);
        return new Processed(failed, null, null);
    }

    private static MessageMeta state(MessageMeta meta, MessageState state, String error) {
        return new MessageMeta(meta.key(), meta.folderKey(), meta.folder(), meta.ref(), meta.size(),
                meta.sha256(), meta.downloadedAt(), state, error);
    }
}
