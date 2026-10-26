package mail.mcp.redaction;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Best-effort, offline secret replacement for tool output only. Never used by storage or indexing.
 * Original rules informed by docs/research/RT-08-redaction-detectors.md; no third-party rules copied.
 * Identifiers and file paths are deliberately outside this filter (plan.md 3.1).
 */
public final class SecretRedactor {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LABEL = "(?:пароль(?:[ \\t]+от[ \\t]+wi-?fi)?|password|passwd|pwd|psk|"
            + "код[ \\t]+подтверждения|одноразовый[ \\t]+код|verification[ \\t]+code|otp|"
            + "api[ _-]?key|api[ _-]?secret|access[ _-]?token|refresh[ _-]?token|client[ _-]?secret|"
            + "secret|token|токен|секрет|ключ[ \\t]+api)";
    private static final Pattern FIELD = pattern("(?<![\\p{L}\\p{N}_])(?:[a-z0-9_]*_)?(?<label>" + LABEL
            + ")(?:_[a-z0-9_]+)?(?![\\p{L}\\p{N}_])");
    private static final Pattern NEXT_FIELD = pattern("[;,&][ \\t]*+[\\p{L}\\p{N}_-][\\p{L}\\p{N}_ -]*[=:]|"
            + "(?<![ \\t])[ \\t]++" + LABEL + "[ \\t]*[=:]");
    private static final Pattern URI = pattern("(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*://([^\\s/@?#<>\"`]+)@");
    private static final Pattern AUTH = pattern("(?<![\\p{L}\\p{N}_-])(?:proxy-)?authorization[\"']?[ \\t]*:[ \\t]*[\"']?"
            + "(basic|bearer)[ \\t]+([a-z0-9._~+/=-]++)");
    private static final Pattern GITHUB = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:gh[pousr]_[A-Za-z0-9]{36}"
            + "|github_pat_[A-Za-z0-9]{22}_[A-Za-z0-9]{59})(?![\\p{L}\\p{N}_])");
    private static final Pattern PEM = Pattern.compile("-----BEGIN ((?:RSA |EC |DSA |ENCRYPTED |OPENSSH )?PRIVATE KEY)-----");
    // Candidate runs are scanned once; validation is structural, never a signature or sign-in check.
    private static final Pattern JWT = Pattern.compile("(?<![\\p{L}\\p{N}_.-])[A-Za-z0-9_-]++(?:\\.[A-Za-z0-9_-]*+){2,5}"
            + "(?![\\p{L}\\p{N}_.-])");
    private static final Pattern WRAPPED_JWT = Pattern.compile("(?<![\\p{L}\\p{N}_.-])eyJ[A-Za-z0-9_.-]*+"
            + "(?:\\r?\\n[ \\t]++[A-Za-z0-9_.-]++){1,8}(?![\\p{L}\\p{N}_.-])");
    private static final Pattern MASK = pattern("(?:\\*{3,}|\\$\\{[^}]*}|\\[(?:скрыто|redacted)[^]]*])");
    private final boolean enabled;

    public SecretRedactor(boolean enabled) { this.enabled = enabled; }

    public boolean enabled() { return enabled; }

    public List<String> texts(List<String> values) {
        return values == null || !enabled ? values : values.stream().map(this::text).toList();
    }

    /** Replace the complete text before slicing or highlighting it. Null stays null. */
    public String text(String text) {
        if (!enabled || text == null || text.isEmpty()) { return text; }
        List<Span> spans = new ArrayList<>();
        fields(text, spans);
        var uri = URI.matcher(text);
        while (uri.find()) {
            // User-only userinfo is not a password. Keep ordinary addresses and URI paths intact.
            if (uri.group(1).contains(":")) { spans.add(new Span(uri.start(1), uri.end(1), "credentials")); }
        }
        var auth = AUTH.matcher(text);
        while (auth.find()) {
            if (auth.group(1).equalsIgnoreCase("bearer") || basic(auth.group(2))) {
                spans.add(new Span(auth.start(2), auth.end(2), "token"));
            }
        }
        var github = GITHUB.matcher(text);
        while (github.find()) { spans.add(new Span(github.start(), github.end(), "token")); }
        var pem = PEM.matcher(text);
        int pemEnd = 0;
        while (pem.find(pemEnd)) {
            String closing = "-----END " + pem.group(1) + "-----";
            int end = text.indexOf(closing, pem.end());
            pemEnd = end < 0 ? text.length() : end + closing.length();
            spans.add(new Span(pem.start(), pemEnd, "private-key"));
            if (pemEnd == text.length()) { break; }
        }
        var jwt = JWT.matcher(text);
        while (jwt.find()) {
            int length = jwtLength(jwt.group());
            if (length > 0) { spans.add(new Span(jwt.start(), jwt.start() + length, "token")); }
        }
        var wrapped = WRAPPED_JWT.matcher(text);
        while (wrapped.find()) {
            int length = jwtLength(wrapped.group());
            if (length > 0) { spans.add(new Span(wrapped.start(), wrapped.start() + length, "token")); }
        }
        if (spans.isEmpty()) { return text; }
        spans.sort(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::end).reversed()));
        var result = new StringBuilder(text.length());
        int cursor = 0;
        for (int i = 0; i < spans.size();) {
            Span first = spans.get(i++);
            int end = first.end();
            Span largest = first;
            while (i < spans.size() && spans.get(i).start() < end) {
                Span next = spans.get(i++);
                end = Math.max(end, next.end());
                if (next.end() - next.start() > largest.end() - largest.start()) { largest = next; }
            }
            result.append(text, cursor, first.start()).append("[redacted:").append(largest.kind()).append(']');
            cursor = end;
        }
        return result.append(text, cursor, text.length()).toString();
    }

    private static void fields(String text, List<Span> spans) {
        var fields = FIELD.matcher(text);
        int valueEnd = 0;
        while (fields.find()) {
            // A label inside an already hidden quoted value must not consume its closing syntax.
            if (fields.start() < valueEnd) { continue; }
            String label = fields.group("label").toLowerCase(Locale.ROOT);
            boolean otp = label.equals("otp") || label.contains("код") || label.contains("code");
            int at = fields.end();
            // JSON keys, Markdown labels and XML leaves retain their surrounding syntax.
            while (at < text.length() && "\"'`*".indexOf(text.charAt(at)) >= 0) { at++; }
            int whitespace = at;
            at = horizontal(text, at);
            boolean tab = text.substring(whitespace, at).indexOf('\t') >= 0;
            boolean xml = fields.start() > 0 && text.charAt(fields.start() - 1) == '<';
            if (at < text.length() && (":=|".indexOf(text.charAt(at)) >= 0 || xml && text.charAt(at) == '>')) {
                at = horizontal(text, at + 1);
                if (fields.start() >= 2 && text.startsWith("**", fields.start() - 2) && text.startsWith("**", at)) {
                    at = horizontal(text, at + 2);
                }
            } else if (!tab) { continue; }
            // Only an indented next line is a continuation, not an arbitrary following paragraph.
            if (at < text.length() && (text.charAt(at) == '\r' || text.charAt(at) == '\n')) {
                if (text.charAt(at) == '\r') { at++; }
                if (at < text.length() && text.charAt(at) == '\n') { at++; }
                int next = horizontal(text, at);
                if (next == at) { continue; }
                at = next;
            }
            if (at >= text.length()) { continue; }
            int start = at;
            char opening = text.charAt(at);
            char closing = switch (opening) { case '«' -> '»'; case '“' -> '”'; default -> opening; };
            int end;
            if ("\"'`«“".indexOf(opening) >= 0) {
                start = ++at;
                while (at < text.length()) {
                    char ch = text.charAt(at);
                    if (ch == '\\' && at + 1 < text.length()) { at += 2; continue; }
                    if (ch == closing) {
                        // ADO/SQL-style doubled quotes, as well as JSON backslash escapes.
                        if (at + 1 < text.length() && text.charAt(at + 1) == closing) { at += 2; continue; }
                        break;
                    }
                    if (ch == '\r' || ch == '\n') { break; }
                    at++;
                }
                end = at;
            } else {
                if (text.startsWith("${", at)) { continue; }
                if (otp) {
                    while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') { at++; }
                    if (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) { continue; }
                } else {
                    while (at < text.length() && "\r\n<|".indexOf(text.charAt(at)) < 0) { at++; }
                }
                end = at;
                var next = NEXT_FIELD.matcher(text).region(start, end);
                if (next.find()) { end = next.start(); }
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) { end--; }
            }
            if (end <= start) { continue; }
            String value = text.substring(start, end);
            if (value.isBlank() || MASK.matcher(value).matches()) { continue; }
            if (otp && !value.matches("[0-9]{4,10}")) { continue; }
            String kind = otp ? "code" : label.contains("пароль") || label.contains("password")
                    || label.contains("passwd") || label.equals("pwd") || label.equals("psk") ? "password" : "secret";
            spans.add(new Span(start, end, kind));
            valueEnd = end;
        }
    }

    private static boolean basic(String encoded) {
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.ISO_8859_1).contains(":");
        } catch (IllegalArgumentException e) { return false; }
    }

    private static int jwtLength(String candidate) {
        String compact = candidate.replaceAll("\\r?\\n[ \\t]+", "");
        if (jwt(compact)) { return candidate.length(); }
        // A sentence-ending period is not an extra segment. Validate the whole remaining
        // token; never fall back to a valid prefix of a longer, malformed token.
        if (compact.endsWith(".") && jwt(compact.substring(0, compact.length() - 1))) {
            return candidate.length() - 1;
        }
        return 0;
    }

    private static boolean jwt(String candidate) {
        String[] parts = candidate.split("\\.", -1);
        if (parts.length != 3 && parts.length != 5 || parts[0].length() > 16_384) { return false; }
        try {
            var header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (header == null || !header.isObject() || !header.path("alg").isString()) { return false; }
            if (parts.length == 5 && !header.path("enc").isString()) { return false; }
            for (int i = 1; i < parts.length; i++) { Base64.getUrlDecoder().decode(parts[i]); }
            return !parts[1].isEmpty() || parts.length == 5;
        } catch (RuntimeException e) { return false; }
    }

    private static int horizontal(String text, int at) {
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\t')) { at++; }
        return at;
    }

    private static Pattern pattern(String expression) { return Pattern.compile(expression, FLAGS); }

    private record Span(int start, int end, String kind) { }
}
