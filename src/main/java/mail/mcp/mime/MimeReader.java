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
        // Jakarta Mail retains shared views with no public close method on MimeMessage.
        // No view escapes read(), so close the backing file as well as the root view.
        try (var input = new SharedFileInputStream(eml.toFile()) {
            @Override public void close() throws IOException {
                try { if (in != null) { in.close(); } }
                finally { super.close(); }
            }
        }) {
            var mime = new MimeMessage(Session.getInstance(new Properties()), input);
            var walk = new Walk(attachmentsDir);
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

    private static String safeName(String name) {
        String safe = name.replaceAll("[<>:\"/\\\\|?*\\p{Cc}]", "_");
        safe = safe.substring(0, Math.min(100, safe.length()));
        return java.util.regex.Pattern.compile("[. ]+$").matcher(safe)
                .replaceAll(match -> "_".repeat(match.group().length()));
    }

    private static final class Walk {
        private final Path directory;
        private final List<AttachmentInfo> attachments = new ArrayList<>();
        private Part plain;
        private Part html;

        private Walk(Path directory) { this.directory = directory; }

        private void visit(Part part) throws MessagingException, IOException {
            if (part.isMimeType("multipart/*")) {
                var multipart = (Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount(); i++) { visit(multipart.getBodyPart(i)); }
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
            String file = (attachments.size() + 1) + "__" + (name == null ? "part" : safeName(name));
            if (directory != null) { Files.createDirectories(directory); }
            long size;
            try (var input = part.getInputStream();
                 var output = directory == null ? OutputStream.nullOutputStream()
                         : Files.newOutputStream(directory.resolve(file))) {
                size = input.transferTo(output);
            }
            String contentId = firstHeader(part, "Content-ID");
            if (contentId != null && contentId.startsWith("<") && contentId.endsWith(">")) {
                contentId = contentId.substring(1, contentId.length() - 1);
            }
            attachments.add(new AttachmentInfo(file, name, type, size, contentId,
                    Part.INLINE.equalsIgnoreCase(disposition)));
        }
    }
}
