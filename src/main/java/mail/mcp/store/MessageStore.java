package mail.mcp.store;

import mail.mcp.model.LocalFolder;
import mail.mcp.model.SyncStatus;
import mail.mcp.model.MessageMeta;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Message directories and atomically replaced metadata for one connection; no mailbox access. */
public final class MessageStore {
    public static final String SYNC_STATUS_FILE = "sync-status.json";
    public static final String MESSAGE_FILE = "message.eml";
    public static final String META_FILE = "meta.json";
    public static final String BODY_FILE = "body.md";
    public static final String ATTACHMENTS_DIR = "attachments";

    private static final Pattern KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final Path connectionDir;
    private final ObjectMapper mapper;

    public MessageStore(Path connectionDir, ObjectMapper mapper) {
        this.connectionDir = connectionDir;
        this.mapper = mapper;
    }

    public List<LocalFolder> folders() {
        try {
            return mapper.readValue(Files.readAllBytes(connectionDir.resolve("folders.json")),
                    FoldersFile.class).folders();
        } catch (NoSuchFileException e) { return List.of(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void saveFolders(List<LocalFolder> folders) {
        try { Files.createDirectories(connectionDir); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        writeJson(connectionDir.resolve("folders.json"), new FoldersFile(folders));
    }

    public Optional<SyncStatus> readSyncStatus() {
        try {
            return Optional.of(mapper.readValue(Files.readAllBytes(connectionDir.resolve(SYNC_STATUS_FILE)),
                    SyncStatus.class));
        } catch (NoSuchFileException e) { return Optional.empty(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void writeSyncStatus(SyncStatus status) {
        writeJson(connectionDir.resolve(SYNC_STATUS_FILE), status);
    }

    public String newKey() { return UUID.randomUUID().toString(); }

    public Path messageDir(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Message key must be a lowercase canonical UUID");
        }
        return connectionDir.resolve("messages").resolve(key.substring(0, 2)).resolve(key);
    }

    public Path createMessageDir(String key) {
        Path directory = messageDir(key);
        try {
            Files.createDirectories(directory.getParent());
            return Files.createDirectory(directory);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public void writeMeta(MessageMeta meta) {
        writeJson(messageDir(meta.key()).resolve(META_FILE), meta);
    }

    public Optional<MessageMeta> readMeta(String key) {
        Path file = messageDir(key).resolve(META_FILE);
        try { return Optional.of(mapper.readValue(Files.readAllBytes(file), MessageMeta.class)); }
        catch (NoSuchFileException e) { return Optional.empty(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public boolean hasMeta(String key) { return Files.exists(messageDir(key).resolve(META_FILE)); }

    public List<String> keys() {
        Path messages = connectionDir.resolve("messages");
        List<String> keys = new ArrayList<>();
        try (var prefixes = Files.list(messages)) {
            for (Path prefix : prefixes.filter(Files::isDirectory).toList()) {
                try (var directories = Files.list(prefix)) {
                    directories.filter(Files::isDirectory).forEach(directory -> {
                        String key = directory.getFileName().toString();
                        if (KEY.matcher(key).matches()
                                && prefix.getFileName().toString().equals(key.substring(0, 2))) {
                            keys.add(key);
                        }
                    });
                }
            }
        } catch (NoSuchFileException e) { return List.of(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        return keys.stream().sorted().toList();
    }

    public void delete(String key) {
        Path directory = messageDir(key);
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path dir, IOException error)
                        throws IOException {
                    if (error != null) { throw error; }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException e) { /* already absent */ }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private void writeJson(Path file, Object value) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.getParent(), ".mail-meta-", ".tmp");
            Files.write(temporary, mapper.writeValueAsBytes(value));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* preserve the write failure */ }
            }
        }
    }

    private record FoldersFile(List<LocalFolder> folders) { }
}
