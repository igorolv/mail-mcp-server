package mail.mcp.source.imap;

import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionType;
import mail.mcp.connections.MailConnectionFactory;
import mail.mcp.source.MessageUnavailableException;
import mail.mcp.source.ews.EwsMailSource;
import mail.mcp.source.ews.EwsSettings;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.net.URI;
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

    @Test void metadataFetchUsesEnvelopeSizeUidAndNeverBodyOrWrites() throws Exception {
        var messages = java.util.stream.LongStream.rangeClosed(1, 205)
                .mapToObj(uid -> new FakeImapServer.Item(uid, MIME, 987654L, "")).toList();
        var folder = new FakeImapServer.MailFolder("INBOX", 7, List.of(), messages);
        try (var server = new FakeImapServer(folder); var client = new ImapClient(settings(server.port()), false)) {
            var uids = client.folderUids("INBOX");
            var result = client.metadata("INBOX", uids.uidValidity(), uids.uids());
            assertThat(result).hasSize(205).containsOnlyKeys(uids.uids().stream().map(String::valueOf).toArray(String[]::new));
            var fingerprint = result.get("42").fingerprint();
            assertThat(fingerprint.complete()).isTrue();
            assertThat(fingerprint.messageId()).isEqualTo("<Move@example.com>");
            assertThat(fingerprint.subject()).isEqualTo("Тема");
            assertThat(fingerprint.from()).isEqualTo("Sender@example.com");
            assertThat(fingerprint.size()).isEqualTo(987654L);
            assertThat(fingerprint.sentAt()).isEqualTo(java.time.Instant.parse("2026-10-25T12:00:00Z"));
            assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThat(server.commands.stream().filter(s -> s.contains("ENVELOPE"))).containsExactly(
                    "FETCH 1:100 (ENVELOPE INTERNALDATE RFC822.SIZE FLAGS UID)",
                    "FETCH 101:200 (ENVELOPE INTERNALDATE RFC822.SIZE FLAGS UID)",
                    "FETCH 201:205 (ENVELOPE INTERNALDATE RFC822.SIZE FLAGS UID)");
            assertThat(server.commands).noneMatch(s -> s.matches("(?i).*(BODY|RFC822[^.]|SELECT |STORE |EXPUNGE|APPEND ).*"));
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
        }
    }

    @Test void metadataRejectsUidValidityChange() throws Exception {
        try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
            var snapshot = client.folderUids("INBOX");
            server.nextUidValidity = 8L;
            assertThatThrownBy(() -> client.metadata("INBOX", snapshot.uidValidity(), snapshot.uids()))
                    .isInstanceOfSatisfying(ImapException.class, e -> assertThat(e.kind()).isEqualTo(ImapException.Kind.PROTOCOL));
            assertThat(server.commands).noneMatch(s -> s.contains("ENVELOPE") || s.contains("BODY"));
            assertThat(server.commands.stream().filter(s -> s.startsWith("EXAMINE "))).hasSize(2);
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
        }
    }

    @Test void metadataMissingUidAndMultipleSendersDoNotProvideCompleteIdentity() throws Exception {
        String two = FakeImapServer.ENVELOPE.replace("((NIL NIL \"Sender\" \"example.com\"))",
                "((NIL NIL \"Sender\" \"example.com\") (NIL NIL \"Other\" \"example.com\"))");
        try (var server = new FakeImapServer(new FakeImapServer.MailFolder("INBOX", new FakeImapServer.Item(42, MIME, 123, "", two)));
             var client = new ImapClient(settings(server.port()), false)) {
            var result = client.metadata("INBOX", 7, List.of(42L, 99L));
            assertThat(result).containsOnlyKeys("42");
            assertThat(result.get("42").fingerprint().from()).isNull();
            assertThat(result.get("42").fingerprint().complete()).isFalse();
            assertThat(server.commands).noneMatch(s -> s.contains("BODY"));
        }
    }

    @Test void metadataSignInRejectionIsNotRetried() throws Exception {
        try (var server = new FakeImapServer(true, false, "login"); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.metadata("INBOX", 7, List.of(42L))).isInstanceOf(ImapException.class)
                    .hasMessageContaining("sign-in rejected");
            assertThatThrownBy(() -> client.metadata("INBOX", 7, List.of(42L))).isInstanceOf(ImapException.class)
                    .hasMessageContaining("closed");
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN "))).hasSize(1);
        }
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

    @Test void multiBlockDownloadPreservesOriginalAndUsesFewReadOnlyFetches() throws Exception {
        byte[] large = new byte[1024 * 1024 + 17];
        new java.util.Random(42).nextBytes(large);
        var folder = new FakeImapServer.MailFolder("INBOX", new FakeImapServer.Item(42, large));
        try (var server = new FakeImapServer(folder); var client = new ImapClient(settings(server.port()), false)) {
            Path target = temp.resolve("multi-block.eml");
            var result = client.download("INBOX", 42, target, ImapClient.DEFAULT_MAX_MIME_BYTES);
            assertThat(Files.readAllBytes(target)).isEqualTo(large);
            assertThat(result.size()).isEqualTo(large.length);
            assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(large)));
            assertThat(server.commands.stream().filter(s -> s.contains("BODY.PEEK[]"))).containsExactly(
                    "FETCH 1 (BODY.PEEK[]<0.262144>)",
                    "FETCH 1 (BODY.PEEK[]<262144.262144>)",
                    "FETCH 1 (BODY.PEEK[]<524288.262144>)",
                    "FETCH 1 (BODY.PEEK[]<786432.262144>)",
                    "FETCH 1 (BODY.PEEK[]<1048576.262144>)");
            assertThat(server.commands.stream().filter(s -> s.startsWith("FETCH ") || s.startsWith("UID FETCH ")))
                    .hasSizeLessThanOrEqualTo(7);
        }
    }

    @Test void streamOverLimitIsTooLargeRemovesPartialAndKeepsSession() throws Exception {
        byte[] large = "x".repeat(600000).getBytes(StandardCharsets.US_ASCII);
        var folder = new FakeImapServer.MailFolder("INBOX",
                new FakeImapServer.Item(42, large, 1, ""), new FakeImapServer.Item(43, MIME));
        try (var server = new FakeImapServer(folder); var client = new ImapClient(settings(server.port()), false)) {
            assertThatThrownBy(() -> client.download("INBOX", 42, temp.resolve("large.eml"), 270000))
                    .isInstanceOfSatisfying(MessageUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(MessageUnavailableException.Reason.TOO_LARGE));
            try (var files = Files.list(temp)) { assertThat(files.toList()).isEmpty(); }
            assertThat(server.commands).anyMatch(s -> s.contains("BODY.PEEK[]<262144.262144>"));
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
                new EwsSettings(URI.create("https://127.0.0.1:1/EWS/Exchange.asmx"), AuthKind.NTLM,
                        "user", "synthetic-test-value", null, EwsSettings.DEFAULT_CONNECT_TIMEOUT,
                        EwsSettings.DEFAULT_READ_TIMEOUT), null, temp, null);
        try (var live = MailConnectionFactory.standard().create(ews)) {
            try (var source = live.openSource(); var other = live.openSource()) {
                assertThat(source).isInstanceOf(EwsMailSource.class); // port 1 is never contacted
                assertThat(other).isNotSameAs(source);
            }
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

    @Test void metadataRefreshesSeenAndFlaggedWithoutWrites() throws Exception {
        try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
            var uids = client.folderUids("INBOX");
            server.metadataFlags = "\\Seen \\Flagged";
            var first = client.metadata("INBOX", uids.uidValidity(), uids.uids()).get("42");
            assertThat(first.read()).isTrue();
            assertThat(first.flagged()).isTrue();
            server.metadataFlags = "";
            var cleared = client.metadata("INBOX", 7, List.of(42L)).get("42");
            assertThat(cleared.read()).isFalse();
            assertThat(cleared.flagged()).isFalse();
            server.omitMetadataFlags = true;
            var absent = client.metadata("INBOX", 7, List.of(42L)).get("42");
            assertThat(absent.read()).isNull();
            assertThat(absent.flagged()).isNull();
            assertThat(server.commands.stream().filter(c -> c.contains("ENVELOPE"))).hasSize(3);
            assertThat(server.commands.stream().filter(c -> c.startsWith("LOGIN "))).hasSize(1);
            assertThat(server.commands).noneMatch(c -> c.contains("BODY") || c.startsWith("SELECT") || c.contains("STORE"));
        }
    }

    @Test void unparsableMetadataResponseLeavesOnlyThatMessageUnknown() throws Exception {
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ImapClient.class);
        logs.start();
        logger.addAppender(logs);
        try (var server = new FakeImapServer(new FakeImapServer.MailFolder("INBOX",
                new FakeImapServer.Item(1, MIME), new FakeImapServer.Item(2, MIME), new FakeImapServer.Item(3, MIME)));
             var client = new ImapClient(settings(server.port()), false)) {
            var uids = client.folderUids("INBOX");
            server.malformedMetadataUid = 2L;
            var result = client.metadata("INBOX", uids.uidValidity(), uids.uids());

            assertThat(result).containsOnlyKeys("1", "3");
            assertThat(result.get("1").fingerprint()).isNotNull();
            assertThat(server.commands.stream().filter(c -> c.contains("ENVELOPE")))
                    .as("no replacement fetch for the skipped message").hasSize(1);
            assertThat(logs.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .containsExactly("IMAP metadata batch 1/1: 1 of 3 messages have no parsed metadata response "
                            + "(1 responses unparsable); their metadata stays unknown");

            server.malformedMetadataUid = null;
            server.malformedMetadataFlags = true;
            assertThat(client.metadata("INBOX", uids.uidValidity(), uids.uids())).isEmpty();
            assertThat(server.commands.stream().filter(c -> c.startsWith("LOGIN "))).hasSize(1);
        } finally {
            logger.detachAppender(logs);
        }
    }


    @Test void failuresLogOperationAndCauseClassesWithoutServerText() throws Exception {
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ImapClient.class);
        logs.start();
        logger.addAppender(logs);
        try {
            try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
                server.rejectMetadata = true;
                assertThatThrownBy(() -> client.metadata("INBOX", 7, List.of(42L))).isInstanceOf(ImapException.class);
            }
            try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
                assertThatThrownBy(() -> client.metadata("INBOX", 8, List.of(42L))).isInstanceOf(ImapException.class);
            }
            var lines = logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).startsWith("IMAP metadata batch 1/1 failed (PROTOCOL): MessagingException")
                    .contains("\"Metadata fetch failed: CommandFailedException\"");
            assertThat(lines.get(1)).isEqualTo("IMAP metadata (1 UIDs) failed (PROTOCOL): UIDVALIDITY changed");
            assertThat(lines).noneMatch(line -> line.contains("private-server-text") || line.contains("INBOX"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test void allowListRefusalNamesOnlyTheCommandShape() {
        assertThatThrownBy(() -> ReadOnlyImapStore.requireReadCommand("UID STORE 17,19:25 +FLAGS (\\Seen)"))
                .isInstanceOf(ProtocolException.class)
                .hasMessage("IMAP command is outside the read-only allow-list: UID STORE n +FLAGS (\\SEEN)");
        var wrapped = new jakarta.mail.MessagingException("A7 NO private-server-text",
                new ProtocolException("private-server-text"));
        assertThat(ImapClient.causes(wrapped)).isEqualTo("MessagingException <- ProtocolException");
    }

    @Test void metadataFetchRefusalIsSanitizedAndNeverRetried() throws Exception {
        try (var server = new FakeImapServer(); var client = new ImapClient(settings(server.port()), false)) {
            server.rejectMetadata = true;
            assertThatThrownBy(() -> client.metadata("INBOX", 7, List.of(42L)))
                    .isInstanceOf(ImapException.class).hasNoCause().hasMessage("IMAP operation failed; no automatic retry");
            assertThat(server.commands.stream().filter(c -> c.contains("ENVELOPE"))).hasSize(1);
            assertThat(server.commands.stream().filter(c -> c.startsWith("LOGIN "))).hasSize(1);
            assertThatThrownBy(client::listFolders).isInstanceOf(ImapException.class).hasMessageContaining("closed");
        }
    }

}
