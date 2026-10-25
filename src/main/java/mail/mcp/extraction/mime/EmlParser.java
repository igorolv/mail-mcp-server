package mail.mcp.extraction.mime;

import mail.mcp.extraction.ExtractionContext;
import mail.mcp.extraction.ExtractionLimitException;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.parser.AbstractContentHandler;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static mail.mcp.extraction.mime.MimeHeaders.*;
import static mail.mcp.extraction.mime.ParsedMail.*;

/** Streaming MIME traversal; only bounded metadata and the selected text are kept in memory.
 * No Spring wiring, mailbox access, redaction or MCP exposure. Caller owns the returned directory.
 */
public final class EmlParser {
    /** The input belongs to the caller and is not closed. outputRoot must already exist. */
    public ParsedMail parse(InputStream input, Path outputRoot, ExtractionContext context) throws IOException {
        context.checkTime();
        Path parent = outputRoot.toRealPath();
        Path directory = Files.createDirectory(parent.resolve("mime-" + UUID.randomUUID()));
        try {
            return parseInto(input, directory, context);
        } catch (IOException | RuntimeException ex) {
            // Roll back only this invocation's newly created directory, never sender paths.
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    private ParsedMail parseInto(InputStream input, Path directory, ExtractionContext context) throws IOException {
        Handler handler = new Handler(directory, context);
        var limits = context.limits();
        var config = MimeConfig.custom().setStrictParsing(false)
                .setMaxLineLen(limits.maxHeaderLength()).setMaxHeaderLen(limits.maxHeaderLength())
                .setMaxHeaderCount(limits.maxHeadersPerEntity()).setMaxContentLen(-1)
                .setMaxTotalHeaderCount(limits.maxHeaders()).setMaxTotalHeaderLen(limits.maxHeaderBytes())
                .setMaxPartCount(limits.maxParts()).setMaxNestingDepth(limits.maxMimeDepth()).build();
        var parser = new MimeStreamParser(config, new DecodeMonitor() {
            @Override public boolean isListening() { return true; }
            @Override public boolean warn(String error, String action) {
                // Library messages can contain sender data. Keep only a stable code.
                handler.warning("mime-decoding-warning");
                handler.partial = true;
                handler.current().damaged = true;
                return false;
            }
        }, null);
        parser.setContentDecoding(true);
        parser.setNoRecurse();
        parser.setContentHandler(handler);
        try {
            parser.parse(new InputLimit(input, context));
        } catch (ExtractionLimitException ex) {
            handler.limit(ex);
        } catch (org.apache.james.mime4j.io.MaxHeaderLengthLimitException
                 | org.apache.james.mime4j.io.MaxHeaderLimitException
                 | org.apache.james.mime4j.io.MaxLineLimitException
                 | org.apache.james.mime4j.io.MaxNestingDepthLimitException
                 | org.apache.james.mime4j.io.MaxPartCountLimitException ex) {
            handler.warning("mime-parser-limit");
            handler.current().status = Status.LIMIT_EXCEEDED;
            handler.status = Status.LIMIT_EXCEEDED;
        } catch (MimeException ex) {
            // Mime4j's own header/line/content guards are limits, not a reason to retry permissively.
            boolean limit = isMimeLimit(ex);
            handler.warning(limit ? "mime-parser-limit" : "mime-parse-error");
            handler.status = limit ? Status.LIMIT_EXCEEDED : Status.PARSE_ERROR;
        } catch (org.apache.james.mime4j.MimeIOException ex) {
            boolean limit = isMimeLimit(ex);
            handler.warning(limit ? "mime-parser-limit" : "mime-parse-error");
            handler.status = limit ? Status.LIMIT_EXCEEDED : Status.PARSE_ERROR;
        }
        Body body = null;
        try {
            body = handler.choose(handler.root);
        } catch (ExtractionLimitException ex) {
            handler.limit(ex, handler.bodyNode);
        }
        Headers headers = normalize(handler.root.headers, handler::warning);
        Status status = handler.status;
        if (status == Status.OK) {
            if (handler.root.format.equals("encrypted")) status = Status.ENCRYPTED;
            else if (handler.unsupported) status = body == null ? Status.UNSUPPORTED : Status.PARTIAL;
            else if (handler.partial) status = Status.PARTIAL;
        }
        return new ParsedMail(headers, body, handler.root.freeze(), status, handler.diagnostics, directory);
    }

    private final class Handler extends AbstractContentHandler {
        final Path directory;
        final ExtractionContext context;
        final Node root = new Node("");
        final Deque<Node> stack = new ArrayDeque<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        Node bodyNode = root;
        Status status = Status.OK;
        boolean partial, unsupported;

        Handler(Path directory, ExtractionContext context) {
            this.directory = directory;
            this.context = context;
            stack.push(root);
        }

        Node current() { return stack.peek(); }
        void warning(String code) { warning(current(), code); }
        void warning(Node node, String code) {
            var warning = new Diagnostic(code, node.path.isEmpty() ? "1" : node.path);
            if (!diagnostics.contains(warning)) diagnostics.add(warning);
        }
        void defect(String code) { warning(code); partial = true; }
        void defect(Node node, String code) { warning(node, code); partial = true; }
        void limit(ExtractionLimitException ex) { limit(ex, current()); }
        void limit(ExtractionLimitException ex, Node node) {
            warning(node, ex.code());
            node.status = Status.LIMIT_EXCEEDED;
            status = Status.LIMIT_EXCEEDED;
        }

        @Override public void startMessage() { context.part(1); }

        @Override public void startBodyPart() {
            Node parent = current();
            String path = (parent.path.isEmpty() ? "" : parent.path + ".") + (parent.children.size() + 1);
            Node part = new Node(path);
            parent.children.add(part);
            stack.push(part);
            context.part(stack.size());
        }

        @Override public void endBodyPart() { stack.pop(); }

        @Override public void field(Field field) {
            Node node = current();
            int bytes = field.getRaw() == null ? field.toString().length() : field.getRaw().length();
            context.header(node.headers.size() + 1, bytes);
            if (node.headers.stream().anyMatch(h -> h.name().equalsIgnoreCase(field.getName()))) {
                defect("header-repeated");
            }
            String raw = field.getBody();
            node.headers.add(new Header(field.getName(), raw, decode(raw, this::defect)));
        }

        @Override public void endHeader() {
            Node n = current();
            String type = first(n.headers, "content-type");
            String disposition = first(n.headers, "content-disposition");
            n.type = token(type, "text/plain");
            n.disposition = token(disposition, null);
            n.charset = parameter(type, "charset", this::defect);
            n.cid = unfold(first(n.headers, "content-id"));
            if (n.cid != null && n.cid.startsWith("<") && n.cid.endsWith(">")) {
                n.cid = n.cid.substring(1, n.cid.length() - 1);
            }
            String filename = parameter(disposition, "filename", this::defect);
            String name = parameter(type, "name", this::defect);
            if (filename != null && name != null && !filename.equals(name)) warning("filename-conflict");
            n.filename = filename == null || filename.isBlank() ? name : filename;
            if ((n.type.equals("text/plain") || n.type.equals("text/html")) && n.charset == null
                    && n.filename == null && !"attachment".equals(n.disposition)) defect("charset-missing");
            if (n.filename != null && n.filename.contains("=?")) {
                warning("filename-encoded-word-nonstandard");
                n.filename = decode(n.filename, this::defect);
            }
            if (n.filename != null && unsafeName(n.filename)) warning("unsafe-filename");
            if ("attachment".equals(n.disposition) && n.filename == null) warning("filename-missing");
            if (!n.type.startsWith("multipart/") && n.path.isEmpty()) n.path = "1";
            n.format = switch (n.type) {
                case "message/rfc822" -> "eml";
                case "application/vnd.ms-outlook" -> "msg";
                case "application/ms-tnef", "application/vnd.ms-tnef", "application/x-tnef" -> "tnef";
                case "application/zip", "application/x-zip-compressed" -> "zip";
                case "text/calendar" -> "calendar";
                case "application/pkcs7-signature", "application/x-pkcs7-signature" -> "signature";
                case "application/pkcs7-mime", "application/x-pkcs7-mime" -> {
                    String smime = parameter(type, "smime-type", this::defect);
                    yield "enveloped-data".equalsIgnoreCase(smime) ? "encrypted"
                            : "signed-data".equalsIgnoreCase(smime) ? "opaque_signed" : "unknown_cms";
                }
                case "multipart/signed" -> "detached_signed";
                default -> "mime";
            };
            if (n.format.equals("detached_signed") || n.format.equals("signature")) warning("signature-not-verified");
            if (List.of("msg", "tnef", "zip", "calendar", "encrypted", "opaque_signed", "unknown_cms")
                    .contains(n.format)) {
                n.status = n.format.equals("encrypted") ? Status.ENCRYPTED : Status.UNSUPPORTED;
                warning("unexpanded-" + n.format);
                unsupported = true;
            }
            if (n.type.startsWith("multipart/") && ("attachment".equals(n.disposition) || n.filename != null)) {
                n.format = "multipart_attachment";
                n.status = Status.UNSUPPORTED;
                warning("unexpanded-multipart-attachment");
                unsupported = true;
            }
        }

        @Override public void body(BodyDescriptor descriptor, InputStream input) throws IOException {
            Node n = current();
            if (n.type.startsWith("multipart/")) {
                // Mime4j may degrade a multipart without boundary to a plain body descriptor.
                // Preserve its bytes but do not present an unparsed structure as an empty successful mail.
                warning("mime-parse-error");
                n.status = Status.PARSE_ERROR;
                status = Status.PARSE_ERROR;
            }
            context.file();
            // All leaf bytes, including body alternatives, are recoverable without trusting a name or extension.
            Path file = directory.resolve(UUID.randomUUID() + ".bin").normalize();
            if (!file.getParent().equals(directory)) throw new IOException("Invalid internal attachment path");
            MessageDigest hash = sha256();
            long size = 0;
            boolean complete = false;
            // Enter cleanup only after CREATE_NEW succeeds, so a collision never deletes an existing file.
            var createdOutput = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            try (var out = createdOutput) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > context.limits().maxPartBytes() - size) {
                        throw new ExtractionLimitException("part-bytes-limit");
                    }
                    context.derivedBytes(count);
                    out.write(buffer, 0, count);
                    hash.update(buffer, 0, count);
                    size += count;
                }
                complete = true;
            } finally {
                if (!complete) Files.deleteIfExists(file);
            }
            n.file = file;
            n.size = size;
            n.hash = HexFormat.of().formatHex(hash.digest());
            if (n.damaged) {
                Files.delete(file);
                n.file = null;
                n.size = 0;
                n.hash = null;
                n.status = Status.PARTIAL;
                warning("damaged-part-discarded");
                return;
            }
            if (n.format.equals("eml")) {
                try {
                    ExtractionContext child = context.child();
                    try (InputStream nested = Files.newInputStream(file)) {
                        n.nested = parse(nested, directory, child);
                    }
                    n.status = n.nested.status();
                    if (n.status == Status.LIMIT_EXCEEDED) status = Status.LIMIT_EXCEEDED;
                    else if (n.status != Status.OK) partial = true;
                } catch (ExtractionLimitException ex) {
                    limit(ex);
                }
            }
        }

