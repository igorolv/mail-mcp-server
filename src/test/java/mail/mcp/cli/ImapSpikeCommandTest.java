package mail.mcp.cli;

import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.MailConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Invalid selection must stop before the factory can create any network client. */
class ImapSpikeCommandTest {
    @TempDir Path temp;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final MailConnectionFactory neverConnect = d -> { throw new AssertionError("Must not connect"); };

    private int run(String... args) {
        return ImapSpikeCommand.run(args, new MailMcpProperties(temp.toString(), null),
                new PrintStream(output, true, StandardCharsets.UTF_8), neverConnect, key -> null);
    }

    @Test void helpDoesNotLoadConfiguration() {
        assertThat(run("--help")).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("imap-spike [connection]");
    }

    @Test void missingOrUnusableConnectionNeverSignsIn() throws Exception {
        assertThat(run()).isEqualTo(2);
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"gmail":{"type":"imap","host":"imap.example.com"}}}
                """);
        assertThat(run("gmail")).isEqualTo(2);
        assertThat(run("unknown")).isEqualTo(2);
    }

    @Test void ambiguousSelectionNeverSignsIn() throws Exception {
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"one":{"type":"imap"},"two":{"type":"imap"}}}
                """);
        assertThat(run()).isEqualTo(2);
    }

    @Test void malformedConfigurationDoesNotEchoRawValues() throws Exception {
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"gmail":{"type":"imap","password":{"not-a-string":"SYNTHETIC_PRIVATE_VALUE"}}}}
                """);
        assertThat(run("gmail")).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("invalid connections configuration").doesNotContain("SYNTHETIC_PRIVATE_VALUE");
    }
}
