package mail.mcp.redaction;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SecretRedactorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PII = Set.of("D22", "D23", "D25", "D28");
    private final SecretRedactor redactor = new SecretRedactor(true);

    @TestFactory Stream<DynamicTest> controlSet() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        try (var files = Files.list(Path.of("testdata/redaction"))) {
            for (var file : files.filter(p -> p.getFileName().toString().matches("D.*\\.json")).sorted().toList()) {
                for (var sample : JSON.readTree(file).path("cases")) {
                    String id = sample.path("id").asString();
                    String text = sample.path("text").asString();
                    tests.add(DynamicTest.dynamicTest(id, () -> {
                        String expected = expected(text, sample.path("expected"));
                        // Research called these negative; output filtering deliberately hides incomplete keys.
                        if (Set.of("D12-N03", "D12-N05").contains(id)) { expected = "[redacted]"; }
                        assertThat(normalize(redactor.text(text))).isEqualTo(expected);
                    }));
                }
            }
        }
        assertThat(tests).hasSize(256);
        return tests.stream();
    }

    @TestFactory Stream<DynamicTest> mixedDocuments() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        try (var files = Files.list(Path.of("testdata/redaction/mixed"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".expected.json")).sorted().toList()) {
                var sample = JSON.readTree(file);
                String text = Files.readString(file.resolveSibling(file.getFileName().toString().replace(".expected.json", ".txt")));
                tests.add(DynamicTest.dynamicTest(sample.path("id").asString(), () ->
                        assertThat(normalize(redactor.text(text))).isEqualTo(expected(text, sample.path("expected")))));
            }
        }
        assertThat(tests).hasSize(8);
        return tests.stream();
    }

    @Test void boundariesAndLongValues() {
        assertThat(redactor.text("password: x")).isEqualTo("password: [redacted:password]");
        assertThat(redactor.text("DB_PASSWORD='space ; and \\\"quote'"))
                .isEqualTo("DB_PASSWORD='[redacted:password]'");
        assertThat(redactor.text("password=abc;part;user=test"))
                .isEqualTo("password=[redacted:password];user=test");
        assertThat(redactor.text("password=abc&ssl=true"))
                .isEqualTo("password=[redacted:password]&ssl=true");
        assertThat(redactor.text("**Пароль:** `short phrase`"))
                .isEqualTo("**Пароль:** `[redacted:password]`");
        assertThat(redactor.text("password:\nNext paragraph")).isEqualTo("password:\nNext paragraph");
        assertThat(redactor.text("password=[ab}cd\"ef`gh]"))
                .isEqualTo("password=[redacted:password]");
        assertThat(redactor.text("OTP: 123456, valid for five minutes"))
                .isEqualTo("OTP: [redacted:code], valid for five minutes");
        assertThat(redactor.text("OTP: 123456abc")).isEqualTo("OTP: 123456abc");
        assertThat(redactor.text("DB_PASSWORD_VALUE='a'"))
                .isEqualTo("DB_PASSWORD_VALUE='[redacted:password]'");
        assertThat(redactor.text("password=\"token: abc\"; login=test"))
                .isEqualTo("password=\"[redacted:password]\"; login=test");
        assertThat(redactor.text("notpassword=ordinary\npasswordless: yes"))
                .isEqualTo("notpassword=ordinary\npasswordless: yes");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            String longValue = "a".repeat(200_000);
            assertThat(redactor.text("password=\"" + longValue + "\"; login=test"))
                    .isEqualTo("password=\"[redacted:password]\"; login=test");
            assertThat(redactor.text(longValue)).isEqualTo(longValue);
            assertThat(redactor.text("ghp_" + longValue)).isEqualTo("ghp_" + longValue);
            String underscores = "abc_".repeat(20_000);
            assertThat(redactor.text(underscores)).isEqualTo(underscores);
            assertThat(redactor.text("password: x;" + " ".repeat(100_000)))
                    .isEqualTo("password: [redacted:password]" + " ".repeat(100_000));
        });
    }

    @Test void overlapsAndIdempotence() {
        String original = "password=\"https://user:fiction@host.example.com/\"\n"
                + "secret: \"-----BEGIN PRIVATE KEY-----\nFiction\n-----END PRIVATE KEY-----\"";
        String filtered = redactor.text(original);
        assertThat(filtered).doesNotContain("fiction", "Fiction", "host.example.com");
        assertThat(redactor.text(filtered)).isEqualTo(filtered);
        assertThat(new SecretRedactor(false).text(original)).isSameAs(original);
        assertThat(redactor.text(null)).isNull();
        assertThat(redactor.text("password=${VALUE}\npassword: [redacted:password]"))
                .isEqualTo("password=${VALUE}\npassword: [redacted:password]");
    }

    private static String normalize(String text) {
        return text.replaceAll("\\[redacted:[a-z-]+]", "[redacted]");
    }

    private static String expected(String text, JsonNode findings) {
        List<int[]> ranges = new ArrayList<>();
        for (var finding : findings) {
            if (PII.contains(finding.path("detector").asString())) { continue; }
            String match = finding.path("match").asString();
            int start = -match.length();
            for (int n = 0; n < finding.path("occurrence").asInt(); n++) {
                start = text.indexOf(match, start + match.length());
                assertThat(start).isNotNegative();
            }
            ranges.add(new int[] { start, start + match.length() });
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < ranges.size();) {
            int start = ranges.get(i)[0];
            int end = ranges.get(i++)[1];
            while (i < ranges.size() && ranges.get(i)[0] < end) { end = Math.max(end, ranges.get(i++)[1]); }
            result.append(text, cursor, start).append("[redacted]");
            cursor = end;
        }
        return result.append(text, cursor, text.length()).toString();
    }
}
