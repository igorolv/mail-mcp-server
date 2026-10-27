package mail.mcp.connections;

/**
 * A password command or a Windows credential did not yield a password. No credentials reached the
 * mail server, so this is never a rejected sign-in. Messages are fixed: they never hold the
 * password or a program's output.
 */
public final class SecretUnavailableException extends RuntimeException {

    /**
     * @param connection connection name
     * @param reason     what failed, starting with its source, e.g. "password command exited with code 1"
     */
    public SecretUnavailableException(String connection, String reason) {
        super("Password of connection '" + connection + "' is unavailable: " + reason
                + "; no sign-in was attempted");
    }
}
