package mail.mcp.store;

import mail.mcp.config.JsonConfig;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageStoreTest {
    private static final ObjectMapper MAPPER = new JsonConfig().mailMcpObjectMapper();
    private static final Instant DOWNLOADED_AT = Instant.parse("2026-10-25T12:00:00Z");
    @TempDir Path temp;

    private MessageStore store() { return new MessageStore(temp.resolve("box"), MAPPER); }

    private static MessageMeta meta(String key, MessageState state) {
        return new MessageMeta(key, "f1", "INBOX", "101", 0, null, DOWNLOADED_AT, state, null);
    }

    @Test void foldersRoundTripAndMissingFileIsEmpty() {
        var store = store();
        assertThat(store.folders()).isEmpty();
        assertThat(temp.resolve("box")).doesNotExist();
        var folders = List.of(new LocalFolder("f1", "INBOX", "INBOX", null),
                new LocalFolder("f2", "AAMk/x+y=", "Входящие/Проекты", "opaque/cursor"));
        store.saveFolders(folders);
        assertThat(store.folders()).isEqualTo(folders);
        store.saveFolders(List.of());
        assertThat(store.folders()).isEmpty();
    }

    @Test void foldersFileHasThePlannedShape() throws IOException {
        store().saveFolders(List.of(new LocalFolder("f1", "INBOX", "INBOX", "7")));
        var json = MAPPER.readTree(Files.readAllBytes(temp.resolve("box/folders.json")));
        assertThat(json.size()).isEqualTo(1);
        var folders = json.get("folders");
        assertThat(folders.isArray()).isTrue();
        assertThat(folders.size()).isEqualTo(1);
        var folder = folders.get(0);
        assertThat(folder.size()).isEqualTo(4);
        assertThat(folder.get("key").asText()).isEqualTo("f1");
        assertThat(folder.get("remoteId").asText()).isEqualTo("INBOX");
        assertThat(folder.get("path").asText()).isEqualTo("INBOX");
        assertThat(folder.get("cursor").asText()).isEqualTo("7");
    }

    @Test void metaRoundTripKeepsIsoInstantAndLowercaseState() throws IOException {
        var store = store();
        String key = store.newKey();
        assertThat(store.readMeta(key)).isEmpty();
        assertThat(store.hasMeta(key)).isFalse();
        Path directory = store.createMessageDir(key);
        assertThat(store.readMeta(key)).isEmpty();
        for (MessageState state : MessageState.values()) {
            MessageMeta meta = meta(key, state);
            store.writeMeta(meta);
            String json = Files.readString(directory.resolve(MessageStore.META_FILE));
            assertThat(json).contains("\"downloadedAt\":\"2026-10-25T12:00:00Z\"",
                    "\"state\":\"" + state.id() + "\"").doesNotContain("\"error\"", "\"sha256\"");
            assertThat(state.id()).isEqualTo(state.name().toLowerCase(Locale.ROOT));
            assertThat(store.readMeta(key)).contains(meta);
            assertThat(store.readMeta(key).orElseThrow().error()).isNull();
            assertThat(store.readMeta(key).orElseThrow().sha256()).isNull();
        }
        assertThat(store.hasMeta(key)).isTrue();
    }

    @Test void messageDirUsesTheFirstTwoKeyCharacters() {
        var store = store();
        String key = store.newKey();
        assertThat(UUID.fromString(key).toString()).isEqualTo(key);
        assertThat(store.messageDir(key)).isEqualTo(temp.resolve("box/messages")
                .resolve(key.substring(0, 2)).resolve(key));
        assertThat(temp.resolve("box")).doesNotExist();
    }

    @Test void invalidKeysAreRejectedWithoutIo() {
        var store = store();
        for (String key : List.of("..", "ab", "ABCDEF01-2345-6789-ABCD-EF0123456789",
                "abcdef01-2345-6789-abcd-ef0123456789/", "1-1-1-1-1")) {
            assertThatThrownBy(() -> store.messageDir(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.createMessageDir(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.readMeta(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.hasMeta(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.writeMeta(meta(key, MessageState.DOWNLOADED)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.delete(key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(temp.resolve("box")).doesNotExist();
    }

    @Test void createMessageDirRefusesAnExistingDirectory() {
        var store = store();
        String key = store.newKey();
        assertThat(store.createMessageDir(key)).isEqualTo(store.messageDir(key)).isDirectory();
        assertThatThrownBy(() -> store.createMessageDir(key)).isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(FileAlreadyExistsException.class);
    }

    @Test void writesLeaveNoTemporaryFiles() throws IOException {
        var store = store();
        String key = store.newKey();
        Path directory = store.createMessageDir(key);
        store.writeMeta(meta(key, MessageState.DOWNLOADED));
        store.writeMeta(meta(key, MessageState.FAILED));
        store.saveFolders(List.of(new LocalFolder("f1", "INBOX", "INBOX", null)));
        store.saveFolders(List.of(new LocalFolder("f1", "INBOX", "INBOX", "7")));
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly(MessageStore.META_FILE);
        }
        try (var files = Files.list(temp.resolve("box"))) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("messages", "folders.json");
        }
    }

    @Test void keysListOnlyMessageDirectories() throws IOException {
        var store = store();
        assertThat(store.keys()).isEmpty();
        String a = "aa000000-0000-0000-0000-000000000001";
        String b = "bb000000-0000-0000-0000-000000000002";
        store.createMessageDir(b);
        store.createMessageDir(a);
        Path messages = temp.resolve("box/messages");
        Files.writeString(messages.resolve("stray"), "ignored");
        Files.writeString(messages.resolve("aa/aa000000-0000-0000-0000-000000000003"), "ignored");
        Files.createDirectory(messages.resolve("aa/not-a-uuid"));
        Files.createDirectory(messages.resolve("aa/AA000000-0000-0000-0000-000000000004"));
        Files.createDirectory(messages.resolve("aa/bb000000-0000-0000-0000-000000000005"));
        assertThat(store.keys()).containsExactly(a, b);
    }

    @Test void deleteRemovesTheDirectoryAndIgnoresMissingKeys() throws IOException {
        var store = store();
        String key = store.newKey();
        store.delete(key);
        assertThat(temp.resolve("box")).doesNotExist();
        Path directory = store.createMessageDir(key);
        store.writeMeta(meta(key, MessageState.DOWNLOADED));
        Path nested = Files.createDirectories(directory.resolve("attachments/nested"));
        Files.writeString(nested.resolve("part.bin"), "synthetic attachment");
        store.delete(key);
        store.delete(key);
        assertThat(directory).doesNotExist();
        assertThat(directory.getParent()).isDirectory();
        assertThat(store.keys()).isEmpty();
        assertThat(store.hasMeta(key)).isFalse();
    }
    @Test void oldMetadataHasNoFingerprint() throws IOException {
        var store = store();
        String key = store.newKey();
        Path directory = store.createMessageDir(key);
        Files.writeString(directory.resolve(MessageStore.META_FILE), """
                {"key":"%s","folderKey":"f1","folder":"INBOX","ref":"101","size":12,
                 "downloadedAt":"2026-10-25T12:00:00Z","state":"converted"}
                """.formatted(key));
        assertThat(store.readMeta(key).orElseThrow().fingerprint()).isNull();
        var fingerprint = new mail.mcp.model.MessageFingerprint(" <Case@example.com> ", 1234L,
                DOWNLOADED_AT, " User@example.com ", " Subject ");
        var old = store.readMeta(key).orElseThrow();
        var meta = new MessageMeta(key, old.folderKey(), old.folder(), old.ref(), old.size(), old.sha256(),
                old.downloadedAt(), old.state(), old.error(), fingerprint);
        store.writeMeta(meta);
        assertThat(store.readMeta(key)).contains(meta);
        assertThat(fingerprint.messageId()).isEqualTo("<Case@example.com>");
        assertThat(fingerprint.from()).isEqualTo("User@example.com");
        assertThat(fingerprint.subject()).isEqualTo(" Subject ");
    }

    @Test void oldSyncStatusDefaultsRescannedToFalse() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("box"));
        Files.writeString(folder.resolve(MessageStore.SYNC_STATUS_FILE), """
                {"state":"completed","pid":1,"startedAt":"2026-10-25T12:00:00Z",
                 "folders":[{"path":"INBOX","downloaded":1,"failed":0,"removed":0,"reset":false,"done":true}],
                 "signInRejected":false}
                """);
        assertThat(store().readSyncStatus().orElseThrow().folders().getFirst().rescanned()).isFalse();
    }

}
