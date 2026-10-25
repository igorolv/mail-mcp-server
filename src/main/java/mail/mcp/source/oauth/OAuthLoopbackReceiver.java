package mail.mcp.source.oauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** User-run authorization only. Never logs callback URLs, codes or query parameters. */
public final class OAuthLoopbackReceiver implements AutoCloseable {
    private static final String PATH = "/oauth2/callback";
    private final HttpServer server;
    private final String state;
    private final CompletableFuture<String> code = new CompletableFuture<>();

    public OAuthLoopbackReceiver(String state) {
        this.state = state;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
            server.createContext(PATH, this::handle);
            server.start();
        } catch (IOException e) { throw new OAuthException(OAuthException.Kind.LOCAL_IO); }
    }

    public URI redirectUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod()) || !PATH.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, "Not found."); return;
            }
            Map<String, String> fields = query(exchange.getRequestURI().getRawQuery());
            String supplied = fields.getOrDefault("state", "");
            if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                respond(exchange, 400, "Invalid authorization state. Return to the authorization tab."); return;
            }
            if (code.isDone()) { respond(exchange, 409, "Authorization already received."); return; }
            String authCode = fields.get("code"), error = fields.get("error");
            if (error != null && authCode == null) {
                respond(exchange, 200, "Authorization was not completed. Return to the terminal.");
                code.completeExceptionally(new OAuthException(OAuthException.Kind.DENIED)); return;
            }
            if (error != null || authCode == null || authCode.isBlank() || authCode.length() > 4096
                    || authCode.contains("\r") || authCode.contains("\n")) {
                respond(exchange, 400, "Invalid authorization response."); return;
            }
            respond(exchange, 200, "Authorization received. Close this tab and return to the terminal.");
            code.complete(authCode);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, "Invalid authorization response.");
        } finally { exchange.close(); }
    }

    static Map<String, String> query(String raw) {
        if (raw == null || raw.length() > 8192) { throw new IllegalArgumentException("Invalid query"); }
        var fields = new LinkedHashMap<String, String>();
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (fields.putIfAbsent(key, value) != null) { throw new IllegalArgumentException("Duplicate query parameter"); }
        }
        return fields;
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    public String await(Duration timeout) {
        try { return code.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { throw new OAuthException(OAuthException.Kind.TIMEOUT); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new OAuthException(OAuthException.Kind.TIMEOUT);
        } catch (ExecutionException e) { throw new OAuthException(OAuthException.Kind.DENIED); }
    }

    @Override public void close() { server.stop(0); }
}
