package mail.mcp.convert;

import jakarta.mail.MessagingException;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.Address;
import mail.mcp.model.AttachmentInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** Best-effort text conversion of supported attachments up to 20 MiB. */
public final class AttachmentText {
    private static final Logger log = LoggerFactory.getLogger(AttachmentText.class);
    private static final long MAX_SIZE = 20L * 1024 * 1024;

    private AttachmentText() { }

    public static Optional<String> toMarkdown(Path file, AttachmentInfo info, Pandoc pandoc) {
        try {
            if (Files.size(file) > MAX_SIZE) { return Optional.empty(); }
            String name = info.name() == null ? info.file() : info.name();
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (extension.equals("zip") || info.contentType().equals("application/zip")
                    || info.contentType().equals("application/x-zip-compressed")
                    || info.contentType().equals("application/x-zip")) { return Optional.empty(); }
            String format = info.contentType().equals("message/rfc822") ? "eml" : switch (extension) {
                case "docx", "odt", "rtf", "epub", "html", "csv", "pdf", "xlsx", "pptx", "txt", "eml" -> extension;
                case "htm" -> "html";
                default -> switch (info.contentType()) {
                    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
                    case "application/vnd.oasis.opendocument.text" -> "odt";
                    case "application/rtf", "text/rtf" -> "rtf";
                    case "application/epub+zip" -> "epub";
                    case "text/html" -> "html";
                    case "text/csv" -> "csv";
                    case "application/pdf" -> "pdf";
                    case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
                    case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
                    case "text/plain" -> "txt";
                    case "message/rfc822" -> "eml";
                    default -> null;
                };
            };
            if (format == null) { return Optional.empty(); }
            String text = switch (format) {
                case "docx", "odt", "rtf", "epub" -> pandoc.toMarkdown(file, format);
                case "html", "csv", "txt" -> {
                    Charset charset = info.charset() == null ? StandardCharsets.UTF_8 : Charset.forName(info.charset());
                    String decoded = new String(Files.readAllBytes(file), charset);
                    yield format.equals("txt") ? decoded : pandoc.toMarkdown(decoded, format);
                }
                case "pdf" -> PdfText.extract(file);
                case "xlsx" -> XlsxText.extract(file);
                case "pptx" -> PptxText.extract(file);
                case "eml" -> nestedMessage(file, pandoc);
                default -> throw new IllegalStateException();
            };
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (IOException | MessagingException | RuntimeException e) {
            log.debug("{}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String nestedMessage(Path file, Pandoc pandoc) throws IOException, MessagingException {
        var parsed = MimeReader.read(file, null);
        var message = parsed.message();
        var headers = new StringBuilder();
        header(headers, "Subject", message.subject());
        header(headers, "From", display(message.from()));
        header(headers, "To", display(message.to()));
        header(headers, "Cc", display(message.cc()));
        header(headers, "Date", message.date() == null ? null : message.date().toString());
        String body = parsed.html() ? pandoc.htmlToMarkdown(parsed.body()) : parsed.body();
        if (body != null) {
            if (!headers.isEmpty()) { headers.append('\n'); }
            headers.append(body);
        }
        return headers.toString();
    }

    private static String display(List<Address> addresses) {
        return addresses.stream().map(Address::display).collect(Collectors.joining(", "));
    }

    private static void header(StringBuilder text, String name, String value) {
        if (value != null && !value.isBlank()) { text.append(name).append(": ").append(value).append('\n'); }
    }
}
