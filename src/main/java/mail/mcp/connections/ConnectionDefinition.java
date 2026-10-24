package mail.mcp.connections;

import mail.mcp.source.ews.EwsSettings;
import mail.mcp.source.imap.ImapSettings;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * One named mailbox this server can read: its type, sign-in settings and local data directory.
 *
 * <p>A definition is inert — building it neither contacts the mail server nor touches the local data
 * directory. A problem that makes the entry unusable (unknown type, missing URL, unsupported auth) is
 * recorded in {@link #configError()} instead of being thrown: one broken entry must not stop the
 * server from serving the other mailboxes.
 *
 * <p>Name rules copied from jdbc-mcp-server {@code connection/ConnectionDefinition} @ {@code c2c4362}.
 *
 * @param name          connection name, also the local data directory name
 * @param description   free-form text from {@code connections.json}, surfaced by {@code listConnections}
 * @param type          source type, {@code null} when missing or unknown
 * @param auth          effective sign-in method, {@code null} when it cannot be determined
 * @param ews           EWS settings for a usable {@code ews} entry, otherwise {@code null}
 * @param imap          IMAP settings for a usable {@code imap} entry, otherwise {@code null}
 * @param connectionDir {@code <data-dir>/<name>}
 * @param configError   why this connection cannot be used, {@code null} when the entry is fine
 */
public record ConnectionDefinition(
        String name,
        String description,
        ConnectionType type,
        AuthKind auth,
        EwsSettings ews,
        ImapSettings imap,
        Path connectionDir,
        String configError
) {

    /**
     * Connection names end up in filesystem paths and in resource URIs, so keep them boring. One
     * optional {@code @} splits the name into "what" and "where" — {@code work@backup} — and cannot
     * lead or trail, because an empty half names nothing.
     */
    public static final Pattern NAME_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?");

    /** Longest accepted connection name. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Validates a connection name, returning it unchanged. */
    public static String requireValidName(String name, String origin) {
        if (name == null || name.length() > MAX_NAME_LENGTH
                || !NAME_PATTERN.matcher(name).matches()
                || !isSingleDirectoryName(name)) {
            throw new IllegalStateException("Invalid connection name '" + name + "' (" + origin
                    + "). Names must match " + NAME_PATTERN.pattern()
                    + ", be at most " + MAX_NAME_LENGTH + " characters, and not be '.' or '..'"
                    + " — they are used as directory names and in MCP resource URIs.");
        }
        return name;
    }

    /**
     * True when the name denotes one plain directory entry. The pattern already excludes separators,
     * but {@code .} and {@code ..} slip through it and would resolve {@code <data-dir>/<name>/}
     * onto the data directory itself or its parent.
     */
    private static boolean isSingleDirectoryName(String name) {
        if (".".equals(name) || "..".equals(name)) {
            return false;
        }
        try {
            Path path = Path.of(name);
            return path.getNameCount() == 1 && name.equals(path.getFileName().toString());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** True when the entry itself is valid. */
    public boolean usable() {
        return configError == null;
    }

    /** True when this server version has a source for the type. Stage 1 implements EWS only. */
    public boolean sourceImplemented() {
        return type == ConnectionType.EWS;
    }

    /** True when {@code <data-dir>/<name>/} already exists. */
    public boolean hasLocalData() {
        return Files.isDirectory(connectionDir);
    }
}
