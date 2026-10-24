package mail.mcp.connections;

import java.util.Arrays;
import java.util.Optional;

/** Which mail source serves a connection: the {@code type} field of {@code connections.json}. */
public enum ConnectionType {

    /** Exchange Web Services (the corporate Exchange 2013 server). */
    EWS("ews"),
    /** IMAP over TLS (Gmail, Mail.ru). */
    IMAP("imap");

    private final String id;

    ConnectionType(String id) {
        this.id = id;
    }

    /** Value used in {@code connections.json} and in tool responses. */
    public String id() {
        return id;
    }

    /**
     * Sign-in method used when the entry has no {@code auth}. A method rather than a constructor
     * argument: {@link AuthKind} constants refer back to this enum, and a field would see a
     * half-initialized class.
     */
    public AuthKind defaultAuth() {
        return switch (this) {
            case EWS -> AuthKind.NTLM;
            case IMAP -> AuthKind.APP_PASSWORD;
        };
    }

    public static Optional<ConnectionType> fromId(String id) {
        return Arrays.stream(values()).filter(type -> type.id.equalsIgnoreCase(id.trim())).findFirst();
    }

    public static String knownIds() {
        return String.join(", ", Arrays.stream(values()).map(ConnectionType::id).toList());
    }
}
