package mail.mcp.connections;

import mail.mcp.config.MailMcpProperties;
import mail.mcp.source.ews.EwsSettings;
import mail.mcp.source.imap.ImapSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Turns {@code connections.json} into the list of {@link ConnectionDefinition}s the server serves.
 *
 * <p>The file is read once, at startup; editing it needs a restart. String values may reference
 * environment variables as {@code ${VAR}} — see {@link EnvironmentPlaceholders}.
 *
 * <p>What stops startup: a file that is not valid JSON, an invalid connection name, an entry that is
 * not an object, a value of the wrong JSON type, and an unset {@code ${VAR}}. What only marks one
 * entry unusable ({@link ConnectionDefinition#configError()}): a missing or unknown {@code type} or
 * {@code auth}, a missing URL, host, login or password, and values out of range. Unknown keys are
 * logged as warnings. A missing file, or one that defines no connection, starts the server with an
 * empty list so {@code listConnections} can say that nothing is configured.
 *
 * <p>Nothing here contacts a mail server. Modeled on jdbc-mcp-server
 * {@code connection/ConnectionsLoader} @ {@code c2c4362}.
 */
public final class ConnectionsLoader {

    private static final Logger log = LoggerFactory.getLogger(ConnectionsLoader.class);

    private static final Set<String> FILE_KEYS = Set.of("connections");
    private static final List<String> ENTRY_KEYS = Arrays.stream(ConnectionsFile.Entry.class.getRecordComponents())
            .map(component -> component.getName())
            .toList();
    private static final Set<String> EWS_ONLY_KEYS = Set.of("url", "domain", "connectTimeoutSeconds", "readTimeoutSeconds");
    private static final Set<String> IMAP_ONLY_KEYS = Set.of("host", "port", "tls");

    private ConnectionsLoader() {
    }

    /** @return configured connections, in file order; empty when the file is missing or defines none */
    public static List<ConnectionDefinition> load(MailMcpProperties server, ObjectMapper mapper,
                                                  UnaryOperator<String> env) {
        Path file = server.resolvedConnectionsFile();
        if (!Files.exists(file)) {
            log.warn("No mail connections configured: there is no file at {}. Create it (or point "
                    + MailMcpProperties.CONNECTIONS_FILE_ENV + " elsewhere) with at least one entry under "
                    + "\"connections\". Starting with no connections.", file);
            return List.of();
        }

        JsonNode root = read(file, mapper);
        warnUnknownKeys(root, FILE_KEYS, "top level of " + file);
        JsonNode connections = root.get("connections");
        if (connections == null || connections.isNull()) {
            log.warn("No mail connections configured: {} has no \"connections\". Starting with no connections.", file);
            return List.of();
        }
        if (!connections.isObject()) {
            throw new IllegalStateException("\"connections\" in " + file + " must be a JSON object of named entries");
        }

        Map<String, ConnectionDefinition> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : connections.properties()) {
            String name = ConnectionDefinition.requireValidName(property.getKey(), "in " + file);
            JsonNode node = property.getValue();
            if (node == null || node.isNull()) {
                throw new IllegalStateException("Connection '" + name + "' in " + file + " has no settings");
            }
            if (!node.isObject()) {
                throw new IllegalStateException("Connection '" + name + "' in " + file + " must be a JSON object");
            }
            warnUnknownKeys(node, Set.copyOf(ENTRY_KEYS), "connection '" + name + "'");
            ConnectionsFile.Entry entry = toEntry(name, node, mapper, file);
            definitions.put(name, define(name, entry, node, server.connectionDir(name), env));
        }
        if (definitions.isEmpty()) {
            log.warn("No mail connections configured: {} defines none under \"connections\". "
                    + "Starting with no connections.", file);
            return List.of();
        }
        logConfiguration(definitions.values());
        return List.copyOf(definitions.values());
    }

    private static JsonNode read(Path path, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(path));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read connections file " + path + ": " + e.getMessage(), e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new IllegalStateException("Connections file " + path + " is empty");
        }
        if (!root.isObject()) {
            throw new IllegalStateException("Connections file " + path + " must hold a JSON object");
        }
        return root;
    }

    private static ConnectionsFile.Entry toEntry(String name, JsonNode node, ObjectMapper mapper, Path file) {
        try {
            return mapper.readerFor(ConnectionsFile.Entry.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(node);
        } catch (Exception e) {
            throw new IllegalStateException("Connection '" + name + "' in " + file + " has a value of the wrong type: "
                    + e.getMessage(), e);
        }
    }

    private static void warnUnknownKeys(JsonNode node, Set<String> known, String where) {
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            if (!known.contains(property.getKey())) {
                log.warn("Unknown key '{}' in {} is ignored. Known keys: {}", property.getKey(), where,
                        known.stream().sorted().collect(Collectors.joining(", ")));
            }
        }
    }

    private static ConnectionDefinition define(String name, ConnectionsFile.Entry entry, JsonNode node,
                                               Path connectionDir, UnaryOperator<String> env) {
        // Resolve every placeholder first: an unset variable stops startup even when the entry has
        // another problem, so the operator sees it at once.
        String field = "connections." + name;
        String description = blankToNull(EnvironmentPlaceholders.resolve(entry.description(), field + ".description", env));
        String typeText = blankToNull(EnvironmentPlaceholders.resolve(entry.type(), field + ".type", env));
        String authText = blankToNull(EnvironmentPlaceholders.resolve(entry.auth(), field + ".auth", env));
        String url = blankToNull(EnvironmentPlaceholders.resolve(entry.url(), field + ".url", env));
        String host = blankToNull(EnvironmentPlaceholders.resolve(entry.host(), field + ".host", env));
        String username = blankToNull(EnvironmentPlaceholders.resolve(entry.username(), field + ".username", env));
        String password = EnvironmentPlaceholders.resolve(entry.password(), field + ".password", env);
        String domain = blankToNull(EnvironmentPlaceholders.resolve(entry.domain(), field + ".domain", env));

        Common common = new Common(name, description, connectionDir);
        if (typeText == null) {
            return common.unusable(null, null, "no 'type' (expected one of: " + ConnectionType.knownIds() + ")");
        }
        Optional<ConnectionType> parsedType = ConnectionType.fromId(typeText);
        if (parsedType.isEmpty()) {
            return common.unusable(null, null, "unknown type '" + typeText + "' (expected one of: "
                    + ConnectionType.knownIds() + ")");
        }
        ConnectionType type = parsedType.get();
        AuthKind auth = authText == null ? type.defaultAuth() : AuthKind.fromId(authText).orElse(null);
        if (auth == null || auth.type() != type) {
            return common.unusable(type, null, "auth '" + authText + "' is not valid for type '" + type.id()
                    + "' (expected one of: " + AuthKind.knownIds(type) + ")");
        }
        if (!auth.supported()) {
            return common.unusable(type, auth, "auth '" + auth.id() + "' is not supported yet; use '"
                    + type.defaultAuth().id() + "'");
        }
        warnKeysOfOtherType(name, type, node);
        if (username == null) {
            return common.unusable(type, auth, "no 'username'");
        }
        if (password == null || password.isEmpty()) {
            return common.unusable(type, auth, "no 'password'");
        }

        return switch (type) {
            case EWS -> defineEws(common, auth, url, username, password, domain,
                    entry.connectTimeoutSeconds(), entry.readTimeoutSeconds());
            case IMAP -> defineImap(common, auth, host, entry.port(), entry.tls(), username, password);
        };
    }

    private static ConnectionDefinition defineEws(Common common, AuthKind auth, String url, String username,
                                                  String password, String domain,
                                                  Integer connectTimeoutSeconds, Integer readTimeoutSeconds) {
        if (url == null) {
            return common.unusable(ConnectionType.EWS, auth,
                    "no 'url' (the EWS endpoint, e.g. https://webmail.example.com/EWS/Exchange.asmx)");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return common.unusable(ConnectionType.EWS, auth, "'url' is not a valid URL: " + e.getMessage());
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return common.unusable(ConnectionType.EWS, auth,
                    "'url' must be an absolute https:// URL; credentials are never sent over plain HTTP");
        }
        if (!positiveOrNull(connectTimeoutSeconds) || !positiveOrNull(readTimeoutSeconds)) {
            return common.unusable(ConnectionType.EWS, auth, "timeouts must be positive numbers of seconds");
        }
        EwsSettings ews = new EwsSettings(uri, auth, username, password, domain,
                connectTimeoutSeconds == null ? EwsSettings.DEFAULT_CONNECT_TIMEOUT : Duration.ofSeconds(connectTimeoutSeconds),
                readTimeoutSeconds == null ? EwsSettings.DEFAULT_READ_TIMEOUT : Duration.ofSeconds(readTimeoutSeconds));
        return new ConnectionDefinition(common.name, common.description, ConnectionType.EWS, auth, ews, null,
                common.connectionDir, null);
    }

    private static ConnectionDefinition defineImap(Common common, AuthKind auth, String host, Integer port,
                                                   Boolean tls, String username, String password) {
        if (host == null) {
            return common.unusable(ConnectionType.IMAP, auth, "no 'host' (e.g. imap.gmail.com)");
        }
        int effectivePort = port == null ? ImapSettings.DEFAULT_PORT : port;
        if (effectivePort < 1 || effectivePort > 65535) {
            return common.unusable(ConnectionType.IMAP, auth, "'port' must be 1..65535, got " + effectivePort);
        }
        if (tls != null && !tls) {
            return common.unusable(ConnectionType.IMAP, auth,
                    "plain IMAP is not supported; set 'tls' to true (port 993)");
        }
        ImapSettings imap = new ImapSettings(host.trim(), effectivePort, true, auth, username, password);
        return new ConnectionDefinition(common.name, common.description, ConnectionType.IMAP, auth, null, imap,
                common.connectionDir, null);
    }

    private static void warnKeysOfOtherType(String name, ConnectionType type, JsonNode node) {
        Set<String> foreign = type == ConnectionType.EWS ? IMAP_ONLY_KEYS : EWS_ONLY_KEYS;
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            if (foreign.contains(property.getKey())) {
                log.warn("Key '{}' in connection '{}' does not apply to type '{}' and is ignored",
                        property.getKey(), name, type.id());
            }
        }
    }

    private static void logConfiguration(Iterable<ConnectionDefinition> definitions) {
        List<String> described = new ArrayList<>();
        for (ConnectionDefinition definition : definitions) {
            String target = definition.ews() != null ? definition.ews().url().getHost()
                    : definition.imap() != null ? definition.imap().host() : "?";
            described.add(definition.name() + " -> "
                    + (definition.type() == null ? "?" : definition.type().id())
                    + (definition.auth() == null ? "" : "/" + definition.auth().id())
                    + " " + target
                    + (definition.usable() ? "" : " (unusable: " + definition.configError() + ")"));
        }
        log.info("Configured connections: {}", String.join(", ", described));
    }

    private static boolean positiveOrNull(Integer value) {
        return value == null || value > 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** What every form of one entry shares: name, description and data directory. */
    private record Common(String name, String description, Path connectionDir) {
        ConnectionDefinition unusable(ConnectionType type, AuthKind auth, String configError) {
            return new ConnectionDefinition(name, description, type, auth, null, null, connectionDir, configError);
        }
    }
}
