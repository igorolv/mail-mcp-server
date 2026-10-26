package mail.mcp.mime;

import jakarta.mail.MessagingException;
import mail.mcp.model.AttachmentInfo;
import mail.mcp.model.MailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** Appends the files of saved nested messages and ZIP archives to the attachment list (plan.md 3.5). */
public final class Containers {
    private static final Logger log = LoggerFactory.getLogger(Containers.class);
    private static final int MAX_DEPTH = 5;
    private static final long MAX_ENTRY_SIZE = 20L * 1024 * 1024;
    private static final int MAX_FILES = 1_000;
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private Containers() { }

    public static MailMessage expand(MailMessage message, Path attachmentsDir, boolean save) {
        return expand(message, attachmentsDir, save, MAX_FILES, MAX_BYTES);
    }

    static MailMessage expand(MailMessage message, Path attachmentsDir, boolean save, int fileLimit, long byteLimit) {
        var attachments = new ArrayList<>(message.attachments());
        var depths = new HashMap<String, Integer>();
        int files = 0;
        long bytes = 0;
        boolean stopped = false;
        for (int i = 0; i < attachments.size() && !stopped; i++) {
            if (files >= fileLimit || bytes >= byteLimit) { break; }
            var container = attachments.get(i);
            int depth = depths.getOrDefault(container.file(), 1);
            if (depth > MAX_DEPTH) { continue; }
            String name = container.name() == null ? container.file() : container.name();
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            boolean eml = extension.equals("eml") || container.contentType().equals("message/rfc822");
            boolean zip = extension.equals("zip") || container.contentType().equals("application/zip")
                    || container.contentType().equals("application/x-zip-compressed")
                    || container.contentType().equals("application/x-zip");
            if (!eml && !zip) { continue; }
            Path source = attachmentsDir.resolve(container.file());
            if (!Files.isRegularFile(source)) { continue; }
            Path partial = null;
            int next = attachments.size() + 1;
            try {
                if (eml) {
                    var entries = new ArrayList<AttachmentInfo>();
                    try {
                        stopped = MimeReader.readAttachments(source, save ? attachmentsDir : null, next,
                                fileLimit - files, byteLimit - bytes, entries);
                    } finally {
                        for (var entry : entries) {
                            attachments.add(new AttachmentInfo(entry.file(), entry.name(), entry.contentType(), entry.size(),
                                    entry.contentId(), entry.inline(), entry.charset(), container.file()));
                            depths.put(entry.file(), depth + 1);
                            files++;
                            bytes += entry.size();
                        }
                    }
                } else {
                    try (var input = new ZipInputStream(Files.newInputStream(source), Charset.forName("IBM866"))) {
                        var buffer = new byte[8192];
                        while (true) {
                            if (files >= fileLimit || bytes >= byteLimit) { stopped = true; break; }
                            var entry = input.getNextEntry();
                            if (entry == null) { break; }
                            String entryName = entry.getName().replace('\\', '/');
                            if (entry.isDirectory() || entryName.endsWith("/")) { continue; }
                            String file = (attachments.size() + 1) + "__"
                                    + MimeReader.safeName(entryName.substring(entryName.lastIndexOf('/') + 1));
                            partial = save ? attachmentsDir.resolve(file) : null;
                            long size = 0;
                            boolean oversized = false;
                            try (var output = save ? Files.newOutputStream(partial) : OutputStream.nullOutputStream()) {
                                int count;
                                while ((count = input.read(buffer)) != -1) {
                                    size += count;
                                    if (size > byteLimit - bytes) { stopped = true; break; }
                                    if (size > MAX_ENTRY_SIZE) { oversized = true; break; }
                                    output.write(buffer, 0, count);
                                }
                            }
                            if (stopped || oversized) {
                                if (save) { Files.deleteIfExists(partial); }
                                partial = null;
                                if (stopped) { break; }
                                input.closeEntry();
                                continue;
                            }
                            attachments.add(new AttachmentInfo(file, entryName, "application/octet-stream", size,
                                    null, false, null, container.file()));
                            depths.put(file, depth + 1);
                            files++;
                            bytes += size;
                            partial = null;
                        }
                    }
                }
            } catch (IOException | MessagingException | RuntimeException e) {
                log.debug("{}", e.getClass().getSimpleName());
                if (save && partial != null) { deletePartial(partial); }
            }
        }
        return new MailMessage(message.subject(), message.from(), message.to(), message.cc(), message.date(),
                message.messageId(), message.inReplyTo(), message.references(), attachments);
    }

    private static void deletePartial(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException | RuntimeException e) { log.debug("{}", e.getClass().getSimpleName()); }
    }
}
