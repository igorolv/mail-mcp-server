package mail.mcp.source.oauth.google;

import mail.mcp.source.oauth.OAuthException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Owner-only refresh token file; access tokens never touch disk. Writes replace atomically. */
public final class GoogleTokenStore {
    private final Path file;

    public GoogleTokenStore(Path file) { this.file = file.toAbsolutePath(); }

    public <T> T locked(Supplier<T> operation) {
        try {
            Files.createDirectories(file.getParent());
            try (var channel = FileChannel.open(file.resolveSibling("google-tokens.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException e) { throw new OAuthException(OAuthException.Kind.BUSY); }
                if (lock == null) { throw new OAuthException(OAuthException.Kind.BUSY); }
                try (lock) { return operation.get(); }
            }
        } catch (IOException e) { throw new OAuthException(OAuthException.Kind.LOCAL_IO); }
    }

    GoogleRefreshCredential read(String clientId, String username) {
        if (!Files.isRegularFile(file)) { throw new OAuthException(OAuthException.Kind.AUTHORIZE); }
        var json = OAuthJson.read(file);
        var credential = new GoogleRefreshCredential(OAuthJson.string(json, "provider"),
                OAuthJson.string(json, "clientId"), OAuthJson.string(json, "username"),
                OAuthJson.string(json, "scope"), OAuthJson.string(json, "refreshToken"));
        if (!"google".equals(credential.provider()) || !clientId.equals(credential.clientId())
                || !username.equalsIgnoreCase(credential.username()) || !GoogleOAuthFlow.hasMailScope(credential.scope())
                || credential.refreshToken() == null || credential.refreshToken().isBlank()) {
            throw new OAuthException(OAuthException.Kind.AUTHORIZE);
        }
        return credential;
    }

    void write(GoogleRefreshCredential credential) {
        Path temporary = file.resolveSibling(".google-token-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.createFile(temporary, ownerOnly(file.getParent()));
            byte[] json = OAuthJson.MAPPER.writeValueAsBytes(credential);
            Files.write(temporary, json, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            if (e instanceof OAuthException safe) { throw safe; }
            throw new OAuthException(OAuthException.Kind.LOCAL_IO);
        } finally {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* owner-only partial */ }
        }
    }

    private static FileAttribute<?> ownerOnly(Path directory) throws IOException {
        var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (acl != null) {
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(directory))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            return new FileAttribute<List<AclEntry>>() {
                public String name() { return "acl:acl"; }
                public List<AclEntry> value() { return List.of(entry); }
            };
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        }
        throw new OAuthException(OAuthException.Kind.LOCAL_IO);
    }
}
