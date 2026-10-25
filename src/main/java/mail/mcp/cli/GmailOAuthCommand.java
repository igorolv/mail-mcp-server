package mail.mcp.cli;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.source.oauth.google.GoogleOAuthFlow;
import mail.mcp.source.oauth.google.GoogleTokenHttpClient;
import mail.mcp.source.oauth.OAuthException;

import java.awt.Desktop;
import java.io.PrintStream;
import java.net.URI;

/** User-run only: authorize Google Desktop OAuth, then save a refresh token outside the repository. */
public final class GmailOAuthCommand {
    public static final String MODE = "gmail-oauth";
    private GmailOAuthCommand() { }

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-cli.xml");
        }
        PrintStream out = System.out;
        if (args.length != 1 || args[0].equals("--help") || args[0].equals("-h")) {
            out.println("Usage: java -jar mail-mcp-server.jar gmail-oauth <connection>");
            out.println("User-run only. Requires a Google Desktop client JSON and auth: oauth2.");
            return args.length == 1 ? 0 : 2;
        }
        try {
            var properties = MailMcpProperties.fromEnvironment(System::getenv);
            var definitions = ConnectionsLoader.load(properties, new JsonConfig().mailMcpObjectMapper(), System::getenv);
            var selected = definitions.stream().filter(d -> d.name().equals(args[0])).findFirst().orElse(null);
            if (selected == null || !selected.usable() || selected.auth() != AuthKind.OAUTH2) {
                out.println("RESULT: FAILED (choose a valid Gmail connection with auth: oauth2 and oauthClientFile)");
                return 2;
            }
            out.println("Opening Google authorization in your system browser.");
            out.println("Choose the same Google account as the connection username. Waiting up to 5 minutes.");
            out.println("IMAP requires full mail scope; this server enforces read-only commands.");
            new GoogleOAuthFlow(new GoogleTokenHttpClient()).authorize(selected.imap().oauth2(),
                    selected.imap().username(), GmailOAuthCommand::openBrowser);
            out.println("RESULT: OK (refresh token saved with owner-only access; no secrets printed)");
            out.println("Next: java -jar build\\libs\\mail-mcp-server.jar mail-sync " + selected.name());
            return 0;
        } catch (OAuthException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        } catch (RuntimeException e) {
            out.println("RESULT: FAILED (check local OAuth configuration; details withheld)");
            return 2;
        }
    }

    private static void openBrowser(URI uri) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new OAuthException(OAuthException.Kind.BROWSER);
            }
            Desktop.getDesktop().browse(uri);
        } catch (Exception e) { throw new OAuthException(OAuthException.Kind.BROWSER); }
    }
}
