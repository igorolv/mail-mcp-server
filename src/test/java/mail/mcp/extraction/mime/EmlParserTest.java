package mail.mcp.extraction.mime;

import mail.mcp.extraction.ExtractionContext;
import mail.mcp.extraction.ExtractionLimitException;
import mail.mcp.extraction.ExtractionLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static mail.mcp.extraction.mime.ParsedMail.*;
import static org.assertj.core.api.Assertions.*;

class EmlParserTest {
    @TempDir Path output;

    @ParameterizedTest
    @ValueSource(strings = {"input", "part", "derived", "parts", "headers", "entityHeaders", "headerBytes",
            "headerLength", "mimeDepth", "files", "text", "htmlBytes", "htmlNodes"})
    void reportsLimitAndNeverLeavesAnIncompleteFile(String kind) throws Exception {
        String source = "Subject: synthetic\r\n" + multipart("text/html; charset=utf-8", "<p>First body</p>",
                "application/octet-stream", "0123456789");
        var limits = limits(Map.of(kind, kind.equals("headerLength") ? 20L : 1L));
        var context = new ExtractionContext(limits);
        var mail = parse(source, context);
        assertThat(mail.status()).as(kind + " " + mail.diagnostics()).isEqualTo(Status.LIMIT_EXCEEDED);
        assertThat(mail.diagnostics()).isNotEmpty();
        try (var files = Files.walk(mail.directory())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) assertThat(Files.size(file)).isGreaterThan(0);
        }
        assertThat(context.usage().derivedBytes()).isLessThanOrEqualTo(limits.maxDerivedBytes());
    }

    @Test void budgetsAreSharedAcrossEmlReentryAndFutureContainerTypes() throws Exception {
        String nested = text("inner");
        String source = multipart("text/plain; charset=utf-8", "outer", "message/rfc822", nested);
        long parentBytes = 5 + nested.getBytes(StandardCharsets.UTF_8).length;
        var context = new ExtractionContext(limits(Map.of("derived", parentBytes)));
        var mail = parse(source, context);
        assertThat(mail.status()).isEqualTo(Status.LIMIT_EXCEEDED);
        assertThat(mail.root().children().get(1).nestedMessage().diagnostics())
                .extracting(Diagnostic::code).contains("derived-bytes-limit");
        assertThat(context.usage().derivedBytes()).isEqualTo(parentBytes);
        var child = context.child();
        assertThat(child.usage()).isEqualTo(context.usage());
        assertThatThrownBy(() -> child.derivedBytes(1)).isInstanceOf(ExtractionLimitException.class);
    }

    @Test void stopsBeforeEnteringAnEmbeddedMessage() throws Exception {
        var mail = parse(multipart("text/plain; charset=utf-8", "outer", "message/rfc822", text("inner")),
                new ExtractionContext(limits(Map.of("containerDepth", 0L))));
        Part eml = mail.root().children().get(1);
        assertThat(mail.status()).isEqualTo(Status.LIMIT_EXCEEDED);
        assertThat(mail.body().text()).isEqualTo("outer");
        assertThat(eml.storagePath()).exists();
        assertThat(eml.nestedMessage()).isNull();
        assertThat(eml.status()).isEqualTo(Status.LIMIT_EXCEEDED);
        assertThat(mail.diagnostics()).contains(new Diagnostic("container-depth-limit", "2"));
    }

    @Test void expiredContextFailsBeforeCreatingFiles() throws Exception {
        var context = new ExtractionContext(limits(Map.of("durationNanos", 1L)));
        assertThatThrownBy(() -> parse(text("body"), context)).isInstanceOf(ExtractionLimitException.class)
                .hasMessage("time-limit");
        try (var files = Files.list(output)) { assertThat(files).isEmpty(); }
    }

    @Test void ioFailureRollsBackOnlyItsOwnDirectory() throws Exception {
        Path sentinel = Files.writeString(output.resolve("keep.txt"), "keep");
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("synthetic source failure"); }
        };
        assertThatThrownBy(() -> new EmlParser().parse(broken, output,
                new ExtractionContext(ExtractionLimits.defaults()))).isInstanceOf(IOException.class);
        try (var files = Files.list(output)) { assertThat(files.toList()).containsExactly(sentinel); }
    }

    @Test void outputNamesIgnoreSenderAndSeparateEveryInvocation() throws Exception {
        String source = multipart("text/plain; charset=utf-8", "body", "text/plain", "attachment")
                .replace("Content-Type: text/plain\r\n", "Content-Type: text/plain\r\n"
                        + "Content-Disposition: attachment; filename=\"..\\..\\CON.txt:stream\"\r\n");
        ParsedMail one = parse(source), two = parse(source);
        assertThat(one.directory()).isNotEqualTo(two.directory());
        Part part = one.root().children().get(1);
        assertThat(part.storagePath()).exists().hasParent(one.directory());
        assertThat(part.storagePath().getFileName().toString()).matches("[0-9a-f-]{36}\\.bin");
        assertThat(one.body().text()).isEqualTo("body");
        assertThat(Files.readString(part.storagePath())).isEqualTo("attachment");
    }

    @Test void relatedStartBeatsAnEarlierPlainResourceAndCidRemainsScoped() throws Exception {
        String source = multipart("text/plain; charset=utf-8", "resource", "text/html; charset=utf-8", "<p>root</p>")
                .replace("multipart/mixed; boundary=b", "multipart/related; boundary=b; start=\"<root@example.com>\"")
                .replace("Content-Type: text/html; charset=utf-8", "Content-ID: <root@example.com>\r\nContent-Type: text/html; charset=utf-8");
        var mail = parse(source);
        assertThat(mail.body().partPath()).isEqualTo("2");
        assertThat(mail.body().text()).isEqualTo("root");
        assertThat(mail.root().children().get(1).contentId()).isEqualTo("root@example.com");
    }

    @Test void emptyPlainFallsBackToHtmlAndKeepsFormattingWithoutActiveContent() throws Exception {
        String source = multipart("text/plain; charset=utf-8", "  ", "text/html; charset=utf-8",
                "<script>secretScript</script><style>secretCss</style><p hidden>secretHidden</p>"
                        + "<div style='display: none'>secretInline</div><p>One<br>Two</p><ul><li>Item</li></ul>"
                        + "<table><tr><td>A</td><td>B</td></tr></table><pre> a  b\n c</pre>"
                        + "<a href='https://example.com/layout'>Link</a><img src='https://example.com/pixel' alt='image'>");
        var mail = parse(source);
        assertThat(mail.body().contentType()).isEqualTo("text/html");
        assertThat(mail.body().text()).contains("One\nTwo", "- Item", "A", "B", " a  b\n c",
                "Link (https://example.com/layout)", "image").doesNotContain("secret", "<script", "pixel");
    }

    @Test void attachmentTextNeverBecomesParentBody() throws Exception {
        String source = multipart("text/plain; charset=utf-8", "attachment", "text/plain; charset=utf-8", "body")
                .replaceFirst("Content-Type: text/plain; charset=utf-8", "Content-Disposition: attachment; filename=a.txt\r\n"
                        + "Content-Type: text/plain; charset=utf-8");
        assertThat(parse(source).body().text()).isEqualTo("body");
    }

    @Test void preservesThreadHeadersRepeatedRecipientsAndInvalidDates() throws Exception {
        var mail = parse("Subject: Test\r\nCc: one@example.com\r\nCc: Group: two@example.org;\r\n"
                + "Reply-To: Reply <reply@example.net>\r\nDate: 31 Feb 2026 10:00:00 +0300\r\n"
                + "In-Reply-To: <parent@example.com>\r\nReferences: <one@example.com>\r\n <two@example.com>\r\n"
                + text("body"));
        assertThat(mail.headers().date()).isNull();
        assertThat(mail.headers().cc()).extracting(Address::address).containsExactly("one@example.com", "two@example.org");
        assertThat(mail.headers().replyTo()).containsExactly(new Address("Reply", "reply@example.net"));
        assertThat(mail.headers().inReplyTo()).isEqualTo("<parent@example.com>");
        assertThat(mail.headers().references()).containsExactly("<one@example.com>", "<two@example.com>");
    }

    @Test void unknownCharsetIsExplicitAndOriginalBytesRemainAvailable() throws Exception {
        var mail = parse("Content-Type: text/plain; charset=made-up\r\n\r\nbody");
        assertThat(mail.status()).isEqualTo(Status.PARTIAL);
        assertThat(mail.body().charsetDeclared()).isEqualTo("made-up");
        assertThat(mail.body().charsetUsed()).isEqualTo("US-ASCII");
        assertThat(mail.diagnostics()).extracting(Diagnostic::code).contains("charset-unsupported");
        assertThat(Files.readString(mail.root().storagePath())).isEqualTo("body");
    }

    @Test void attachedMultipartIsExplicitlyUnsupportedAndLeavesSurvive() throws Exception {
        var mail = parse("Content-Disposition: attachment; filename=container.mime\r\n"
                + multipart("text/plain; charset=utf-8", "one", "text/plain; charset=utf-8", "two"));
        assertThat(mail.body()).isNull();
        assertThat(mail.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(mail.root().format()).isEqualTo("multipart_attachment");
        assertThat(mail.diagnostics()).extracting(Diagnostic::code).contains("unexpanded-multipart-attachment");
        for (var part : mail.root().children()) assertThat(part.storagePath()).exists();
    }

    @Test void missingMultipartBoundaryReportsParseErrorWithoutRetry() throws Exception {
        var mail = parse("Content-Type: multipart/mixed\r\n\r\nbody");
        assertThat(mail.status()).isEqualTo(Status.PARSE_ERROR);
        assertThat(mail.diagnostics()).extracting(Diagnostic::code).contains("mime-parse-error");
        assertThat(mail.body()).isNull();
    }

    private ParsedMail parse(String source) throws IOException { return parse(source, new ExtractionContext(ExtractionLimits.defaults())); }
    private ParsedMail parse(String source, ExtractionContext context) throws IOException {
        try (var input = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))) {
            return new EmlParser().parse(input, output, context);
        }
    }
    private static String text(String body) { return "Content-Type: text/plain; charset=utf-8\r\n\r\n" + body; }
    private static String multipart(String type1, String body1, String type2, String body2) {
        return "Content-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\nContent-Type: " + type1
                + "\r\n\r\n" + body1 + "\r\n--b\r\nContent-Type: " + type2 + "\r\n\r\n" + body2 + "\r\n--b--\r\n";
    }
    private static ExtractionLimits limits(Map<String, Long> overrides) {
        var d = ExtractionLimits.defaults();
        return new ExtractionLimits(overrides.getOrDefault("input", d.maxInputBytes()),
                overrides.getOrDefault("part", d.maxPartBytes()), overrides.getOrDefault("derived", d.maxDerivedBytes()),
                overrides.getOrDefault("parts", (long) d.maxParts()).intValue(),
                overrides.getOrDefault("entityHeaders", (long) d.maxHeadersPerEntity()).intValue(),
                overrides.getOrDefault("headers", (long) d.maxHeaders()).intValue(),
                overrides.getOrDefault("headerBytes", d.maxHeaderBytes()),
                overrides.getOrDefault("headerLength", (long) d.maxHeaderLength()).intValue(),
                overrides.getOrDefault("mimeDepth", (long) d.maxMimeDepth()).intValue(),
                overrides.getOrDefault("containerDepth", (long) d.maxContainerDepth()).intValue(),
                overrides.getOrDefault("files", (long) d.maxFiles()).intValue(),
                overrides.getOrDefault("text", (long) d.maxTextChars()).intValue(),
                overrides.getOrDefault("htmlBytes", (long) d.maxHtmlBytes()).intValue(),
                overrides.getOrDefault("htmlNodes", (long) d.maxHtmlNodes()).intValue(),
                Duration.ofNanos(overrides.getOrDefault("durationNanos", d.maxDuration().toNanos())));
    }
}
