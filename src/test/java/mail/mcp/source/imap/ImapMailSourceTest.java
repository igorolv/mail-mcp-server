package mail.mcp.source.imap;

import mail.mcp.connections.AuthKind;
import mail.mcp.source.RemoteFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static mail.mcp.source.imap.FakeImapServer.MIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImapMailSourceTest {
    @TempDir Path temp;
    private static final RemoteFolder INBOX = new RemoteFolder("INBOX", "INBOX");

    private static ImapMailSource source(FakeImapServer server) {
        return new ImapMailSource(new ImapClient(new ImapSettings("127.0.0.1", server.port(), true,
                AuthKind.APP_PASSWORD, "user", "synthetic-test-value"), false));
    }

    private static FakeImapServer.MailFolder inbox() {
        return new FakeImapServer.MailFolder("INBOX", new FakeImapServer.Item(1, MIME),
                new FakeImapServer.Item(5, MIME), new FakeImapServer.Item(10, MIME));
    }

    @Test void foldersSkipNoselectNonexistentTrashJunkDraftsAndContainers() throws Exception {
        try (var server = new FakeImapServer(inbox(),
                new FakeImapServer.MailFolder("NoSelect", 7, List.of("\\nOsElEcT"), List.of()),
                new FakeImapServer.MailFolder("Gone", 7, List.of("\\NonExistent"), List.of()),
                new FakeImapServer.MailFolder("Trash", 7, List.of("\\tRaSh"), List.of()),
                new FakeImapServer.MailFolder("Junk", 7, List.of("\\Junk"), List.of()),
                new FakeImapServer.MailFolder("Drafts", 7, List.of("\\Drafts"), List.of()),
                new FakeImapServer.MailFolder("Container", 7, List.of("\\Noselect", "\\HasChildren"), List.of()),
                new FakeImapServer.MailFolder("Sent", 7, List.of("\\Sent"), List.of()),
                new FakeImapServer.MailFolder("Projects")); var source = source(server)) {
            assertThat(source.folders()).containsExactly(INBOX,
                    new RemoteFolder("Sent", "Sent"), new RemoteFolder("Projects", "Projects"));
        }
    }

    @Test void firstChangesReturnAllUidsAndUidValidityCursor() throws Exception {
        try (var server = new FakeImapServer(inbox()); var source = source(server)) {
            var changes = source.changes(INBOX, null, Set.of());
            assertThat(changes.added()).containsExactly("1", "5", "10");
            assertThat(changes.removed()).isEmpty();
            assertThat(changes.cursor()).isEqualTo("7");
            assertThat(changes.reset()).isFalse();
            assertThatThrownBy(() -> changes.added().add("99")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void changesSkipKnownRefs() throws Exception {
        try (var server = new FakeImapServer(inbox()); var source = source(server)) {
            var first = source.changes(INBOX, null, Set.of());
            var changes = source.changes(INBOX, first.cursor(), Set.of("1", "10"));
            assertThat(changes.added()).containsExactly("5");
            assertThat(changes.removed()).isEmpty();
            assertThat(changes.reset()).isFalse();
        }
    }

    @Test void removedIsKnownMinusPresent() throws Exception {
        try (var server = new FakeImapServer(inbox()); var source = source(server)) {
            var changes = source.changes(INBOX, "7", Set.of("1", "2", "20", "11", "0", "bad", "gone"));
            assertThat(changes.added()).containsExactly("5", "10");
            assertThat(changes.removed()).containsExactly("0", "2", "11", "20", "bad", "gone");
            assertThat(changes.reset()).isFalse();
        }
    }

    @Test void changedUidValidityResetsFolder() throws Exception {
        try (var server = new FakeImapServer(new FakeImapServer.MailFolder("INBOX",
                new FakeImapServer.Item(1, MIME), new FakeImapServer.Item(5, MIME))); var source = source(server)) {
            var changes = source.changes(INBOX, "6", Set.of("1", "2"));
            assertThat(changes.reset()).isTrue();
            assertThat(changes.removed()).containsExactly("1", "2");
            assertThat(changes.added()).containsExactly("1", "5");
            assertThat(changes.cursor()).isEqualTo("7");
        }
    }

    @Test void invalidCursorOrRefIsRejectedBeforeAnyCommand() throws Exception {
        try (var server = new FakeImapServer(); var source = source(server)) {
            for (String cursor : List.of("", "-1", "+7", "7.0", " 7", "abc", "7\n")) {
                assertThatThrownBy(() -> source.changes(INBOX, cursor, Set.of())).isInstanceOf(IllegalArgumentException.class);
            }
            for (String ref : List.of("", "0", "000", "-1", "+1", "1.0", " 1", "abc", "1\n", "99999999999999999999")) {
                assertThatThrownBy(() -> source.download(INBOX, ref, temp.resolve("invalid.eml")))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(server.commands).isEmpty();
        }
    }

    @Test void oneSignInForFoldersChangesAndDownloadsAcrossFolders() throws Exception {
        var a = new RemoteFolder("A", "A");
        var b = new RemoteFolder("B", "B");
        try (var server = new FakeImapServer(new FakeImapServer.MailFolder("A",
                new FakeImapServer.Item(1, MIME), new FakeImapServer.Item(2, MIME)),
                new FakeImapServer.MailFolder("B", new FakeImapServer.Item(3, MIME))); var source = source(server)) {
            assertThat(source.folders()).containsExactly(a, b);
            assertThat(source.changes(a, null, Set.of()).added()).containsExactly("1", "2");
            assertThat(source.changes(b, null, Set.of()).added()).containsExactly("3");
            source.download(a, "1", temp.resolve("a1.eml"));
            source.download(b, "3", temp.resolve("b3.eml"));
            source.download(a, "2", temp.resolve("a2.eml"));
            assertThat(server.commands.stream().filter(s -> s.startsWith("LOGIN ") || s.startsWith("AUTHENTICATE "))).hasSize(1);
            for (String command : server.commands) {
                String verb = command.split(" ")[0];
                if (List.of("LOGIN", "LIST", "EXAMINE").contains(verb)) {
                    ReadOnlyImapStore.requireReadCommand(verb);
                } else { ReadOnlyImapStore.requireReadCommand(command); }
            }
            assertThat(server.commands).noneMatch(s -> s.matches("(?i).*(SELECT |STORE |EXPUNGE|APPEND ).*"));
        }
    }

    @Test void downloadWritesExactBytesAndSha256() throws Exception {
        try (var server = new FakeImapServer(inbox()); var source = source(server)) {
            var target = temp.resolve("original.eml");
            var result = source.download(INBOX, "5", target);
            assertThat(Files.readAllBytes(target)).isEqualTo(MIME);
            assertThat(result.size()).isEqualTo(MIME.length);
            assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MIME)));
            assertThat(source.changes(INBOX, "7", Set.of("5")).added()).containsExactly("1", "10");
        }
    }
}
