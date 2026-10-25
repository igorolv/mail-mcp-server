package mail.mcp.source.imap;

import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionType;
import mail.mcp.connections.MailConnectionFactory;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImapClientTest {
    @TempDir Path temp;
    private static final byte[] MIME = ("From: test@example.com\r\nTo: reader@example.org\r\n"
            + "Subject: Synthetic IMAP\r\n\r\nSynthetic body, never real mail.\r\n").getBytes(StandardCharsets.US_ASCII);

    private static ImapSettings settings(int port) {
        return new ImapSettings("127.0.0.1", port, true, AuthKind.APP_PASSWORD, "user", "synthetic-test-value");
    }

    @Test void examinePeekFlagsAndOriginalBytesOnOneAuthenticatedSocket() throws Exception {
        try (var server = new FakeImap(false, false, false); var client = new ImapClient(settings(server.port()), false)) {
            var folders = client.listFolders();
            assertThat(folders).hasSize(2);
            assertThat(folders.get(1).holdsMessages()).isFalse();
            var file = temp.resolve("one.eml");
            var item = client.downloadLastMessage("INBOX", file, 1024).orElseThrow();
            assertThat(Files.readAllBytes(file)).isEqualTo(MIME);
            assertThat(item.uidValidity()).isEqualTo(7);
            assertThat(item.uid()).isEqualTo(42);
            assertThat(item.bytes()).isEqualTo(MIME.length);
            assertThat(item.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MIME)));
            assertThat(item.seenBefore()).isFalse();
            assertThat(item.seenAfter()).isFalse();
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
            assertThat(server.commands).anyMatch(s -> s.startsWith("EXAMINE "));
            assertThat(server.commands).anyMatch(s -> s.contains("BODY.PEEK[]"));
            assertThat(server.commands.stream().filter(s -> s.contains("FLAGS") && s.startsWith("FETCH "))).hasSize(2);
            assertThat(server.commands).noneMatch(s -> s.matches("(?i).*(SELECT |STORE |EXPUNGE|APPEND |CLOSE).*"));
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
        }
    }

    @Test void refusedCredentialsAreNeverRetriedAndProviderTextIsWithheld() throws Exception {
        try (var server = new FakeImap(true, false, false); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class)
                    .hasMessageContaining("sign-in rejected").hasMessageNotContaining("private-server-text");
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
        }
    }

    @Test void plainAuthenticationAlsoUsesOneCredentialExchange() throws Exception {
        try (var server = new FakeImap(false, false, false, true);
             var client = new ImapClient(settings(server.port()), false)) {
            assertThat(client.listFolders()).hasSize(2);
            assertThat(client.downloadLastMessage("INBOX", temp.resolve("plain.eml"), 1024)).isPresent();
            assertThat(server.commands.stream().filter(s -> s.startsWith("AUTHENTICATE ")))
                    .containsExactly("AUTHENTICATE PLAIN");
            assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN "));
        }
    }

    @Test void rejectedPlainDoesNotFallBackToAdvertisedLogin() throws Exception {
        try (var server = new FakeImap(true, false, false, true);
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
            try (var server = new FakeImap(false, false, false, mode);
                 var client = new ImapClient(oauthSettings(server.port()), false, () -> "FICTION_ACCESS")) {
                assertThat(client.listFolders()).hasSize(2);
                assertThat(client.downloadLastMessage("INBOX", temp.resolve(mode + ".eml"), 1024)).isPresent();
                assertThat(server.commands.stream().filter(s -> s.startsWith("AUTHENTICATE XOAUTH2"))).hasSize(1);
                assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN ") || s.startsWith("AUTHENTICATE PLAIN"));
            }
        }
    }

    @Test void oauthErrorChallengeIsAcknowledgedEmptyWithoutResendingTokenOrFallingBack() throws Exception {
        try (var server = new FakeImap(true, false, false, "oauth-ir");
             var client = new ImapClient(oauthSettings(server.port()), false, () -> "FICTION_ACCESS")) {
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("sign-in rejected")
                    .hasMessageNotContaining("FICTION_ACCESS");
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
            assertThat(server.commands.stream().filter(s -> s.startsWith("AUTHENTICATE XOAUTH2"))).hasSize(1);
            assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN ") || s.startsWith("AUTHENTICATE PLAIN"));
        }
    }

    @Test void missingXoauth2CapabilityDoesNotSendTokenThroughLoginFallback() throws Exception {
        try (var server = new FakeImap(false, false, false, true);
             var client = new ImapClient(oauthSettings(server.port()), false, () -> "FICTION_ACCESS")) {
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class);
            assertThat(server.commands).noneMatch(s -> s.startsWith("LOGIN ") || s.startsWith("AUTHENTICATE"));
        }
    }

    private static ImapSettings oauthSettings(int port) {
        return new ImapSettings("127.0.0.1", port, true, AuthKind.OAUTH2, "user", null);
    }

    @Test void limitRemovesPartialFileAndPreservesExistingTarget() throws Exception {
        Path target = temp.resolve("existing.eml");
        Files.writeString(target, "existing");
        try (var server = new FakeImap(false, false, false); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.downloadLastMessage("INBOX", target, 12))
                    .isInstanceOf(ImapException.class).hasMessageContaining("byte limit");
        }
        assertThat(Files.readString(target)).isEqualTo("existing");
        try (var files = Files.list(temp)) { assertThat(files.toList()).containsExactly(target); }
    }

    @Test void changedFlagsAreDetectedUsingAFreshFetch() throws Exception {
        try (var server = new FakeImap(false, true, false); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.downloadLastMessage("INBOX", temp.resolve("changed.eml"), 1024))
                    .isInstanceOf(ImapException.class).hasMessageContaining("operation failed");
            assertThat(Files.exists(temp.resolve("changed.eml"))).isFalse();
        }
    }

    @Test void successfulDownloadNeverReplacesAnExistingOriginal() throws Exception {
        Path target = temp.resolve("existing.eml");
        Files.writeString(target, "existing original");
        try (var server = new FakeImap(false, false, false); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.downloadLastMessage("INBOX", target, 1024))
                    .isInstanceOf(ImapException.class).hasMessageContaining("save IMAP MIME");
        }
        assertThat(Files.readString(target)).isEqualTo("existing original");
        try (var files = Files.list(temp)) { assertThat(files.toList()).containsExactly(target); }
    }

    @Test void emptyInboxDoesNotFetchMime() throws Exception {
        try (var server = new FakeImap(false, false, true); var client = new ImapClient(settings(server.port()), false)) {
            assertThat(client.downloadLastMessage("INBOX", temp.resolve("empty.eml"), 1024)).isEmpty();
            assertThat(server.commands).noneMatch(s -> s.contains("FETCH"));
            assertThat(Files.exists(temp.resolve("empty.eml"))).isFalse();
        }
    }

    @Test void guardRejectsEveryMutationAndNonPeekFetch() {
        for (String command : List.of("SELECT", "STORE 1 +FLAGS (\\Seen)", "UID STORE 42 +FLAGS (\\Deleted)",
                "EXPUNGE", "UID EXPUNGE", "APPEND", "COPY", "MOVE", "CREATE", "DELETE", "RENAME",
                "SUBSCRIBE", "UNSUBSCRIBE", "SETACL", "DELETEACL", "SETQUOTA", "CLOSE",
                "FETCH 1 (BODY[])", "FETCH 1 (RFC822)", "UID FETCH 42 (BODY[TEXT])", "NOOP\r\nEXPUNGE")) {
            assertThatThrownBy(() -> ReadOnlyImapStore.requireReadCommand(command)).isInstanceOf(ProtocolException.class);
        }
    }

    @Test void productionFactoryIsLazyAndRequiresVerifiedTls() {
        var d = new ConnectionDefinition("gmail", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD,
                null, settings(1), temp, null);
        try (var live = MailConnectionFactory.standard().create(d)) {
            assertThat(live.imap()).isNotNull(); // port 1 is never contacted
            assertThat(live.ews()).isNull();
        }
        var p = ImapClient.properties();
        assertThat(p.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p).doesNotContainKey("mail.imaps.ssl.trust");
        assertThatThrownBy(() -> new ImapClient(new ImapSettings("imap.example.com", 993, false,
                AuthKind.APP_PASSWORD, "user", "synthetic"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeImap implements AutoCloseable {
        final ServerSocket listener;
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> task;
        final List<String> commands = new CopyOnWriteArrayList<>();
        volatile Socket socket;

        FakeImap(boolean reject, boolean changeSeen, boolean empty) throws IOException {
            this(reject, changeSeen, empty, false);
        }

        FakeImap(boolean reject, boolean changeSeen, boolean empty, boolean plain) throws IOException {
            this(reject, changeSeen, empty, plain ? "plain" : "login");
        }

        FakeImap(boolean reject, boolean changeSeen, boolean empty, String mode) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            listener.setSoTimeout(5000);
            task = executor.submit(() -> {
                try (var accepted = listener.accept()) {
                    socket = accepted;
                    accepted.setSoTimeout(5000);
                    var in = new BufferedReader(new InputStreamReader(accepted.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream out = accepted.getOutputStream();
                    boolean oauth = mode.startsWith("oauth"), initial = mode.equals("oauth-ir");
                    String capabilities = "IMAP4rev1" + (oauth ? " AUTH=XOAUTH2 AUTH=PLAIN AUTH=LOGIN" : mode.equals("plain") ? " AUTH=PLAIN AUTH=LOGIN" : "")
                            + (initial ? " SASL-IR" : "");
                    send(out, "* OK [CAPABILITY " + capabilities + "] synthetic endpoint\r\n");
                    String line;
                    int flagFetches = 0;
                    while ((line = in.readLine()) != null) {
                        int space = line.indexOf(' ');
                        String tag = line.substring(0, space), command = line.substring(space + 1);
                        commands.add(command);
                        if (command.equals("CAPABILITY")) { send(out, "* CAPABILITY " + capabilities + "\r\n"); }
                        else if (command.startsWith("AUTHENTICATE XOAUTH2")) {
                            String encoded;
                            if (initial) { encoded = command.substring("AUTHENTICATE XOAUTH2 ".length()); }
                            else { send(out, "+ \r\n"); encoded = in.readLine(); }
                            String decoded = new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                            assertThat(decoded).isEqualTo("user=user\001auth=Bearer FICTION_ACCESS\001\001");
                            if (reject) {
                                String challenge = java.util.Base64.getEncoder().encodeToString("{\"status\":\"401\",\"schemes\":\"bearer\"}".getBytes(StandardCharsets.UTF_8));
                                send(out, "+ " + challenge + "\r\n");
                                assertThat(in.readLine()).isEmpty(); // never another credential response
                                send(out, tag + " NO private-server-text\r\n"); continue;
                            }
                        }
                        else if (command.equals("AUTHENTICATE PLAIN")) {
                            send(out, "+ \r\n");
                            String decoded = new String(java.util.Base64.getDecoder().decode(in.readLine()), StandardCharsets.UTF_8);
                            assertThat(decoded).isEqualTo("\0user\0synthetic-test-value");
                            if (reject) { send(out, tag + " NO private-server-text\r\n"); continue; }
                        }
                        else if (command.startsWith("LOGIN ")) {
                            if (reject) { send(out, tag + " NO private-server-text\r\n"); continue; }
                        } else if (command.startsWith("LIST ")) {
                            send(out, "* LIST () \"/\" \"INBOX\"\r\n* LIST (\\Noselect) \"/\" \"Container\"\r\n");
                        } else if (command.startsWith("EXAMINE ")) {
                            send(out, "* FLAGS (\\Seen)\r\n* " + (empty ? 0 : 1) + " EXISTS\r\n"
                                    + "* 0 RECENT\r\n* OK [UIDVALIDITY 7] generation\r\n* OK [UIDNEXT 43] next\r\n");
                            send(out, tag + " OK [READ-ONLY] examined\r\n"); continue;
                        } else if (command.startsWith("FETCH ") && command.contains("BODY.PEEK[]")) {
                            send(out, "* 1 FETCH (BODY[]<0> {" + MIME.length + "}\r\n");
                            out.write(MIME); send(out, ")\r\n");
                        } else if (command.startsWith("FETCH ")) {
                            flagFetches++;
                            send(out, "* 1 FETCH (UID 42 FLAGS (" + (changeSeen && flagFetches > 1 ? "\\Seen" : "") + "))\r\n");
                        } else if (command.equals("LOGOUT")) {
                            send(out, "* BYE shutdown\r\n" + tag + " OK logout\r\n"); break;
                        } else if (!command.equals("NOOP")) { throw new AssertionError("Unexpected synthetic command: " + command); }
                        send(out, tag + " OK done\r\n");
                    }
                } catch (IOException e) { if (!listener.isClosed()) { throw new RuntimeException(e); } }
            });
        }

        int port() { return listener.getLocalPort(); }
        static void send(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.US_ASCII)); out.flush();
        }
        @Override public void close() throws Exception {
            listener.close();
            if (socket != null) { socket.close(); }
            try { task.get(6, java.util.concurrent.TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); }
        }
    }
}
