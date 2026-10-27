package mail.mcp.cli;

import mail.mcp.config.MailMcpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MailAnalyzeCommandTest {
    @TempDir Path dir;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private int run(String... words) {
        return MailAnalyzeCommand.run(words, new MailMcpProperties(dir.toString(), "invalid-connections.json"),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @Test void printsStemsSourcesAndStopWords() throws Exception {
        Files.writeString(dir.resolve("word-forms.txt"), "# comment\nписьмо писем\nребёнок дети\nчеловек люди");
        assertThat(run("ПИСЕМ", "договоры и", "РЕБЁНОК дети", "человек люди")).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Word forms: " + dir.resolve("word-forms.txt"),
                "писем -> письм (word-forms.txt line 2)", "договоры -> договор (Snowball)",
                "и -> stop word", "ребёнок -> ребенок (word-forms.txt line 3)",
                "дети -> ребенок (word-forms.txt line 3)", "человек -> человек (word-forms.txt line 4)",
                "люди -> человек (word-forms.txt line 4)", "RESULT: OK");
        Files.delete(dir.resolve("word-forms.txt"));
        output.reset();
        assertThat(run("писем", "человек")).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("none found", "писем -> пис (Snowball)",
                "человек -> stop word", "RESULT: OK");
        Files.writeString(dir.resolve("word-forms.txt"), "one");
        output.reset();
        assertThat(run("word")).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("RESULT: FAILED (word-forms.txt line 1:");
        assertThat(run()).isEqualTo(2);
        assertThat(run("--help")).isZero();
        assertThat(dir.resolve("index")).doesNotExist();
    }
}
