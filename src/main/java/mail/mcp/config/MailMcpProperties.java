package mail.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.UnaryOperator;

/**
 * Top-level server settings: where local data lives and where the connections file is.
 *
 * <p>Modeled on jdbc-mcp-server {@code config/JdbcMcpProperties} @ {@code c2c4362}.
 *
 * @param dataDir         root of all local data; {@code ~/.mail-mcp-server} when blank. Each
 *                        connection gets {@code <data-dir>/<connection>/}, logs go to
 *                        {@code <data-dir>/logs/}
 * @param connectionsFile JSON file with the named mailboxes; {@code <data-dir>/connections.json}
 *                        when blank
 */
@ConfigurationProperties(prefix = "mail-mcp")
public record MailMcpProperties(String dataDir, String connectionsFile) {

    /** Environment variable that overrides {@link #dataDir()}. */
    public static final String DATA_DIR_ENV = "MAIL_MCP_DATA_DIR";

    /** Environment variable that overrides {@link #connectionsFile()}. */
    public static final String CONNECTIONS_FILE_ENV = "MAIL_MCP_CONNECTIONS_FILE";

    /**
     * Settings read straight from the environment, for user-run commands without Spring.
     * Mirrors the {@code mail-mcp.*} block of {@code application.yml}.
     */
    public static MailMcpProperties fromEnvironment(UnaryOperator<String> env) {
        return new MailMcpProperties(env.apply(DATA_DIR_ENV), env.apply(CONNECTIONS_FILE_ENV));
    }

    public Path resolvedDataDir() {
        String dir = dataDir;
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home", ".") + "/.mail-mcp-server";
        }
        return Paths.get(expandHome(dir.trim())).normalize();
    }

    /** Path of the connections file: the configured one, or {@code <data-dir>/connections.json}. */
    public Path resolvedConnectionsFile() {
        if (connectionsFile == null || connectionsFile.isBlank()) {
            return resolvedDataDir().resolve("connections.json");
        }
        return Paths.get(expandHome(connectionsFile.trim())).normalize();
    }

    /** Local data of one connection: {@code <data-dir>/<connection>}. */
    public Path connectionDir(String connection) {
        return resolvedDataDir().resolve(connection);
    }

    private static String expandHome(String path) {
        return path.startsWith("~") ? System.getProperty("user.home", ".") + path.substring(1) : path;
    }
}
