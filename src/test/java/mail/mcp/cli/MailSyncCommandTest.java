package mail.mcp.cli;

import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.MailConnection;
import mail.mcp.connections.MailConnectionFactory;
import mail.mcp.index.MailIndex;
import mail.mcp.source.ews.EwsAuthenticationException;
import mail.mcp.source.ews.EwsException;
import mail.mcp.source.ews.EwsOperation;
import mail.mcp.source.ews.EwsResponseException;
import mail.mcp.source.ews.EwsSoapFaultException;
import mail.mcp.source.ews.EwsTransportException;
import mail.mcp.source.imap.ImapException;
import mail.mcp.source.oauth.OAuthException;
import mail.mcp.sync.FakeMailSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MailSyncCommandTest {
    @TempDir Path temp;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final MailConnectionFactory neverConnect = d -> { throw new AssertionError("Must not connect"); };

    private int run(MailConnectionFactory factory, String... args) {
        return MailSyncCommand.run(args, new MailMcpProperties(temp.toString(), null),
                new PrintStream(output, true, StandardCharsets.UTF_8), factory,
                key -> "SYNTHETIC_TEST_CREDENTIAL");
    }

    private String output() { return output.toString(StandardCharsets.UTF_8); }

    private void imapConnection() throws IOException {
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"test":{"type":"imap","host":"imap.example.com",
                "username":"user@example.com","password":"${TEST_CREDENTIAL}"}}}
                """);
    }

    @Test void helpAndWrongArgumentsNeverLoadConfiguration() throws Exception {
        Files.writeString(temp.resolve("connections.json"), "not JSON");
        assertThat(run(neverConnect, "--help")).isZero();
        assertThat(run(neverConnect, "-h")).isZero();
        assertThat(run(neverConnect)).isEqualTo(2);
        assertThat(run(neverConnect, "one", "two")).isEqualTo(2);
        assertThat(output()).isEqualTo(("Usage: java -jar mail-mcp-server.jar mail-sync <connection>"
                + System.lineSeparator() + "Run by the user only; signs in to a real mailbox."
                + System.lineSeparator()).repeat(4));
    }

    @Test void unknownConnectionNeverSignsIn() throws Exception {
        imapConnection();
        assertThat(run(neverConnect, "unknown")).isEqualTo(2);
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"test":{"type":"imap"}}}
                """);
        assertThat(run(neverConnect, "test")).isEqualTo(2);
        assertThat(output()).contains("RESULT: FAILED (choose one valid connection by name)");
        assertThat(temp.resolve("test/index")).doesNotExist();
    }

    @Test void ewsFailuresUseFixedMessagesAndExitCodes() throws Exception {
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"work":{"type":"ews","url":"https://webmail.example.com/EWS/Exchange.asmx",
                "username":"user","password":"${TEST_CREDENTIAL}"}}}
                """);
        assertThat(run(d -> { throw new EwsAuthenticationException("webmail.example.com rejected user", "NTLM"); },
                "work")).isEqualTo(3);
        assertThat(output()).contains("RESULT: FAILED (EWS sign-in rejected; check the connection locally before the next run)")
                .doesNotContain("webmail.example.com");
        output.reset();
        assertThat(run(d -> { throw new EwsSoapFaultException(EwsOperation.SYNC_FOLDER_ITEMS,
                "s:Server", "webmail.example.com is busy", "ErrorServerBusy"); }, "work")).isEqualTo(4);
        assertThat(output()).contains("RESULT: FAILED (EWS error: ErrorServerBusy)")
                .doesNotContain("webmail.example.com");
        output.reset();
        assertThat(run(d -> { throw new EwsTransportException("Cannot contact webmail.example.com", null); },
                "work")).isEqualTo(4);
        assertThat(output()).contains("RESULT: FAILED (EWS error: EwsTransportException)")
                .doesNotContain("webmail.example.com");
        for (var failure : new EwsException[]{
                new EwsResponseException(EwsOperation.SYNC_FOLDER_ITEMS, "ErrorAccessDenied", "webmail.example.com"),
                new EwsResponseException(EwsOperation.SYNC_FOLDER_ITEMS, null, "webmail.example.com"),
                new EwsSoapFaultException(EwsOperation.SYNC_FOLDER_ITEMS, "s:Server", "webmail.example.com", null)}) {
            output.reset();
            assertThat(run(d -> { throw failure; }, "work")).isEqualTo(4);
            String code = failure instanceof EwsResponseException response && response.responseCode() != null
                    ? response.responseCode() : failure.getClass().getSimpleName();
            assertThat(output()).contains("RESULT: FAILED (EWS error: " + code + ")")
                    .doesNotContain("webmail.example.com");
        }
        assertThat(temp.resolve("work/index")).doesNotExist();
    }

    @Test void lockedIndexStopsBeforeSignIn() throws Exception {
        imapConnection();
        var source = new FakeMailSource();
        try (var index = MailIndex.openWriter(temp.resolve("test"))) {
            assertThat(run(d -> new MailConnection(d, () -> source), "test")).isEqualTo(5);
            assertThat(index.keys()).isEmpty();
        }
        assertThat(source.foldersCalls).isZero();
        assertThat(source.changesCalls).isZero();
        assertThat(source.downloadCalls).isZero();
        assertThat(output()).contains("RESULT: FAILED (Connection is being synchronized by another process)");
    }

    @Test void successfulRunPrintsFoldersAndTotals() throws Exception {
        imapConnection();
        var source = new FakeMailSource();
        source.putFolder("inbox", "INBOX", "7",
                IntStream.rangeClosed(1, 101).mapToObj(String::valueOf).toArray(String[]::new));
        source.putFolder("archive", "Archive", "9", "1");
        source.markTooLarge("archive", "1");
        MailConnectionFactory factory = d -> new MailConnection(d, () -> source);
        assertThat(run(factory, "test")).isZero();
        assertThat(output().lines().toList()).containsExactly(
                "Mail sync (read-only; one sign-in attempt)",
                "Connection: test (imap, app-password; address and credentials hidden)",
                "  INBOX: 100 downloaded, 0 failed, 0 removed (in progress)",
                "  INBOX: 101 downloaded, 0 failed, 0 removed",
                "  Archive: 0 downloaded, 1 failed, 0 removed",
                "RESULT: OK (2 folders, 101 downloaded, 1 failed, 0 removed)");
        output.reset();
        source.resetCounts();
        source.putFolder("inbox", "INBOX", "8", "1");
        source.removeFolder("archive");
        assertThat(run(factory, "test")).isZero();
        assertThat(output().lines().toList()).containsExactly(
                "Mail sync (read-only; one sign-in attempt)",
                "Connection: test (imap, app-password; address and credentials hidden)",
                "  Archive: 0 downloaded, 0 failed, 1 removed",
                "  INBOX: 1 downloaded, 0 failed, 101 removed, folder reset",
                "RESULT: OK (2 folders, 1 downloaded, 0 failed, 102 removed)");
        assertThat(source.downloadCalls).isEqualTo(1);
    }

    @Test void configurationErrorsWithholdRawValues() throws Exception {
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"test":{"type":"imap","password":{"private":"SYNTHETIC_PRIVATE_VALUE"}}}}
                """);
        assertThat(run(neverConnect, "test")).isEqualTo(2);
        assertThat(output()).contains("RESULT: FAILED (invalid connections configuration; check the file locally)")
                .doesNotContain("SYNTHETIC_PRIVATE_VALUE");
    }

    @Test void failuresUseFixedMessagesAndExitCodes() throws Exception {
        imapConnection();
        for (var kind : ImapException.Kind.values()) {
            var failure = new ImapException(kind);
            assertThat(run(d -> { throw failure; }, "test"))
                    .isEqualTo(kind == ImapException.Kind.AUTHENTICATION ? 3 : 4);
            assertThat(output()).contains("RESULT: FAILED (" + failure.getMessage() + ")");
            output.reset();
        }
        var oauth = new OAuthException(OAuthException.Kind.AUTHORIZE);
        assertThat(run(d -> { throw oauth; }, "test")).isEqualTo(2);
        assertThat(output()).contains("RESULT: FAILED (" + oauth.getMessage() + ")");
        output.reset();
        assertThat(run(d -> { throw new UncheckedIOException(new IOException("SYNTHETIC_PRIVATE_VALUE")); },
                "test")).isEqualTo(4);
        assertThat(output()).contains("RESULT: FAILED (local storage error; details withheld)")
                .doesNotContain("SYNTHETIC_PRIVATE_VALUE");
        output.reset();
        assertThat(run(d -> { throw new IllegalStateException("SYNTHETIC_PRIVATE_VALUE"); }, "test"))
                .isEqualTo(1);
        assertThat(output()).contains("RESULT: FAILED (unexpected local error; details withheld)")
                .doesNotContain("SYNTHETIC_PRIVATE_VALUE");
    }
}
