package mail.mcp.extraction.mime;

import mail.mcp.extraction.ExtractionContext;
import mail.mcp.extraction.ExtractionLimits;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static mail.mcp.extraction.mime.ParsedMail.*;
import static org.assertj.core.api.Assertions.assertThat;

class EmlParserFixtureTest {
    static final Path FIXTURES = Path.of("testdata/mime");
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path output;

    static Stream<JsonNode> cases() throws IOException {
        return JSON.readTree(FIXTURES.resolve("manifest.json").toFile()).get("cases").valueStream();
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void parsesSyntheticFixture(JsonNode fixture) throws Exception {
        String id = fixture.get("id").asString();
        var expected = JSON.readTree(FIXTURES.resolve(fixture.get("expected").asString()).toFile());
        var context = new ExtractionContext(ExtractionLimits.defaults());
        ParsedMail mail;
        try (var input = Files.newInputStream(FIXTURES.resolve(fixture.get("file").asString()))) {
            mail = new EmlParser().parse(input, output, context);
        }
        var headers = expected.get("headers");
        if (id.equals("M18")) {
            assertThat(mail.headers().subject()).isNotBlank();
            assertThat(mail.diagnostics()).extracting(Diagnostic::code).contains("header-encoded-word-split");
        } else assertThat(mail.headers().subject()).isEqualTo(headers.get("subject").asString());
        assertThat(mail.headers().messageId()).isEqualTo(headers.get("messageId").asString());
        assertAddresses(mail.headers().from(), headers.get("from"));
        assertAddresses(mail.headers().to(), headers.get("to"));
        assertThat(mail.headers().date()).isEqualTo(headers.get("date").isNull() ? null
                : OffsetDateTime.parse(headers.get("date").asString()));

        var body = expected.get("body");
        if (body.get("selectedPart").isNull()) assertThat(mail.body()).isNull();
        else {
            assertThat(mail.body()).isNotNull();
            assertThat(mail.body().partPath()).isEqualTo(body.get("selectedPart").asString());
            assertThat(mail.body().contentType()).isEqualTo(body.get("contentType").asString());
            // M05's actual CP1251 is fixture ground truth, not permission to override a declared charset.
            if (!id.equals("M05")) for (var text : body.get("textContains")) {
                assertThat(mail.body().text()).contains(text.asString());
            }
            for (var text : body.get("textNotContains")) assertThat(mail.body().text()).doesNotContain(text.asString());
        }
        List<Part> parts = mail.root().children().isEmpty() ? List.of(mail.root()) : mail.root().children();
        checkParts(parts, expected.get("parts"), id);
        if (id.equals("M09") || id.equals("M10") || id.equals("M11")) {
            assertThat(mail.status()).isEqualTo(Status.PARTIAL); // Later container/calendar extractors are absent.
        } else if (id.equals("M13")) assertThat(mail.status()).isEqualTo(Status.ENCRYPTED);
        else if (id.equals("M15")) assertThat(mail.status()).isEqualTo(Status.UNSUPPORTED);
        else if (id.equals("M19")) assertThat(mail.status()).isEqualTo(Status.LIMIT_EXCEEDED);
        else if (List.of("M05", "M14", "M16", "M18", "M20").contains(id)) assertThat(mail.status()).isEqualTo(Status.PARTIAL);
        else assertThat(mail.status()).isEqualTo(Status.OK);

        if (id.equals("M05")) {
            assertThat(mail.body().charsetUsed()).isEqualTo("ISO-8859-1");
            assertThat(mail.diagnostics()).extracting(Diagnostic::code).contains("charset-mismatch-suspected");
        }
        if (id.equals("M20")) {
            assertThat(mail.headers().fields().stream().filter(h -> h.name().equalsIgnoreCase("To")).count()).isEqualTo(2);
            assertThat(mail.diagnostics()).extracting(Diagnostic::code).contains("date-invalid", "header-repeated");
        }
        if (id.equals("M12")) assertThat(mail.diagnostics()).extracting(Diagnostic::code).contains("signature-not-verified");
        if (id.equals("M16")) assertThat(mail.diagnostics()).extracting(Diagnostic::code).contains("unsafe-filename");
        assertThat(context.usage().derivedBytes()).isGreaterThan(0);
    }

    private void checkParts(List<Part> actual, JsonNode expected, String id) throws Exception {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < actual.size(); i++) {
            var part = actual.get(i);
            var want = expected.get(i);
            assertThat(part.contentType()).isEqualTo(want.get("contentType").asString());
            assertThat(part.disposition()).isEqualTo(nullable(want, "disposition"));
            assertThat(part.contentId()).isEqualTo(nullable(want, "contentId"));
            assertThat(part.filename()).isEqualTo(nullable(want, "filenameDecoded"));
            if (id.equals("M14") && part.path().equals("2")) {
                assertThat(part.status()).isEqualTo(Status.PARTIAL);
                assertThat(part.storagePath()).isNull();
                continue; // README explicitly permits discarding the damaged attachment with a partial status.
            }
            if (!part.contentType().startsWith("multipart/")) {
                assertThat(part.storagePath()).isNotNull().startsWith(output.toRealPath());
                assertThat(part.storagePath().getFileName().toString()).matches("[0-9a-f-]{36}\\.bin");
                JsonNode byteExpectation = want;
                if (part.format().equals("eml")) {
                    byteExpectation = JSON.readTree(Path.of("src/test/resources/mime/wire-expectations.json").toFile())
                            .get(id).get(want.get("path").asString());
                }
                assertThat(part.size()).isEqualTo(byteExpectation.get("size").asLong());
                assertThat(part.sha256()).isEqualTo(byteExpectation.get("sha256").asString());
                assertThat(Files.size(part.storagePath())).isEqualTo(part.size());
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(part.storagePath())))).isEqualTo(part.sha256());
            }
            if (part.format().equals("eml")) {
                if (part.nestedMessage() == null) {
                    assertThat(part.status()).isEqualTo(Status.LIMIT_EXCEEDED);
                    assertThat(want.get("status").asString()).isEqualTo("limit_exceeded");
                } else {
                    var nestedRoot = part.nestedMessage().root();
                    checkParts(nestedRoot.children().isEmpty() ? List.of(nestedRoot) : nestedRoot.children(),
                            want.get("children"), id);
                    assertThat(part.nestedMessage().body()).isNotNull();
                }
            } else if (List.of("msg", "tnef", "zip", "calendar", "opaque_signed").contains(part.format())) {
                assertThat(part.status()).isEqualTo(Status.UNSUPPORTED);
                assertThat(part.children()).isEmpty();
            } else if (part.contentType().startsWith("multipart/")) checkParts(part.children(), want.get("children"), id);
        }
    }

    private static String nullable(JsonNode node, String key) { return node.get(key).isNull() ? null : node.get(key).asString(); }
    private static void assertAddresses(List<Address> actual, JsonNode expected) {
        List<Address> addresses = new ArrayList<>();
        for (var address : expected) addresses.add(new Address(nullable(address, "name"), address.get("address").asString()));
        assertThat(actual).isEqualTo(addresses);
    }
}
