package mail.mcp.cli;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.index.MailIndex;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class MailReprocessCommandTest {
    @TempDir Path temp;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private void configuration() throws Exception {
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"test":{"type":"imap","host":"imap.example.com",
                "username":"user@example.com","password":"${TEST_CREDENTIAL}"}}}
                """);
    }

    private int run(Supplier<Pandoc> locator, String... args) {
        return MailReprocessCommand.run(args, new MailMcpProperties(temp.toString(), null),
                new PrintStream(output, true, StandardCharsets.UTF_8), locator,
                key -> "SYNTHETIC_TEST_CREDENTIAL");
    }

    private int run() { return run(() -> new Pandoc("pandoc"), "test"); }
    private String output() { return output.toString(StandardCharsets.UTF_8); }
    private MessageStore store() {
        return new MessageStore(temp.resolve("test"), new JsonConfig().mailMcpObjectMapper());
    }

    private MessageMeta save(String mime) throws Exception {
        var store = store();
        String key = store.newKey();
        Path directory = store.createMessageDir(key);
        if (mime != null) { Files.writeString(directory.resolve(MessageStore.MESSAGE_FILE), mime); }
        var meta = new MessageMeta(key, "f1", "INBOX", key, 12, "a".repeat(64), Instant.now(),
                mime == null ? MessageState.FAILED : MessageState.DOWNLOADED,
                mime == null ? "Synthetic download failure" : null);
        store.writeMeta(meta);
        return meta;
    }

    @Test void missingPandocStopsBeforeTheIndex() throws Exception {
        configuration();
        assertThat(run(() -> { throw new PandocException("pandoc not found on PATH"); }, "test")).isEqualTo(2);
        assertThat(output()).contains("RESULT: FAILED (pandoc not found; install pandoc and put it on PATH)");
        assertThat(temp.resolve("test/index")).doesNotExist();
    }

    @Test void lockedIndexExitsFive() throws Exception {
        configuration();
        try (var index = MailIndex.openWriter(temp.resolve("test"))) {
            assertThat(run()).isEqualTo(5);
            assertThat(index.keys()).isEmpty();
        }
        assertThat(output()).contains("RESULT: FAILED (Connection is being synchronized by another process)");
    }

    @Test void reprocessConvertsDownloadedMessagesAndPrintsReasons() throws Exception {
        configuration();
        var good = save("Subject: Good\r\n\r\nPlain body");
        save("Subject: Another good\r\n\r\nSecond body");
        var bad = save("Content-Type: multipart/mixed; boundary=missing\r\n\r\nnot a multipart");
        var failedDownload = save(null);
        String incomplete = store().newKey();
        Files.writeString(store().createMessageDir(incomplete).resolve(MessageStore.MESSAGE_FILE), "No metadata");
        assertThat(run()).isZero();
        assertThat(output().lines().toList()).containsExactly("Mail reprocess (offline)",
                "  failed: MIME parsing failed (ParseException): 1",
                "RESULT: OK (3 messages, 2 converted, 1 failed)");
        assertThat(store().readMeta(good.key()).orElseThrow().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(store().readMeta(bad.key()).orElseThrow().state()).isEqualTo(MessageState.FAILED);
        assertThat(store().readMeta(failedDownload.key())).contains(failedDownload);
        try (var directory = FSDirectory.open(temp.resolve("test/index"));
             var reader = DirectoryReader.open(directory)) {
            var states = new ArrayList<String>();
            for (int i = 0; i < reader.maxDoc(); i++) { states.add(reader.storedFields().document(i).get("state")); }
            assertThat(states).containsExactlyInAnyOrder("converted", "converted", "failed");
        }
    }

    @Test void progressAndFailureReasonsAreCountedAndSorted() throws Exception {
        configuration();
        for (int i = 0; i < 1_000; i++) { save("Subject: Good\r\n\r\nBody"); }
        for (int i = 0; i < 2; i++) {
            save("Content-Type: multipart/mixed; boundary=missing\r\n\r\nnot a multipart");
        }
        save("Content-Type: text/html\r\n\r\n<p>HTML</p>");
        assertThat(run(() -> new Pandoc("nonexistent-pandoc-it05"), "test")).isZero();
        assertThat(output().lines().toList()).containsExactly("Mail reprocess (offline)",
                "  1000 messages processed", "  failed: MIME parsing failed (ParseException): 2",
                "  failed: pandoc I/O failed: 1", "RESULT: OK (1003 messages, 1000 converted, 3 failed)");
    }

    @Test void configurationAndStorageErrorsUseFixedMessages() throws Exception {
        assertThat(run(() -> { throw new AssertionError("Must not locate"); }, "--help")).isZero();
        assertThat(run(() -> new Pandoc("pandoc"))).isEqualTo(2);
        assertThat(run()).isEqualTo(2);
        configuration();
        assertThat(run(() -> new Pandoc("pandoc"), "unknown")).isEqualTo(2);
        Files.writeString(temp.resolve("connections.json"), "not JSON");
        assertThat(run()).isEqualTo(2);
        assertThat(output()).contains("RESULT: FAILED (invalid connections configuration; check the file locally)");
        configuration();
        Files.createDirectories(temp.resolve("test"));
        Files.writeString(temp.resolve("test/index"), "SYNTHETIC_PRIVATE_VALUE");
        assertThat(run()).isEqualTo(4);
        assertThat(output()).contains("RESULT: FAILED (local storage error; details withheld)")
                .doesNotContain("SYNTHETIC_PRIVATE_VALUE");
    }
}
