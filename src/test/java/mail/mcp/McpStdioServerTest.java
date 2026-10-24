package mail.mcp;

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
                  "mailru": {"type": "imap", "host": "imap.mail.ru"}
                }}
                """);
        Path argFile = dir.resolve("java.args");
        // An argument file keeps the long test classpath off the Windows command line.
        Files.writeString(argFile, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n");

        ProcessBuilder builder = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "@" + argFile, MailMcpServerApplication.class.getName());
        builder.environment().put("MAIL_MCP_DATA_DIR", dataDir.toString());
        builder.environment().put("MAIL_MCP_STDIO_TEST_PASSWORD", SECRET);
        builder.environment().remove("MAIL_MCP_CONNECTIONS_FILE");
        Process server = builder.start();
        ExecutorService io = Executors.newFixedThreadPool(2);
        StringBuilder stderr = new StringBuilder();
        io.submit(() -> drain(server.getErrorStream(), stderr));

        try (BufferedWriter requests = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
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
            assertThat(toolNames).containsExactly("listConnections");

            send(requests, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"listConnections\",\"arguments\":{}}}");
            JsonNode call = readResponse(io, replies, 3, stdout, stderr);
            JsonNode connections = call.at("/result/structuredContent/connections");
            assertThat(connections.size()).isEqualTo(2);
            assertThat(connections.get(0).get("name").asString()).isEqualTo("work");
            assertThat(connections.get(1).get("name").asString()).isEqualTo("mailru");
            assertThat(connections.get(1).get("configError").asString()).isEqualTo("no 'username'");

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
