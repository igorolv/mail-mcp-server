package mail.mcp.tools;

import mail.mcp.api.ConnectionInfo;
import mail.mcp.api.ListConnectionsResult;
import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.connections.MailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionToolsTest {

    private static final ObjectMapper MAPPER = new JsonConfig().mailMcpObjectMapper();

    @Test
    void listsConfigurationWithoutSecretsOrServerAddresses(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("connections.json"), """
                {"connections": {
                  "work": {"type": "ews", "url": "https://webmail.example.com/EWS/Exchange.asmx",
                           "username": "user", "password": "${WORK_PASSWORD}", "description": "Work mail"},
                  "gmail": {"type": "imap", "host": "imap.gmail.com", "username": "user@gmail.com",
                            "password": "gmail-secret"},
                  "broken": {"type": "ews", "auth": "kerberos"}
                }}
                """);
        Files.createDirectories(dataDir.resolve("work"));
        ConnectionRegistry registry = new ConnectionRegistry(
                ConnectionsLoader.load(new MailMcpProperties(dataDir.toString(), null), MAPPER,
                        Map.of("WORK_PASSWORD", "work-secret")::get),
                d -> new MailConnection(d, null));

        ListConnectionsResult result = new ConnectionTools(registry).listConnections();

        assertThat(result.connections()).containsExactly(
                new ConnectionInfo("work", "Work mail", "ews", "ntlm", true, true, false, null),
                new ConnectionInfo("gmail", null, "imap", "app-password", true, false, false, null),
                new ConnectionInfo("broken", null, "ews", null, true, false, false,
                        "auth 'kerberos' is not valid for type 'ews' (expected one of: ntlm, basic)"));
        String json = MAPPER.writeValueAsString(result);
        assertThat(json).doesNotContain("secret", "webmail.example.com", "imap.gmail.com", "user@gmail.com",
                "\"user\"");
    }

    @Test
    void emptyConfigurationGivesAnEmptyList(@TempDir Path dataDir) {
        ConnectionRegistry registry = new ConnectionRegistry(
                ConnectionsLoader.load(new MailMcpProperties(dataDir.toString(), null), MAPPER, k -> null),
                d -> new MailConnection(d, null));

        assertThat(new ConnectionTools(registry).listConnections().connections()).isEmpty();
    }
}
