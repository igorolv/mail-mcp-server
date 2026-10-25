package mail.mcp.source.imap;

import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionType;
import mail.mcp.connections.MailConnectionFactory;
import mail.mcp.source.MessageUnavailableException;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImapClientTest {
    @TempDir Path temp;
    private static final byte[] MIME = ("From: test@example.com\r\nTo: reader@example.org\r\n"
            + "Subject: Synthetic IMAP\r\n\r\nSynthetic body, never real mail.\r\n").getBytes(StandardCharsets.US_ASCII);

    private static ImapSettings settings(int port) {
        return new ImapSettings("127.0.0.1", port, true, AuthKind.APP_PASSWORD, "user", "synthetic-test-value");
    }

    @Test void folderUidsAndDownloadUseOneAuthenticatedSocket() throws Exception {
        try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
            var folders = client.listFolders();
            assertThat(folders).hasSize(2);
            assertThat(folders.get(1).holdsMessages()).isFalse();
            var file = temp.resolve("one.eml");
            var uids = client.folderUids("INBOX");
            var item = client.download("INBOX", 42, file, 1024);
            assertThat(Files.readAllBytes(file)).isEqualTo(MIME);
            assertThat(uids.uidValidity()).isEqualTo(7);
            assertThat(uids.uids()).containsExactly(42L);
            assertThat(item.size()).isEqualTo(MIME.length);
            assertThat(item.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MIME)));
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
            assertThat(server.commands).anyMatch(s -> s.startsWith("EXAMINE "));
            assertThat(server.commands).anyMatch(s -> s.contains("BODY.PEEK[]"));
            assertThat(server.commands).noneMatch(s -> s.matches("(?i).*(SELECT |STORE |EXPUNGE|APPEND ).*"));
            assertThat(client.listFolders()).hasSize(2);
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
            assertThat(server.commands.stream().filter(s -> s.startsWith("LIST ") || s.equals("CLOSE")))
                    .hasSize(3).element(1).isEqualTo("CLOSE");
        }
    }

    @Test void refusedCredentialsAreNeverRetriedAndProviderTextIsWithheld() throws Exception {
        try (var server = new FakeImapServer(true, false, "login"); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class)
                    .hasMessageContaining("sign-in rejected").hasMessageNotContaining("private-server-text");
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
        }
    }

    @Test void plainAuthenticationAlsoUsesOneCredentialExchange() throws Exception {
        try (var server = new FakeImapServer(false, false, "plain");
             var client = new ImapClient(settings(server.port()), false)) {
            assertThat(client.listFolders()).hasSize(2);
            assertThat(client.folderUids("INBOX").uids()).containsExactly(42L);
            assertThat(client.download("INBOX", 42, temp.resolve("plain.eml"), 1024).size()).isEqualTo(MIME.length);
            assertThat(server.commands.stream().filter(s -> s.startsWith("AUTHENTICATE ")))
                    .containsExactly("AUTHENTICATE PLAIN");
            assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN "));
        }
    }

    @Test void rejectedPlainDoesNotFallBackToAdvertisedLogin() throws Exception {
        try (var server = new FakeImapServer(true, false, "plain");
             var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("sign-in rejected");
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
            assertThat(server.commands.stream().filter(s -> s.startsWith("AUTHENTICATE ")))
                    .containsExactly("AUTHENTICATE PLAIN");
            assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN "));
        }
    }

    @Test void oauthWorksWithAndWithoutSaslInitialResponse() throws Exception {
        for (String mode : List.of("oauth", "oauth-ir")) {
            try (var server = new FakeImapServer(false, false, mode);
                 var client = new ImapClient(oauthSettings(server.port()), false, () -> "FICTION_ACCESS")) {
                assertThat(client.listFolders()).hasSize(2);
                assertThat(client.folderUids("INBOX").uids()).containsExactly(42L);
                assertThat(client.download("INBOX", 42, temp.resolve(mode + ".eml"), 1024).size()).isEqualTo(MIME.length);
                assertThat(server.commands.stream().filter(s -> s.startsWith("AUTHENTICATE XOAUTH2"))).hasSize(1);
                assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN ") || s.startsWith("AUTHENTICATE PLAIN"));
            }
        }
    }

    @Test void oauthErrorChallengeIsAcknowledgedEmptyWithoutResendingTokenOrFallingBack() throws Exception {
        try (var server = new FakeImapServer(true, false, "oauth-ir");
             var client = new ImapClient(oauthSettings(server.port()), false, () -> "FICTION_ACCESS")) {
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("sign-in rejected")
                    .hasMessageNotContaining("FICTION_ACCESS");
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
            assertThat(server.commands.stream().filter(s -> s.startsWith("AUTHENTICATE XOAUTH2"))).hasSize(1);
            assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN ") || s.startsWith("AUTHENTICATE PLAIN"));
        }
    }

    @Test void missingXoauth2CapabilityDoesNotSendTokenThroughLoginFallback() throws Exception {
        try (var server = new FakeImapServer(false, false, "plain");
             var client = new ImapClient(oauthSettings(server.port()), false, () -> "FICTION_ACCESS")) {
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class);
            assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN ") || s.startsWith("AUTHENTICATE"));
        }
    }

    private static ImapSettings oauthSettings(int port) {
        return new ImapSettings("127.0.0.1", port, true, AuthKind.OAUTH2, "user", null);
    }

    @Test void declaredSizeOverLimitIsTooLargeWithoutBodyFetch() throws Exception {
        Path target = temp.resolve("existing.eml");
        Files.writeString(target, "existing");
        try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.download("INBOX", 42, target, 12))
                    .isInstanceOfSatisfying(MessageUnavailableException.class, e -> {
                        assertThat(e.reason()).isEqualTo(MessageUnavailableException.Reason.TOO_LARGE);
                        assertThat(e).hasMessage("Message exceeds the size limit").hasNoCause();
                    });
            assertThat(server.commands).noneMatch(s -> s.contains("BODY.PEEK"));
            assertThat(client.listFolders()).hasSize(2);
        }
        assertThat(Files.readString(target)).isEqualTo("existing");
        try (var files = Files.list(temp)) { assertThat(files.toList()).containsExactly(target); }
    }

    @Test void streamOverLimitIsTooLargeRemovesPartialAndKeepsSession() throws Exception {
        byte[] large = "x".repeat(40000).getBytes(StandardCharsets.US_ASCII);
        var folder = new FakeImapServer.MailFolder("INBOX",
                new FakeImapServer.Item(42, large, 1, ""), new FakeImapServer.Item(43, MIME));
        try (var server = new FakeImapServer(folder); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.download("INBOX", 42, temp.resolve("large.eml"), 17000))
                    .isInstanceOfSatisfying(MessageUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(MessageUnavailableException.Reason.TOO_LARGE));
            try (var files = Files.list(temp)) { assertThat(files.toList()).isEmpty(); }
            assertThat(server.commands).anyMatch(s -> s.contains("BODY.PEEK[]<16384.16384>"));
            client.download("INBOX", 43, temp.resolve("next.eml"), 1024);
            assertThat(Files.readAllBytes(temp.resolve("next.eml"))).isEqualTo(MIME);
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
        }
    }

    @Test void successfulDownloadNeverReplacesAnExistingOriginal() throws Exception {
        Path target = temp.resolve("existing.eml");
        Files.writeString(target, "existing original");
        try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.download("INBOX", 42, target, 1024))
                    .isInstanceOf(ImapException.class).hasMessageContaining("save IMAP MIME");
        }
        assertThat(Files.readString(target)).isEqualTo("existing original");
        try (var files = Files.list(temp)) { assertThat(files.toList()).containsExactly(target); }
    }

    @Test void emptyFolderHasNoUidsAndSendsNoFetch() throws Exception {
        try (var server = new FakeImapServer(false, true, "login"); var client = new ImapClient(settings(server.port()), false)) {
            var uids = client.folderUids("INBOX");
            assertThat(uids.uidValidity()).isEqualTo(7);
            assertThat(uids.uids()).isEmpty();
            assertThat(server.commands).noneMatch(s -> s.contains("FETCH"));
            assertThat(Files.exists(temp.resolve("empty.eml"))).isFalse();
        }
    }

    @Test void guardRejectsEveryMutationAndNonPeekFetch() {
        for (String command : List.of("SELECT", "STORE 1 +FLAGS (\\Seen)", "UID STORE 42 +FLAGS (\\Deleted)",
                "EXPUNGE", "UID EXPUNGE", "APPEND", "COPY", "MOVE", "CREATE", "DELETE", "RENAME",
                "SUBSCRIBE", "UNSUBSCRIBE", "SETACL", "DELETEACL", "SETQUOTA",
                "FETCH 1 (BODY[])", "FETCH 1 (RFC822)", "UID FETCH 42 (BODY[TEXT])", "NOOP\r\nEXPUNGE")) {
            assertThatThrownBy(() -> ReadOnlyImapStore.requireReadCommand(command)).isInstanceOf(ProtocolException.class);
        }
    }

    @Test void productionFactoryIsLazyAndRequiresVerifiedTls() {
        var d = new ConnectionDefinition("gmail", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD,
                null, settings(1), temp, null);
        try (var live = MailConnectionFactory.standard().create(d)) {
            try (var source = live.openSource(); var other = live.openSource()) {
                assertThat(source).isInstanceOf(ImapMailSource.class); // port 1 is never contacted
                assertThat(other).isNotSameAs(source);
            }
        }
        var ews = new ConnectionDefinition("work", null, ConnectionType.EWS, AuthKind.NTLM,
                null, null, temp, null);
        try (var live = MailConnectionFactory.standard().create(ews)) {
            assertThatThrownBy(live::openSource).isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("Connection 'work' has no mail source yet");
        }
        var p = ImapClient.properties();
        assertThat(p.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p).doesNotContainKey("mail.imaps.ssl.trust");
        assertThatThrownBy(() -> new ImapClient(new ImapSettings("imap.example.com", 993, false,
                AuthKind.APP_PASSWORD, "user", "synthetic"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void guardAllowsCloseOfAnExaminedMailbox() throws Exception {
        ReadOnlyImapStore.requireReadCommand("CLOSE");
    }

    @Test void switchingFoldersClosesTheExaminedFolderWithoutSecondSignIn() throws Exception {
        try (var server = new FakeImapServer(new FakeImapServer.MailFolder("A", new FakeImapServer.Item(1, MIME)),
                new FakeImapServer.MailFolder("B", new FakeImapServer.Item(2, MIME)));
             var client = new ImapClient(settings(server.port()), false)) {
            client.folderUids("A");
            client.folderUids("B");
            client.download("A", 1, temp.resolve("a.eml"), 1024);
            assertThat(server.commands.stream().filter(s -> s.startsWith("EXAMINE ") || s.equals("CLOSE")))
                    .containsExactly("EXAMINE A", "CLOSE", "EXAMINE B", "CLOSE", "EXAMINE A");
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
            assertThat(server.commands).noneMatch(s -> s.startsWith("SELECT "));
        }
    }

    @Test void goneUidIsUnavailableAndSessionContinues() throws Exception {
        try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.download("INBOX", 99, temp.resolve("gone.eml"), 1024))
                    .isInstanceOfSatisfying(MessageUnavailableException.class, e -> {
                        assertThat(e.reason()).isEqualTo(MessageUnavailableException.Reason.GONE);
                        assertThat(e).hasMessage("Message is no longer on the server").hasNoCause();
                    });
            assertThat(client.listFolders()).hasSize(2);
            client.download("INBOX", 42, temp.resolve("present.eml"), 1024);
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
            assertThat(Files.exists(temp.resolve("gone.eml"))).isFalse();
        }
    }

    @Test void invalidDownloadArgumentsAreRejectedBeforeAnyCommand() throws Exception {
        try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
            for (String folder : new String[] {null, "", " "}) {
                assertThatThrownBy(() -> client.download(folder, 42, temp.resolve("invalid.eml"), 1024))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            for (long invalid : new long[] {0, -1}) {
                assertThatThrownBy(() -> client.download("INBOX", invalid, temp.resolve("invalid.eml"), 1024))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> client.download("INBOX", 42, temp.resolve("invalid.eml"), invalid))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(server.commands).isEmpty();
        }
    }

    @Test void nonPositiveUidValidityOrUidEndsSession() throws Exception {
        var invalidFolders = List.of(new FakeImapServer.MailFolder("INBOX", 0, List.of(), List.of()),
                new FakeImapServer.MailFolder("INBOX", new FakeImapServer.Item(0, MIME)));
        for (var folder : invalidFolders) {
            try (var server = new FakeImapServer(folder); var client = new ImapClient(settings(server.port()), false)) {
                assertThatThrownBy(() -> client.folderUids("INBOX")).isInstanceOfSatisfying(ImapException.class,
                        e -> assertThat(e.kind()).isEqualTo(ImapException.Kind.PROTOCOL));
                assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
                assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
            }
        }
    }

    @Test void zeroByteMimeIsProtocolFailureAndRemovesPartial() throws Exception {
        try (var server = new FakeImapServer(new FakeImapServer.MailFolder("INBOX", new FakeImapServer.Item(1, new byte[0])));
             var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.download("INBOX", 1, temp.resolve("empty.eml"), 1024))
                    .isInstanceOfSatisfying(ImapException.class,
                            e -> assertThat(e.kind()).isEqualTo(ImapException.Kind.PROTOCOL));
            try (var files = Files.list(temp)) { assertThat(files.toList()).isEmpty(); }
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
        }
    }
}
