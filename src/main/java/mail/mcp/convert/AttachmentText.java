package mail.mcp.convert;

import jakarta.mail.MessagingException;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.Address;
import mail.mcp.model.AttachmentInfo;
import org.apache.poi.hmef.HMEFMessage;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertyValue.LongPropertyValue;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.CodePageUtil;
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
            String name = (info.name() == null ? info.file() : info.name()).replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            String type = info.contentType().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            boolean eml = extension.equals("eml") || type.equals("message/rfc822");
            boolean zip = extension.equals("zip") || type.equals("application/zip")
                    || type.equals("application/x-zip-compressed") || type.equals("application/x-zip");
            boolean msg = extension.equals("msg") || type.equals("application/vnd.ms-outlook");
            boolean tnef = extension.equals("tnef") || name.equals("winmail.dat")
                    || type.equals("application/ms-tnef") || type.equals("application/vnd.ms-tnef");
            if (!eml && zip) { return Optional.empty(); }
            String format = eml ? "eml" : msg ? "msg" : tnef ? "tnef" : switch (extension) {
                case "doc", "xls", "ppt", "docx", "odt", "rtf", "epub", "html", "csv", "pdf", "xlsx", "pptx", "txt", "eml" -> extension;
                case "htm" -> "html";
                default -> switch (type) {
                    case "application/msword" -> "doc";
                    case "application/vnd.ms-excel" -> "xls";
                    case "application/vnd.ms-powerpoint" -> "ppt";
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
                case "doc" -> DocText.extract(file);
                case "xls" -> XlsText.extract(file);
                case "ppt" -> PptText.extract(file);
                case "eml" -> nestedMessage(file, pandoc);
                case "msg" -> outlookMessage(file, pandoc);
                case "tnef" -> tnefMessage(file, pandoc);
                default -> throw new IllegalStateException();
            };
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (IOException | MessagingException | RuntimeException e) {
            log.debug("{}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String outlookMessage(Path file, Pandoc pandoc) throws IOException {
        try (var fs = new POIFSFileSystem(file.toFile(), true); var message = new MAPIMessage(fs)) {
            // Only declared code pages: guess7BitEncoding also uses heuristics and is deliberately avoided.
            String general = codepage(message, MAPIProperty.MESSAGE_CODEPAGE);
            String internet = codepage(message, MAPIProperty.INTERNET_CPID);
            // Plain text uses the message code page; HTML may declare a different Internet code page.
            String plain = general == null ? internet : general;
            message.set7BitEncoding(plain, internet == null ? general : internet, plain);
            var headers = new StringBuilder();
            try { header(headers, "Subject", message.getSubject()); } catch (ChunkNotFoundException e) { /* absent */ }
            try { header(headers, "From", message.getDisplayFrom()); } catch (ChunkNotFoundException e) { /* absent */ }
            try { header(headers, "To", message.getDisplayTo()); } catch (ChunkNotFoundException e) { /* absent */ }
            try { header(headers, "Cc", message.getDisplayCC()); } catch (ChunkNotFoundException e) { /* absent */ }
            try {
                var date = message.getMessageDate();
                if (date != null) { header(headers, "Date", date.toInstant().toString()); }
            } catch (ChunkNotFoundException e) { /* absent */ }
            String body = null;
            try { body = message.getTextBody(); } catch (ChunkNotFoundException e) { /* next alternative */ }
            if (body == null || body.isBlank()) {
                try { body = message.getHtmlBody(); } catch (ChunkNotFoundException e) { body = null; }
                if (body != null && !body.isBlank()) { body = pandoc.htmlToMarkdown(body); }
                else {
                    try { body = message.getRtfBody(); } catch (ChunkNotFoundException e) { body = null; }
                    if (body != null && !body.isBlank()) { body = pandoc.toMarkdown(body, "rtf"); }
                }
            }
            if (body != null && !body.isBlank()) {
                if (!headers.isEmpty()) { headers.append('\n'); }
                headers.append(body);
            }
            return headers.toString();
        }
    }

    private static String codepage(MAPIMessage message, MAPIProperty property) throws IOException {
        var values = message.getMainChunks().getProperties().get(property);
        return values == null || values.isEmpty() ? null
                : CodePageUtil.codepageToEncoding(((LongPropertyValue) values.getFirst()).getValue(), true);
    }

    private static String tnefMessage(Path file, Pandoc pandoc) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var message = new HMEFMessage(input);
            var text = new StringBuilder();
            header(text, "Subject", message.getSubject());
            String body = message.getBody();
            if (body != null && !body.isBlank()) {
                String markdown = pandoc.toMarkdown(body, "rtf");
                if (!markdown.isBlank()) {
                    if (!text.isEmpty()) { text.append('\n'); }
                    text.append(markdown);
                }
            }
            return text.toString();
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
