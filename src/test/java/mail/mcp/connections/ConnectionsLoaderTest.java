package mail.mcp.connections;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionsLoaderTest {

    private static final ObjectMapper MAPPER = new JsonConfig().mailMcpObjectMapper();

    private static final UnaryOperator<String> ENV =
            Map.of("WORK_PASSWORD", "work-secret", "GMAIL_APP_PASSWORD", "gmail-secret")::get;

    @TempDir
    Path dataDir;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger loaderLogger = (Logger) LoggerFactory.getLogger(ConnectionsLoader.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        loaderLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        loaderLogger.detachAppender(logs);
    }

    private MailMcpProperties server() {
        return new MailMcpProperties(dataDir.toString(), null);
    }

    private List<ConnectionDefinition> load(String json) throws IOException {
        Files.writeString(dataDir.resolve("connections.json"), json);
        return ConnectionsLoader.load(server(), MAPPER, ENV);
    }

    private ConnectionDefinition loadOne(String entryJson) throws IOException {
        return load("{\"connections\": {\"box\": " + entryJson + "}}").getFirst();
    }

    private List<String> warnings() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void readsEwsAndImapEntriesInFileOrderWithPlaceholders() throws IOException {
        List<ConnectionDefinition> loaded = load("""
                {
                  "connections": {
                    "work": {
                      "type": "ews",
                      "url": "https://webmail.example.com/EWS/Exchange.asmx",
                      "auth": "ntlm",
                      "username": "user",
                      "password": "${WORK_PASSWORD}",
                      "description": "Work mail, Exchange 2013"
                    },
                    "gmail": {
                      "type": "imap",
                      "host": "imap.gmail.com",
                      "port": 993,
                      "tls": true,
                      "auth": "app-password",
                      "username": "user@gmail.com",
                      "password": "${GMAIL_APP_PASSWORD}"
                    }
                  }
                }
                """);

        assertThat(loaded).extracting(ConnectionDefinition::name).containsExactly("work", "gmail");

        ConnectionDefinition work = loaded.getFirst();
        assertThat(work.usable()).isTrue();
        assertThat(work.type()).isEqualTo(ConnectionType.EWS);
        assertThat(work.auth()).isEqualTo(AuthKind.NTLM);
        assertThat(work.description()).isEqualTo("Work mail, Exchange 2013");
        assertThat(work.ews().url().getHost()).isEqualTo("webmail.example.com");
        assertThat(work.ews().username()).isEqualTo("user");
        assertThat(work.ews().password()).isEqualTo("work-secret");
        assertThat(work.ews().domain()).isNull();
        assertThat(work.ews().connectTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(work.ews().readTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(work.connectionDir()).isEqualTo(dataDir.resolve("work"));
        assertThat(work.sourceImplemented()).isTrue();

        ConnectionDefinition gmail = loaded.get(1);
        assertThat(gmail.usable()).isTrue();
        assertThat(gmail.imap().host()).isEqualTo("imap.gmail.com");
        assertThat(gmail.imap().port()).isEqualTo(993);
        assertThat(gmail.imap().password()).isEqualTo("gmail-secret");
        assertThat(gmail.sourceImplemented()).isFalse();
    }

    @Test
    void defaultsAuthAndTimeoutsAndKeepsAnExplicitDomain() throws IOException {
        ConnectionDefinition box = loadOne("""
                {"type": "EWS", "url": "https://webmail.example.com/EWS/Exchange.asmx",
                 "username": "user", "password": "p", "domain": "example", "readTimeoutSeconds": 30}
                """);

        assertThat(box.auth()).isEqualTo(AuthKind.NTLM);
        assertThat(box.ews().domain()).isEqualTo("example");
        assertThat(box.ews().readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(loadOne("""
                {"type": "imap", "host": "imap.mail.ru", "username": "user@mail.ru", "password": "p"}
                """).imap()).satisfies(imap -> {
            assertThat(imap.port()).isEqualTo(993);
            assertThat(imap.tls()).isTrue();
            assertThat(imap.auth()).isEqualTo(AuthKind.APP_PASSWORD);
        });
    }

    @Test
    void missingFileStartsWithNoConnections() {
        assertThat(ConnectionsLoader.load(server(), MAPPER, ENV)).isEmpty();
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains("No mail connections configured"));
    }

    @Test
    void connectionsFileCanBeMovedWithItsOwnSetting() throws IOException {
        Path elsewhere = dataDir.resolve("elsewhere.json");
        Files.writeString(elsewhere, "{\"connections\": {\"a\": {\"type\": \"imap\"}}}");

        List<ConnectionDefinition> loaded = ConnectionsLoader.load(
                new MailMcpProperties(dataDir.toString(), elsewhere.toString()), MAPPER, ENV);

        assertThat(loaded).extracting(ConnectionDefinition::name).containsExactly("a");
    }

    @Test
    void emptyConnectionsObjectStartsWithNoConnections() throws IOException {
        assertThat(load("{\"connections\": {}}")).isEmpty();
    }

    @Test
    void unsetEnvironmentVariableStopsStartupEvenForABrokenEntry() {
        assertThatThrownBy(() -> load("""
                {"connections": {"work": {"type": "nonsense", "password": "${NO_SUCH_VAR}"}}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO_SUCH_VAR")
                .hasMessageContaining("connections.work.password");
    }

    @Test
    void malformedJsonStopsStartup() {
        assertThatThrownBy(() -> load("{\"connections\": {"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read connections file");
    }

    @Test
    void wrongJsonTypeStopsStartup() {
        assertThatThrownBy(() -> load("{\"connections\": {\"g\": {\"type\": \"imap\", \"port\": \"imaps\"}}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Connection 'g'")
                .hasMessageContaining("wrong type");
        assertThatThrownBy(() -> load("{\"connections\": {\"g\": \"imap\"}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a JSON object");
        assertThatThrownBy(() -> load("{\"connections\": {\"g\": null}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no settings");
    }

    @Test
    void invalidNamesStopStartup() {
        assertThatThrownBy(() -> load("{\"connections\": {\"..\": {\"type\": \"ews\"}}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid connection name '..'");
        assertThatThrownBy(() -> load("{\"connections\": {\"a/b\": {\"type\": \"ews\"}}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid connection name 'a/b'");
        assertThatThrownBy(() -> load("{\"connections\": {\"@x\": {\"type\": \"ews\"}}}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nameWithAtGetsItsOwnDirectory() throws IOException {
        ConnectionDefinition box = load("{\"connections\": {\"work@backup\": {\"type\": \"imap\"}}}").getFirst();

        assertThat(box.connectionDir()).isEqualTo(dataDir.resolve("work@backup"));
    }

    @Test
    void unknownKeysAreWarnedAboutAndIgnored() throws IOException {
        ConnectionDefinition box = load("""
                {
                  "connections": {
                    "work": {"type": "ews", "url": "https://webmail.example.com/EWS/Exchange.asmx",
                             "username": "user", "password": "p", "foldres": ["Inbox"]}
                  },
                  "version": 1
                }
                """).getFirst();

        assertThat(box.usable()).isTrue();
        assertThat(warnings())
                .anySatisfy(w -> assertThat(w).contains("Unknown key 'foldres'").contains("connection 'work'"))
                .anySatisfy(w -> assertThat(w).contains("Unknown key 'version'").contains("top level"));
    }

    @Test
    void keysOfTheOtherTypeAreWarnedAbout() throws IOException {
        loadOne("""
                {"type": "ews", "url": "https://webmail.example.com/EWS/Exchange.asmx",
                 "username": "user", "password": "p", "port": 993}
                """);

        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains("Key 'port'").contains("does not apply"));
    }

    @Test
    void entryProblemsBecomeConfigErrorsInsteadOfStoppingStartup() throws IOException {
        String ews = "\"url\": \"https://webmail.example.com/EWS/Exchange.asmx\", \"username\": \"user\", \"password\": \"p\"";

        assertThat(loadOne("{\"username\": \"user\"}").configError()).contains("no 'type'");
        assertThat(loadOne("{\"type\": \"pop3\"}").configError()).contains("unknown type 'pop3'");
        assertThat(loadOne("{\"type\": \"ews\", \"auth\": \"app-password\", " + ews + "}").configError())
                .contains("auth 'app-password' is not valid for type 'ews'").contains("ntlm, basic");
        assertThat(loadOne("{\"type\": \"imap\", \"auth\": \"oauth2\", \"host\": \"imap.gmail.com\"}").configError())
                .contains("'oauth2' is not supported yet");
        assertThat(loadOne("{\"type\": \"ews\", \"username\": \"user\", \"password\": \"p\"}").configError())
                .contains("no 'url'");
        assertThat(loadOne("{\"type\": \"ews\", \"url\": \"http://webmail.example.com/EWS/Exchange.asmx\", "
                + "\"username\": \"user\", \"password\": \"p\"}").configError())
                .contains("https://");
        assertThat(loadOne("{\"type\": \"ews\", \"url\": \"https://webmail.example.com/EWS/Exchange.asmx\", "
                + "\"username\": \"user\"}").configError())
                .contains("no 'password'");
        assertThat(loadOne("{\"type\": \"ews\", " + ews + ", \"connectTimeoutSeconds\": 0}").configError())
                .contains("timeouts");
        assertThat(loadOne("{\"type\": \"imap\", \"username\": \"u\", \"password\": \"p\"}").configError())
                .contains("no 'host'");
        assertThat(loadOne("{\"type\": \"imap\", \"host\": \"imap.mail.ru\", \"tls\": false, "
                + "\"username\": \"u\", \"password\": \"p\"}").configError())
                .contains("plain IMAP");
        assertThat(loadOne("{\"type\": \"imap\", \"host\": \"imap.mail.ru\", \"port\": 70000, "
                + "\"username\": \"u\", \"password\": \"p\"}").configError())
                .contains("'port' must be");
    }

    @Test
    void oneBrokenEntryDoesNotHideTheOthers() throws IOException {
        List<ConnectionDefinition> loaded = load("""
                {"connections": {
                  "broken": {"type": "ews"},
                  "work": {"type": "ews", "url": "https://webmail.example.com/EWS/Exchange.asmx",
                           "username": "user", "password": "p"}
                }}
                """);

        assertThat(loaded).extracting(ConnectionDefinition::usable).containsExactly(false, true);
    }

    @Test
    void passwordsNeverAppearInDefinitionsOrLogs() throws IOException {
        List<ConnectionDefinition> loaded = load("""
                {"connections": {
                  "work": {"type": "ews", "url": "https://webmail.example.com/EWS/Exchange.asmx",
                           "username": "user", "password": "${WORK_PASSWORD}"},
                  "gmail": {"type": "imap", "host": "imap.gmail.com", "username": "user@gmail.com",
                            "password": "${GMAIL_APP_PASSWORD}"}
                }}
                """);

        assertThat(loaded.toString()).doesNotContain("work-secret").doesNotContain("gmail-secret");
        assertThat(warnings()).noneSatisfy(w -> assertThat(w).containsAnyOf("work-secret", "gmail-secret"));
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anySatisfy(info -> assertThat(info).contains("Configured connections: work -> ews/ntlm webmail.example.com"));
    }
}
