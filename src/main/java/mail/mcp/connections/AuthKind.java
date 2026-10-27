package mail.mcp.connections;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** How a connection signs in: the {@code auth} field of {@code connections.json}. */
public enum AuthKind {

    /** NTLM over HTTPS; the working method for the Exchange server (docs/results.md, 1). */
    NTLM("ntlm", ConnectionType.EWS, true),
    /** HTTP Basic over HTTPS. Rejected by the Exchange server for a bare login (docs/results.md, 1). */
    BASIC("basic", ConnectionType.EWS, true),
    /** IMAP LOGIN with an app password (decision P3). */
    APP_PASSWORD("app-password", ConnectionType.IMAP, true),
    /** Gmail IMAP XOAUTH2 with a local refresh token (decision P3). */
    OAUTH2("oauth2", ConnectionType.IMAP, true);

    private final String id;
    private final ConnectionType type;
    private final boolean supported;

    AuthKind(String id, ConnectionType type, boolean supported) {
        this.id = id;
        this.type = type;
        this.supported = supported;
    }

    /** Value used in {@code connections.json} and in tool responses. */
    public String id() {
        return id;
    }

    /** The connection type this method belongs to. */
    public ConnectionType type() {
        return type;
    }

    /** False for methods the model already names but the server cannot use yet. */
    public boolean supported() {
        return supported;
    }

    public static Optional<AuthKind> fromId(String id) {
        return Arrays.stream(values()).filter(kind -> kind.id.equalsIgnoreCase(id.trim())).findFirst();
    }

    /** Methods valid for one connection type, as a comma-separated list for error messages. */
    public static String knownIds(ConnectionType type) {
        List<String> ids = Arrays.stream(values()).filter(kind -> kind.type == type).map(AuthKind::id).toList();
        return String.join(", ", ids);
    }
}
