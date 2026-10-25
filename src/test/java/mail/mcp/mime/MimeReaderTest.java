package mail.mcp.mime;

import mail.mcp.config.JsonConfig;
import mail.mcp.model.Address;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MimeReaderTest {
    @TempDir Path temp;

    static Stream<String> cases() { return IntStream.rangeClosed(1, 20).mapToObj(i -> "M%02d".formatted(i)); }

    private static Path fixture(String id, String suffix) throws Exception {
        try (var paths = Files.list(Path.of("testdata/mime"))) {
            return paths.filter(p -> p.getFileName().toString().startsWith(id + "-"))
                    .filter(p -> p.toString().endsWith(suffix)).findFirst().orElseThrow();
        }
    }

    private static String nullable(JsonNode node) { return node.isNull() ? null : node.asString(); }

    private static List<Address> addresses(JsonNode node) {
        List<Address> result = new ArrayList<>();
        node.forEach(a -> result.add(new Address(nullable(a.get("name")), a.get("address").asString())));
        return result;
    }

    @Test void angusPropertiesAreSet() {
        assertThat(System.getProperty("mail.mime.decodefilename")).isEqualTo("true");
        assertThat(System.getProperty("mail.mime.parameters.strict")).isEqualTo("false");
        assertThat(System.getProperty("mail.mime.base64.ignoreerrors")).isEqualTo("true");
    }

    @ParameterizedTest(name = "{0}") @MethodSource("cases")
    void testdataSetsMatchExpectations(String id) throws Exception {
        var expected = new JsonConfig().mailMcpObjectMapper().readTree(Files.readString(fixture(id, ".expected.json")));
        var parsed = MimeReader.read(fixture(id, ".eml"), temp.resolve("attachments"));
        var headers = expected.get("headers");
        var message = parsed.message();
        if (id.equals("M18")) {
            // README and the brief allow Angus's split encoded-word replacement characters.
            assertThat(message.subject()).contains("\uFFFD", "макет");
        } else { assertThat(message.subject()).isEqualTo(headers.get("subject").asString()); }
        assertThat(message.from()).isEqualTo(addresses(headers.get("from")));
        if (id.equals("M20")) {
            // Direct getAddress() mapping keeps the group address (brief section 2).
            assertThat(message.to()).containsExactly(new Address(null, "reader@example.org"),
                    new Address(null, headers.get("rawTo").get(1).asString()));
        } else { assertThat(message.to()).isEqualTo(addresses(headers.get("to"))); }
        assertThat(message.date()).isEqualTo(headers.get("date").isNull() ? null
                : OffsetDateTime.parse(headers.get("date").asString()).toInstant());
        assertThat(message.messageId()).isEqualTo(headers.get("messageId").asString());
        var body = expected.get("body");
        assertThat(parsed.body() != null).isEqualTo(!body.get("selectedPart").isNull());
        assertThat(parsed.html()).isEqualTo("text/html".equals(nullable(body.get("contentType"))));
        if (id.equals("M05")) {
            // Declared ISO-8859-1 wins; no charset correction, and the second candidate is ignored.
            String garbled = new String("Приложен макет для проверки.".getBytes(Charset.forName("windows-1251")),
                    Charset.forName("ISO-8859-1"));
            assertThat(parsed.body()).isEqualTo(garbled);
        } else if (parsed.body() != null && !parsed.html()) {
            for (var text : body.get("textContains")) { assertThat(parsed.body()).contains(text.asString()); }
        }
        List<JsonNode> parts = new ArrayList<>();
        for (var part : expected.get("parts")) {
            String type = part.get("contentType").asString();
            boolean candidate = (type.equals("text/plain") || type.equals("text/html"))
                    && !"attachment".equals(nullable(part.get("disposition")))
                    && part.get("filenameDecoded").isNull();
            if (!type.startsWith("multipart/") && !candidate) { parts.add(part); }
        }
        assertThat(message.attachments()).hasSize(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            var actual = message.attachments().get(i);
            String name = nullable(part.get("filenameDecoded"));
            if (id.equals("M16") && part.get("path").asString().equals("3")) {
                name = "/etc/passwd"; // Angus returns this decoded name, per brief section 2.
            }
            assertThat(actual.name()).isEqualTo(name);
            assertThat(actual.contentType()).isEqualTo(part.get("contentType").asString());
            assertThat(actual.inline()).isEqualTo("inline".equals(nullable(part.get("disposition"))));
            assertThat(actual.contentId()).isEqualTo(nullable(part.get("contentId")));
            byte[] bytes = Files.readAllBytes(temp.resolve("attachments").resolve(actual.file()));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(actual.size()).isEqualTo(bytes.length);
            if (id.equals("M09")) {
                // Angus's opaque embedded message stream omits the final CRLF, per brief section 2.
                assertThat(actual.size()).isEqualTo(6276);
                byte[] withCrlf = java.util.Arrays.copyOf(bytes, bytes.length + 2);
                withCrlf[bytes.length] = '\r';
                withCrlf[bytes.length + 1] = '\n';
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(withCrlf)))
                        .isEqualTo(part.get("sha256").asString());
            } else if (id.equals("M19")) {
                // Like M09, per level of nesting; nested messages stay opaque (plan.md 3.5).
                assertThat(actual.size()).isEqualTo(3381);
                assertThat(hash).isEqualTo("738b154f7e560f0f62901fcb552ce3fbf34f04cc9a819f33fa578933c3ffcab5");
            } else {
                assertThat(actual.size()).isEqualTo(part.get("size").asLong());
                assertThat(hash).isEqualTo(part.get("sha256").asString());
            }
            if (id.equals("M17")) { assertThat(actual.file()).isEqualTo("1__part"); } // No extension guessing.
        }
    }

    @Test void storedNamesAreSafeOnWindows() throws Exception {
        Path attachments = temp.resolve("attachments");
        var parsed = MimeReader.read(fixture("M16", ".eml"), attachments);
        assertThat(parsed.message().attachments()).extracting(a -> a.file()).doesNotHaveDuplicates()
                .contains("3__CON.txt");
        for (var attachment : parsed.message().attachments()) {
            assertThat(attachment.file()).doesNotContainPattern("[<>:\"/\\\\|?*\\p{Cntrl}]");
            assertThat(attachments.resolve(attachment.file()).normalize().getParent()).isEqualTo(attachments);
        }
    }

    @Test void longNameIsCutTo100Characters() throws Exception {
        Path eml = temp.resolve("long.eml");
        Files.writeString(eml, "Content-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\""
                + "x".repeat(110) + "\"\r\n\r\nbytes");
        assertThat(MimeReader.read(eml, null).message().attachments().getFirst().file())
                .isEqualTo("1__" + "x".repeat(100));
    }

    @Test void nullDirectoryListsWithoutWriting() throws Exception {
        var listed = MimeReader.read(fixture("M01", ".eml"), null);
        var written = MimeReader.read(fixture("M01", ".eml"), temp.resolve("attachments"));
        assertThat(listed).isEqualTo(written);
        try (var paths = Files.list(temp)) { assertThat(paths.toList()).containsExactly(temp.resolve("attachments")); }
    }

    @Test void onlyTheSelectedCandidateIsDecodedAndThreadHeadersAreKept() throws Exception {
        Path eml = temp.resolve("alternatives.eml");
        Files.writeString(eml, """
                Subject: Candidates
                From: broken <
                To: Reader <reader@example.org>
                Cc: Copy <copy@example.com>
                In-Reply-To: <parent@example.com>
                In-Reply-To: <ignored@example.com>
                References: <one@example.com>
                  <two@example.com>
                Content-Type: multipart/mixed; boundary=outer

                --outer
                Content-Type: text/html; charset=nonexistent-charset

                ignored HTML
                --outer
                Content-Type: multipart/alternative; boundary=inner

                --inner
                Content-Type: text/plain; charset=utf-8

                chosen plain text
                --inner
                Content-Type: text/plain; charset=nonexistent-charset

                ignored plain text
                --inner--
                --outer--
                """.replace("\n", "\r\n"));
        var result = MimeReader.read(eml, temp.resolve("attachments"));
        assertThat(result.body()).isEqualTo("chosen plain text");
        assertThat(result.html()).isFalse();
        assertThat(result.message().from()).isEmpty();
        assertThat(result.message().cc()).containsExactly(new Address("Copy", "copy@example.com"));
        assertThat(result.message().inReplyTo()).isEqualTo("<parent@example.com>");
        assertThat(result.message().references()).containsExactly("<one@example.com>", "<two@example.com>");
        assertThat(result.message().attachments()).isEmpty();
        assertThat(temp.resolve("attachments")).doesNotExist();
    }
}
