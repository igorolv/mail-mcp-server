package mail.mcp.connections;

/**
 * A sign-in secret of one connection: a literal password from {@code connections.json}, a generic
 * Windows credential ({@code windowsCredential}, Д28) or the output of its {@code passwordCommand}
 * (Д22); docs/plan.md 3.6.
 *
 * <p>Sources ask for the value only right before signing in, so a command never runs at startup or
 * for a connection that is not synchronized. Implementations never print the value in
 * {@code toString}.
 */
public interface Secret {

    /**
     * The secret. A Windows credential or password command is read on the first call and kept in memory.
     *
     * @throws SecretUnavailableException when the password cannot be obtained; nothing was sent to a server
     */
    String value();

    /** Drops a value kept in memory, so a rejected password is never offered again. */
    default void forget() { }

    /** @return a literal secret, or {@code null} for {@code null} */
    static Secret of(String value) {
        return value == null ? null : new Literal(value);
    }

    /** A password written in the connections file or taken from a {@code ${VAR}}. */
    record Literal(String value) implements Secret {
        @Override
        public String toString() {
            return "***";
        }
    }
}
