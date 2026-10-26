package mail.mcp.mime;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.SharedFileInputStream;
import mail.mcp.model.Address;
import mail.mcp.model.AttachmentInfo;
import mail.mcp.model.MailMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Reads a local MIME file; only multipart containers are traversed. */
public final class MimeReader {
    private MimeReader() { }

    /** Sets Angus properties before any Jakarta Mail class is used. */
    public static void configureAngus() {
        System.setProperty("mail.mime.decodefilename", "true");
        System.setProperty("mail.mime.parameters.strict", "false");
        System.setProperty("mail.mime.base64.ignoreerrors", "true");
    }

    /** Parses MIME, saving decoded attachments only when attachmentsDir is non-null. */
    public static ParsedMessage read(Path eml, Path attachmentsDir) throws MessagingException, IOException {
        return read(eml, attachmentsDir, 1);
    }

    /** Parses MIME with stored attachment numbering starting at firstNumber. */
    public static ParsedMessage read(Path eml, Path attachmentsDir, int firstNumber) throws MessagingException, IOException {
        try (var input = open(eml)) {
            var mime = new MimeMessage(Session.getInstance(new Properties()), input);
            var walk = new Walk(attachmentsDir, firstNumber);
            walk.visit(mime);
            Part selected = walk.plain != null ? walk.plain : walk.html;
            String body = selected == null ? null : (String) selected.getContent();
            var date = mime.getSentDate();
            String references = mime.getHeader("References", " ");
            var message = new MailMessage(mime.getSubject(), addresses(mime, null),
                    addresses(mime, Message.RecipientType.TO), addresses(mime, Message.RecipientType.CC),
                    date == null ? null : date.toInstant(), mime.getMessageID(),
                    firstHeader(mime, "In-Reply-To"),
                    references == null || references.isBlank() ? List.of()
                            : List.of(references.trim().split("\\s+")), walk.attachments);
            return new ParsedMessage(message, body,
                    walk.plain == null && walk.html != null);
        }
    }

    /** Lists completed nested-message attachments, retaining them on error; returns whether a limit stopped the walk. */
    static boolean readAttachments(Path eml, Path attachmentsDir, int firstNumber,
                                   int fileLimit, long byteLimit, List<AttachmentInfo> entries)
            throws MessagingException, IOException {
        try (var input = open(eml)) {
            var walk = new Walk(attachmentsDir, firstNumber, entries, fileLimit, byteLimit);
            walk.visit(new MimeMessage(Session.getInstance(new Properties()), input));
            return walk.stopped;
        }
    }

    private static SharedFileInputStream open(Path eml) throws IOException {
        // Jakarta Mail retains shared views with no public close method on MimeMessage.
        // No view escapes the reader, so close the backing file as well as the root view.
        return new SharedFileInputStream(eml.toFile()) {
            @Override public void close() throws IOException {
                try { if (in != null) { in.close(); } }
                finally { super.close(); }
            }
        };
    }

    private static List<Address> addresses(MimeMessage message, Message.RecipientType type) {
        try {
            var values = type == null ? message.getFrom() : message.getRecipients(type);
            if (values == null) { return List.of(); }
            return Arrays.stream(values).map(value -> (InternetAddress) value)
                    .map(value -> new Address(value.getPersonal(), value.getAddress())).toList();
        } catch (MessagingException e) { return List.of(); }
    }

    private static String firstHeader(Part part, String name) throws MessagingException {
        String[] values = part.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    static String safeName(String name) {
        String safe = name.replaceAll("[<>:\"/\\\\|?*\\p{Cc}]", "_");
        safe = safe.substring(0, Math.min(100, safe.length()));
        return java.util.regex.Pattern.compile("[. ]+$").matcher(safe)
                .replaceAll(match -> "_".repeat(match.group().length()));
    }

    private static final class Walk {
        private final Path directory;
        private final int firstNumber;
        private final List<AttachmentInfo> attachments;
        private final int fileLimit;
        private long remainingBytes;
        private boolean stopped;
        private Part plain;
        private Part html;

        private Walk(Path directory, int firstNumber) {
            this(directory, firstNumber, new ArrayList<>(), Integer.MAX_VALUE, Long.MAX_VALUE);
        }

        private Walk(Path directory, int firstNumber, List<AttachmentInfo> attachments,
                     int fileLimit, long byteLimit) {
            this.directory = directory;
            this.firstNumber = firstNumber;
            this.attachments = attachments;
            this.fileLimit = fileLimit;
            this.remainingBytes = byteLimit;
        }

        private void visit(Part part) throws MessagingException, IOException {
            if (stopped || attachments.size() >= fileLimit || remainingBytes == 0) {
                stopped = true;
                return;
            }
            if (part.isMimeType("multipart/*")) {
                var multipart = (Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount() && !stopped; i++) { visit(multipart.getBodyPart(i)); }
                return;
            }
            String type = new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
            String disposition = part.getDisposition();
            String name = part.getFileName();
            if ((type.equals("text/plain") || type.equals("text/html"))
                    && !Part.ATTACHMENT.equalsIgnoreCase(disposition) && name == null) {
                if (type.equals("text/plain") && plain == null) { plain = part; }
                if (type.equals("text/html") && html == null) { html = part; }
                return;
            }
            String file = (attachments.size() + firstNumber) + "__" + (name == null ? "part" : safeName(name));
            String contentId = firstHeader(part, "Content-ID");
            if (contentId != null && contentId.startsWith("<") && contentId.endsWith(">")) {
                contentId = contentId.substring(1, contentId.length() - 1);
            }
            String charset = new ContentType(part.getContentType()).getParameter("charset");
            if (directory != null) { Files.createDirectories(directory); }
            long size = 0;
            boolean complete = false;
            try {
                try (var input = part.getInputStream();
                     var output = directory == null ? OutputStream.nullOutputStream()
                             : Files.newOutputStream(directory.resolve(file))) {
                    if (remainingBytes == Long.MAX_VALUE) {
                        size = input.transferTo(output);
                    } else {
                        var buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, remainingBytes - size + 1))) != -1) {
                            size += count;
                            if (size > remainingBytes) { stopped = true; return; }
                            output.write(buffer, 0, count);
                        }
                    }
                }
                complete = true;
            } finally {
                if (!complete && directory != null) { Files.deleteIfExists(directory.resolve(file)); }
            }
            attachments.add(new AttachmentInfo(file, name, type, size, contentId,
                    Part.INLINE.equalsIgnoreCase(disposition),
                    charset == null ? null : charset.toLowerCase(Locale.ROOT), null));
            if (remainingBytes != Long.MAX_VALUE) { remainingBytes -= size; }
            stopped = attachments.size() >= fileLimit || remainingBytes == 0;
        }
    }
}
