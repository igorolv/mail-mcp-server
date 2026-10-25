package mail.mcp;

import mail.mcp.config.JsonConfig;
import mail.mcp.index.MailIndex;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.MessageProcessor;
import mail.mcp.convert.Pandoc;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real server in a child JVM and talks MCP over its stdin/stdout, as an MCP client does.
 * Checks that stdout carries JSON-RPC only (no banner, no log line) and that {@code listConnections}
 * answers from configuration without secrets. No mail server is contacted.
 */
class McpStdioServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "stdio-test-secret-value";

    @Test
    void listConnectionsWorksOverStdioAndStdoutIsPureJsonRpc(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("connections.json"), """
                {"connections": {
                  "work": {"type": "ews", "url": "https://webmail.example.com/EWS/Exchange.asmx",
                           "username": "user", "password": "${MAIL_MCP_STDIO_TEST_PASSWORD}",
                           "description": "Work mail"},
                  "mailru": {"type": "imap", "host": "imap.mail.ru"},
                  "local": {"type": "imap", "host": "mail.example.com", "username": "${MAIL_MCP_STDIO_TEST_USERNAME}",
                            "password": "${MAIL_MCP_STDIO_TEST_PASSWORD}"}
                }}
                """);
        prepareLocalData(dataDir.resolve("local"));
        Path argFile = dir.resolve("java.args");
        // An argument file keeps the long test classpath off the Windows command line.
        Files.writeString(argFile, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n");

        ProcessBuilder builder = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "@" + argFile, MailMcpServerApplication.class.getName());
        builder.environment().put("MAIL_MCP_DATA_DIR", dataDir.toString());
        builder.environment().put("MAIL_MCP_STDIO_TEST_PASSWORD", SECRET);
        builder.environment().put("MAIL_MCP_STDIO_TEST_USERNAME", "user@example.com");
        builder.environment().remove("MAIL_MCP_CONNECTIONS_FILE");
        Process server = builder.start();
        ExecutorService io = Executors.newFixedThreadPool(2);
        StringBuilder stderr = new StringBuilder();
        io.submit(() -> drain(server.getErrorStream(), stderr));

        try (var liveWriter = MailIndex.openWriter(dataDir.resolve("local"));
             BufferedWriter requests = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader replies = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> stdout = new ArrayList<>();

            send(requests, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"stdio-test\",\"version\":\"1\"}}}");
            JsonNode initialized = readResponse(io, replies, 1, stdout, stderr);
            assertThat(initialized.at("/result/serverInfo/name").asString()).isEqualTo("mail-mcp-server");
            send(requests, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            send(requests, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            JsonNode tools = readResponse(io, replies, 2, stdout, stderr);
            // Not findValues("name"): the output schema has a "name" property object too.
            List<String> toolNames = new ArrayList<>();
            tools.at("/result/tools").forEach(tool -> toolNames.add(tool.get("name").asString()));
            assertThat(toolNames).containsExactlyInAnyOrder("listConnections", "sync", "syncStatus", "listFolders", "listMessages", "getMessage", "search", "getAttachment");

            send(requests, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"listConnections\",\"arguments\":{}}}");
            JsonNode call = readResponse(io, replies, 3, stdout, stderr);
            JsonNode connections = call.at("/result/structuredContent/connections");
            assertThat(connections.size()).isEqualTo(3);
            assertThat(connections.get(0).get("name").asString()).isEqualTo("work");
            assertThat(connections.get(1).get("name").asString()).isEqualTo("mailru");
            assertThat(connections.get(1).get("configError").asString()).isEqualTo("no 'username'");

            JsonNode folders = call(requests, io, replies, stderr, stdout, 4, "listFolders",
                    "{\"connection\":\"local\"}");
            assertThat(folders.at("/folders/0/messages").asInt()).isEqualTo(2);
            assertThat(folders.at("/folders/0/synced").asBoolean()).isTrue();
            JsonNode messages = call(requests, io, replies, stderr, stdout, 5, "listMessages",
                    "{\"connection\":\"local\",\"folder\":\"f1\"}");
            assertThat(messages.at("/messages").size()).isEqualTo(2);
            String key = messages.at("/messages/0/key").asString();
            JsonNode message = call(requests, io, replies, stderr, stdout, 6, "getMessage",
                    "{\"connection\":\"local\",\"key\":\"" + key + "\",\"maxChars\":10}");
            assertThat(message.get("body").asString()).hasSize(10);
            assertThat(message.get("nextOffset").asInt()).isEqualTo(10);
            assertThat(Path.of(message.get("localPath").asString())).isDirectory();
            JsonNode status = call(requests, io, replies, stderr, stdout, 7, "syncStatus",
                    "{\"connection\":\"local\"}");
            assertThat(status.get("state").asString()).isEqualTo("never");
            // Verify thrown IllegalArgumentException is an MCP tool error, without a network call.
            send(requests, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"listMessages\",\"arguments\":{\"connection\":\"local\",\"folder\":\"missing\"}}}");
            JsonNode invalid = readResponse(io, replies, 8, stdout, stderr);
            assertThat(invalid.at("/result/isError").asBoolean()).isTrue();
            assertThat(invalid.at("/result/content/0/text").asString()).contains("Unknown folder key");

            JsonNode search = call(requests, io, replies, stderr, stdout, 9, "search",
                    "{\"connection\":\"local\",\"query\":\"sender@example.com\",\"field\":\"from\"}");
            assertThat(search.get("total").asInt()).isEqualTo(2);
            assertThat(search.at("/hits/0/folder").asString()).isEqualTo("f1");
            assertThat(Path.of(search.at("/hits/0/localPath").asString())).isDirectory();

            String attachmentFile = message.at("/attachments/0/file").asString();
            JsonNode attachment = call(requests, io, replies, stderr, stdout, 10, "getAttachment",
                    "{\"connection\":\"local\",\"key\":\"" + key + "\",\"file\":\"" + attachmentFile + "\",\"maxChars\":10}");
            assertThat(attachment.get("file").asString()).isEqualTo(attachmentFile);
            assertThat(Path.of(attachment.get("path").asString())).isRegularFile();
            assertThat(attachment.get("text").asString()).isEqualTo("Synthetic ");
            assertThat(attachment.get("nextOffset").asInt()).isEqualTo(10);
            assertThat(Path.of(attachment.get("textPath").asString())).isRegularFile();

            assertThat(stdout).allSatisfy(line -> assertThat(JSON.readTree(line).get("jsonrpc").asString()).isEqualTo("2.0"));
            assertThat(String.join("\n", stdout)).doesNotContain(SECRET, "webmail.example.com", "imap.mail.ru");
        } finally {
            server.destroy();
            if (!server.waitFor(10, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
            io.shutdownNow();
        }
        assertThat(Files.readString(dataDir.resolve("logs").resolve("mail-mcp-server.log")))
                .contains("Configured connections: work -> ews/ntlm")
                .doesNotContain(SECRET);
    }

    private static void prepareLocalData(Path dir) throws Exception {
        var store = new MessageStore(dir, new JsonConfig().mailMcpObjectMapper());
        store.saveFolders(List.of(new LocalFolder("f1", "INBOX", "INBOX", "7")));
        try (var index = MailIndex.openWriter(dir)) {
            for (String fixture : List.of("M01-rfc2231-continuations.eml", "M16-unsafe-filenames.eml")) {
                String key = store.newKey();
                Files.copy(Path.of("testdata/mime", fixture), store.createMessageDir(key).resolve(MessageStore.MESSAGE_FILE));
                var meta = new MessageMeta(key, "f1", "INBOX", fixture, 0, null, Instant.now(), MessageState.DOWNLOADED, null);
                store.writeMeta(meta);
                var processed = new MessageProcessor(store, new Pandoc("unused")).process(meta);
                var attachment = processed.message().attachments().getFirst();
                Files.writeString(store.messageDir(key).resolve(MessageStore.ATTACHMENTS_DIR)
                        .resolve(attachment.file() + ".md"), "Synthetic attachment Markdown");
                index.put(processed.meta(), processed.message(), processed.body(), processed.attachmentText());
            }
            index.commit();
        }
    }

    private static JsonNode call(BufferedWriter requests, ExecutorService io, BufferedReader replies,
                                 StringBuilder stderr, List<String> stdout, int id, String name, String arguments)
            throws Exception {
        send(requests, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}");
        JsonNode response = readResponse(io, replies, id, stdout, stderr);
        assertThat(response.at("/result/isError").asBoolean()).isFalse();
        return response.at("/result/structuredContent");
    }

    private static void send(BufferedWriter requests, String message) throws IOException {
        requests.write(message);
        requests.newLine();
        requests.flush();
    }

    /** Reads stdout lines until the response with {@code id} arrives; every line is kept for checks. */
    private static JsonNode readResponse(ExecutorService io, BufferedReader replies, int id, List<String> stdout,
                                         StringBuilder stderr) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            Future<String> next = io.submit(replies::readLine);
            String line = next.get(60, TimeUnit.SECONDS);
            assertThat(line).as("server closed stdout; stderr:%n%s", stderr).isNotNull();
            stdout.add(line);
            JsonNode message = JSON.readTree(line);
            if (message.has("id") && message.get("id").asInt() == id) {
                assertThat(message.has("error")).as("error response: %s", line).isFalse();
                return message;
            }
        }
        throw new AssertionError("no response with id " + id + "; stderr:\n" + stderr);
    }

    private static void drain(InputStream in, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // the process ended
        }
    }
}