        Body choose(Node n) throws IOException {
            bodyNode = n;
            if ("attachment".equals(n.disposition) || n.filename != null) return null;
            if (n.type.equals("multipart/related")) {
                String start = parameter(first(n.headers, "content-type"), "start", this::defect);
                if (start != null) {
                    String cid = start.startsWith("<") && start.endsWith(">")
                            ? start.substring(1, start.length() - 1) : start;
                    for (Node child : n.children) if (cid.equals(child.cid)) return choose(child);
                    defect(n, "related-root-missing");
                }
                return n.children.isEmpty() ? null : choose(n.children.getFirst());
            }
            if (n.type.equals("multipart/signed")) return n.children.isEmpty() ? null : choose(n.children.getFirst());
            if (n.type.startsWith("multipart/")) {
                // Plain preference is local to alternatives, never overrules the root of related/mixed.
                if (n.type.equals("multipart/alternative")) {
                    for (Node child : n.children) {
                        if (child.type.equals("text/plain")) {
                            Body body = choose(child);
                            if (body != null && !body.text().isBlank()) return body;
                        }
                    }
                }
                for (Node child : n.children) {
                    if (n.type.equals("multipart/alternative") && child.type.equals("text/plain")) continue;
                    Body body = choose(child);
                    if (body != null && !body.text().isBlank()) return body;
                }
                return null;
            }
            if (n.file == null || (!n.type.equals("text/plain") && !n.type.equals("text/html"))) return null;
            if (n.type.equals("text/html") && n.size > context.limits().maxHtmlBytes()) {
                throw new ExtractionLimitException("html-bytes-limit");
            }
            // A bounded decoder reads from disk; binary attachments never become Strings.
            Charset charset = StandardCharsets.US_ASCII;
            if (n.charset == null) defect(n, "charset-missing");
            else {
                try { charset = Charset.forName(n.charset); }
                catch (IllegalArgumentException ex) { defect(n, "charset-unsupported"); }
            }
            int charLimit = context.limits().maxTextChars();
            StringBuilder text = new StringBuilder();
            var decoder = charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
            // HTML source may be longer than the resulting text, but is bounded separately before DOM construction.
            int inputCharLimit = n.type.equals("text/html") ? context.limits().maxHtmlBytes() : charLimit;
            try (var reader = new java.io.InputStreamReader(Files.newInputStream(n.file), decoder)) {
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    context.checkTime();
                    if (count > inputCharLimit - text.length()) throw new ExtractionLimitException("text-limit");
                    text.append(buffer, 0, count);
                }
            }
            String value = text.toString();
            if (value.indexOf('\ufffd') >= 0) defect(n, "charset-decoding-defect");
            if (charset.equals(StandardCharsets.ISO_8859_1)
                    && value.chars().filter(c -> c >= 128).count() > value.length() / 5) {
                defect(n, "charset-mismatch-suspected");
            }
            if (n.type.equals("text/html")) value = HtmlText.convert(value, context);
            if (value.isBlank()) return null;
            context.textChars(value.length());
            return new Body(n.path, n.type, n.charset, charset.name(), value);
        }
    }

    private static boolean unsafeName(String name) {
        return name.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$")
                || name.chars().anyMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\' || c == ':')
                || name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ");
    }

    private static boolean isMimeLimit(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getPackageName().equals("org.apache.james.mime4j.io")
                    && cause.getClass().getSimpleName().contains("LimitException")) return true;
        }
        return false;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static final class Node {
        String path, type = "text/plain", disposition, filename, cid, charset, hash, format = "mime";
        Path file;
        long size;
        Status status = Status.OK;
        final List<Header> headers = new ArrayList<>();
        final List<Node> children = new ArrayList<>();
        ParsedMail nested;
        boolean damaged;
        Node(String path) { this.path = path; }
        Part freeze() {
            return new Part(path, type, disposition, filename, cid, charset, headers, file, size, hash,
                    status, format, children.stream().map(Node::freeze).toList(), nested);
        }
    }

    private static final class InputLimit extends FilterInputStream {
        final ExtractionContext context;
        long count;
        InputLimit(InputStream input, ExtractionContext context) { super(input); this.context = context; }
        private void charge(int amount) {
            context.checkTime();
            if (amount > context.limits().maxInputBytes() - count) throw new ExtractionLimitException("input-bytes-limit");
            count += amount;
        }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) charge(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            long remaining = context.limits().maxInputBytes() - count;
            int bounded = (int) Math.min(length, remaining == 0 ? 1 : Math.min(Integer.MAX_VALUE, remaining));
            int value = in.read(buffer, offset, bounded);
            if (value > 0) charge(value);
            return value;
        }
        @Override public long skip(long count) throws IOException {
            // Skips must also pass through the byte budget.
            byte[] buffer = new byte[(int) Math.min(8192, Math.max(0, count))];
            long skipped = 0;
            while (skipped < count) {
                int n = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                if (n < 0) break;
                skipped += n;
            }
            return skipped;
        }
        @Override public void close() { /* Caller owns the source stream. */ }
    }
}
