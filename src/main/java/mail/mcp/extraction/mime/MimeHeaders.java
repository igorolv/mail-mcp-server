package mail.mcp.extraction.mime;

import jakarta.mail.internet.ParameterList;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.dom.field.AddressListField;
import org.apache.james.mime4j.dom.field.MailboxListField;
import org.apache.james.mime4j.field.DefaultFieldParser;
import org.apache.james.mime4j.field.datetime.parser.DateTimeParser;

import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static mail.mcp.extraction.mime.ParsedMail.*;

final class MimeHeaders {
    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+>");
    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?[^?]+\\?[bBqQ]\\?[^?]*\\?=");
    private MimeHeaders() { }

    static String first(List<Header> headers, String name) {
        return headers.stream().filter(h -> h.name().equalsIgnoreCase(name))
                .map(Header::rawBody).findFirst().orElse(null);
    }

    static String unfold(String text) {
        return text == null ? null : text.replaceAll("\\r?\\n[ \\t]+", " ").strip();
    }

    static String decode(String text, Consumer<String> warning) {
        if (text == null) return null;
        try {
            DecodeMonitor monitor = new DecodeMonitor() {
                @Override public boolean isListening() { return true; }
                @Override public boolean warn(String error, String action) {
                    warning.accept("header-decoding-defect");
                    return false;
                }
            };
            // Mime4j can join adjacent words before charset decoding. Still report invalid per-word bytes.
            var words = ENCODED_WORD.matcher(unfold(text));
            while (words.find()) {
                if (DecoderUtil.decodeEncodedWords(words.group(), monitor).indexOf('\ufffd') >= 0) {
                    warning.accept("header-encoded-word-split");
                }
            }
            String value = DecoderUtil.decodeEncodedWords(unfold(text), monitor);
            if (value.indexOf('\ufffd') >= 0) warning.accept("header-decoding-defect");
            return value;
        } catch (RuntimeException ex) {
            warning.accept("header-decoding-defect");
            return unfold(text);
        }
    }

    static String token(String text, String fallback) {
        if (text == null) return fallback;
        return unfold(text).split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    static String parameter(String header, String name, Consumer<String> warning) {
        if (header == null || !header.contains(";")) return null;
        try {
            // Uses RFC 2231 continuation decoding without changing global mail.mime properties.
            String unfolded = unfold(header);
            return new ParameterList(unfolded.substring(unfolded.indexOf(';'))).get(name);
        } catch (jakarta.mail.internet.ParseException | RuntimeException ex) {
            warning.accept("mime-parameter-invalid");
            return null;
        }
    }

    static Headers normalize(List<Header> fields, Consumer<String> warning) {
        OffsetDateTime date = null;
        String rawDate = first(fields, "date");
        if (rawDate != null) {
            try {
                var parsed = new DateTimeParser(new StringReader(unfold(rawDate))).parseAll();
                int zone = parsed.getTimeZone();
                date = OffsetDateTime.of(parsed.getYear(), parsed.getMonth(), parsed.getDay(),
                        parsed.getHour(), parsed.getMinute(), parsed.getSecond(), 0,
                        ZoneOffset.ofTotalSeconds((zone / 100 * 60 + zone % 100) * 60));
            } catch (Exception ex) {
                warning.accept("date-invalid");
            }
        }
        List<String> references = new ArrayList<>();
        fields.stream().filter(h -> h.name().equalsIgnoreCase("references"))
                .forEach(h -> MESSAGE_ID.matcher(unfold(h.rawBody())).results().forEach(m -> references.add(m.group())));
        return new Headers(decode(first(fields, "subject"), warning), addresses(fields, "from", warning),
                addresses(fields, "to", warning), addresses(fields, "cc", warning),
                addresses(fields, "reply-to", warning), date, unfold(first(fields, "message-id")),
                unfold(first(fields, "in-reply-to")), references, fields);
    }

    private static List<Address> addresses(List<Header> fields, String name, Consumer<String> warning) {
        List<Address> result = new ArrayList<>();
        for (Header header : fields) {
            if (!header.name().equalsIgnoreCase(name)) continue;
            try {
                var field = DefaultFieldParser.parse(name + ": " + unfold(header.rawBody()));
                MailboxList mailboxes = null;
                if (field instanceof MailboxListField value) mailboxes = value.getMailboxList();
                if (field instanceof AddressListField value && value.getAddressList() != null) {
                    mailboxes = value.getAddressList().flatten();
                }
                if (!field.isValidField() || mailboxes == null) warning.accept("address-invalid");
                if (mailboxes != null) {
                    for (var mailbox : mailboxes) result.add(new Address(mailbox.getName(), mailbox.getAddress()));
                }
            } catch (Exception ex) {
                warning.accept("address-invalid");
            }
        }
        return result;
    }
}
