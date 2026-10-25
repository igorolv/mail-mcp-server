package mail.mcp.source.oauth.google;

import mail.mcp.source.oauth.OAuthException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class OAuthJson {
    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final int MAX_BYTES = 65536;
    private OAuthJson() { }

    static JsonNode read(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) { throw new OAuthException(OAuthException.Kind.CONFIGURATION); }
            return MAPPER.readTree(bytes);
        } catch (IOException | RuntimeException e) {
            if (e instanceof OAuthException safe) { throw safe; }
            throw new OAuthException(OAuthException.Kind.CONFIGURATION);
        }
    }

    static String string(JsonNode node, String key) {
        if (node == null || !node.isObject()) { return null; }
        JsonNode value = node.get(key);
        return value != null && value.isString() ? value.asString() : null;
    }
}
