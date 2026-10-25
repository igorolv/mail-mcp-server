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

/** Connection selection of the spike. Every case stops before any network access. */
class EwsSpikeCommandTest {

    @TempDir
    Path dataDir;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private int run(String... args) {
        return EwsSpikeCommand.run(args, new MailMcpProperties(dataDir.toString(), null),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return output.toString(StandardCharsets.UTF_8);
    }

    private void connections(String json) throws Exception {
        Files.writeString(dataDir.resolve("connections.json"), json);
    }

    @Test
    void helpPrintsUsage() {
        assertThat(run("--help")).isEqualTo(EwsSpikeCommand.OK);
        assertThat(output()).contains("Usage: java -jar mail-mcp-server.jar ews-spike [connection]");
    }

    @Test
    void withoutAnEwsConnectionNothingIsContacted() {
        assertThat(run()).isEqualTo(EwsSpikeCommand.CONFIG);
        assertThat(output()).contains("no connection of type ews is configured");
    }

    @Test
    void unknownNameListsTheConfiguredOnes() throws Exception {
        connections("{\"connections\": {\"gmail\": {\"type\": \"imap\"}}}");

        assertThat(run("work")).isEqualTo(EwsSpikeCommand.CONFIG);
        assertThat(output()).contains("no connection named 'work'; configured: gmail");
    }

    @Test
    void nonEwsConnectionIsRefused() throws Exception {
        connections("{\"connections\": {\"gmail\": {\"type\": \"imap\"}}}");

        assertThat(run("gmail")).isEqualTo(EwsSpikeCommand.CONFIG);
        assertThat(output()).contains("is not of type ews");
    }

    @Test
    void misconfiguredConnectionIsRefusedWithItsConfigError() throws Exception {
        connections("{\"connections\": {\"work\": {\"type\": \"ews\", \"username\": \"user\", \"password\": \"p\"}}}");

        assertThat(run()).isEqualTo(EwsSpikeCommand.CONFIG);
        assertThat(output()).contains("connection 'work' is misconfigured: no 'url'");
    }

    @Test
    void severalEwsConnectionsNeedAName() throws Exception {
        connections("""
                {"connections": {"a": {"type": "ews"}, "b": {"type": "ews"}}}
                """);

        assertThat(run()).isEqualTo(EwsSpikeCommand.CONFIG);
        assertThat(output()).contains("name the connection: ews connections: a, b");
    }

    @Test
    void brokenConnectionsFileIsAConfigError() throws Exception {
        connections("{\"connections\": {\"work\": {\"type\": \"ews\", \"password\": \"${MAIL_MCP_TEST_UNSET_VAR}\"}}}");

        assertThat(run()).isEqualTo(EwsSpikeCommand.CONFIG);
        assertThat(output()).contains("RESULT: FAILED (connections file:").contains("MAIL_MCP_TEST_UNSET_VAR");
    }
}
