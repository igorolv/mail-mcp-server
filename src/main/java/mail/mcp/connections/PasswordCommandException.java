package mail.mcp.connections;

/**
 * A password command did not yield a password. No credentials reached the mail server, so this is
 * never a rejected sign-in. Messages are fixed: they never hold the command's output.
 */
public final class PasswordCommandException extends RuntimeException {

    public PasswordCommandException(String connection, String reason) {
        super("Password command of connection '" + connection + "' " + reason
                + "; no sign-in was attempted");
    }
}
