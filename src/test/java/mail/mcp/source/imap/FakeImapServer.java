package mail.mcp.source.imap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Single-socket, strict loopback endpoint. All mail and credentials are synthetic. */
final class FakeImapServer implements AutoCloseable {
    static final byte[] MIME = ("From: test@example.com\r\nTo: reader@example.org\r\n"
            + "Subject: Synthetic IMAP\r\n\r\nSynthetic body, never real mail.\r\n").getBytes(StandardCharsets.US_ASCII);

    static final String ENVELOPE = "(\"Sun, 25 Oct 2026 12:00:00 +0000\" \"=?UTF-8?B?0KLQtdC80LA=?=\" "
            + "((NIL NIL \"Sender\" \"example.com\")) NIL NIL NIL NIL NIL NIL \"<Move@example.com>\")";

    record Item(long uid, byte[] bytes, long declaredSize, String flags, String envelope) {
        Item(long uid, byte[] bytes) { this(uid, bytes, bytes.length, ""); }
        Item(long uid, byte[] bytes, long declaredSize, String flags) { this(uid, bytes, declaredSize, flags, ENVELOPE); }
    }

    record MailFolder(String name, long uidValidity, List<String> attributes, List<Item> messages) {
        MailFolder(String name, Item... messages) { this(name, 7, List.of(), List.of(messages)); }
    }

    final List<String> commands = new CopyOnWriteArrayList<>();
    private final ServerSocket listener;
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Future<?> task;
    private volatile Socket socket;
    volatile Long nextUidValidity;
    volatile String metadataFlags;
    volatile boolean omitMetadataFlags;
    volatile boolean malformedMetadataFlags;
    volatile Long malformedMetadataUid;
    volatile String listingFlags;
    volatile boolean rejectMetadata;

    FakeImapServer() throws IOException { this(false, false, "login"); }

    FakeImapServer(boolean reject, boolean empty, String mode) throws IOException {
        this(reject, mode, List.of(new MailFolder("INBOX", 7, List.of(),
                        empty ? List.of() : List.of(new Item(42, MIME))),
                new MailFolder("Container", 7, List.of("\\Noselect"), List.of())));
    }

    FakeImapServer(MailFolder... folders) throws IOException { this(false, "login", List.of(folders)); }

    private FakeImapServer(boolean reject, String mode, List<MailFolder> folders) throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(5000);
        task = executor.submit(() -> serve(reject, mode, folders));
    }

    private void serve(boolean reject, String mode, List<MailFolder> folders) {
        try (var accepted = listener.accept()) {
            socket = accepted;
            accepted.setSoTimeout(5000);
            var in = new BufferedReader(new InputStreamReader(accepted.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = accepted.getOutputStream();
            boolean oauth = mode.startsWith("oauth"), initial = mode.equals("oauth-ir");
            String capabilities = "IMAP4rev1" + (oauth ? " AUTH=XOAUTH2 AUTH=PLAIN AUTH=LOGIN"
                    : mode.equals("plain") ? " AUTH=PLAIN AUTH=LOGIN" : "") + (initial ? " SASL-IR" : "");
            send(out, "* OK [CAPABILITY " + capabilities + "] synthetic endpoint\r\n");
            MailFolder current = null;
            String line;
            while ((line = in.readLine()) != null) {
                int space = line.indexOf(' ');
                String tag = line.substring(0, space), command = line.substring(space + 1);
                commands.add(command);
                if (command.equals("CAPABILITY")) { send(out, "* CAPABILITY " + capabilities + "\r\n"); }
                else if (command.startsWith("AUTHENTICATE XOAUTH2")) {
                    String encoded;
                    if (initial) { encoded = command.substring("AUTHENTICATE XOAUTH2 ".length()); }
                    else { send(out, "+ \r\n"); encoded = in.readLine(); }
                    String decoded = new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                    assertThat(decoded).isEqualTo("user=user\001auth=Bearer FICTION_ACCESS\001\001");
                    if (reject) {
                        String challenge = java.util.Base64.getEncoder().encodeToString(
                                "{\"status\":\"401\",\"schemes\":\"bearer\"}".getBytes(StandardCharsets.UTF_8));
                        send(out, "+ " + challenge + "\r\n");
                        assertThat(in.readLine()).isEmpty(); // never another credential response
                        send(out, tag + " NO private-server-text\r\n"); continue;
                    }
                } else if (command.equals("AUTHENTICATE PLAIN")) {
                    send(out, "+ \r\n");
                    String decoded = new String(java.util.Base64.getDecoder().decode(in.readLine()), StandardCharsets.UTF_8);
                    assertThat(decoded).isEqualTo("\0user\0synthetic-test-value");
                    if (reject) { send(out, tag + " NO private-server-text\r\n"); continue; }
                } else if (command.startsWith("LOGIN ")) {
                    if (reject) { send(out, tag + " NO private-server-text\r\n"); continue; }
                } else if (command.startsWith("LIST ")) {
                    for (var folder : folders) {
                        send(out, "* LIST (" + String.join(" ", folder.attributes()) + ") \"/\" \"" + folder.name() + "\"\r\n");
                    }
                } else if (command.startsWith("EXAMINE ")) {
                    assertThat(current).as("previous folder must be closed").isNull();
                    String name = command.substring("EXAMINE ".length()).replace("\"", "");
                    current = folders.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
                    long next = current.messages().stream().mapToLong(Item::uid).max().orElse(0) + 1;
                    send(out, "* FLAGS (\\Seen)\r\n* " + current.messages().size() + " EXISTS\r\n"
                            + "* 0 RECENT\r\n* OK [UIDVALIDITY " + (nextUidValidity == null ? current.uidValidity() : nextUidValidity) + "] generation\r\n"
                            + "* OK [UIDNEXT " + next + "] next\r\n");
                    send(out, tag + " OK [READ-ONLY] examined\r\n"); continue;
                } else if (command.equals("CLOSE")) {
                    assertThat(current).isNotNull();
                    current = null;
                } else if (command.matches("UID FETCH [0-9:,]+ \\(UID\\)")) {
                    assertThat(current).isNotNull();
                    String set = command.split(" ")[2];
                    for (int i = 0; i < current.messages().size(); i++) {
                        long uid = current.messages().get(i).uid();
                        if (inSet(Math.toIntExact(uid), set)) {
                            send(out, "* " + (i + 1) + " FETCH (UID " + uid + ")\r\n");
                        }
                    }
                } else if (command.matches("FETCH [0-9:,]+ \\(UID\\)")) {
                    assertThat(current).isNotNull();
                    String set = command.split(" ")[1];
                    for (int i = 0; i < current.messages().size(); i++) {
                        if (inSet(i + 1, set)) {
                            send(out, "* " + (i + 1) + " FETCH (UID " + current.messages().get(i).uid() + ")\r\n");
                        }
                    }
                } else if (command.matches("FETCH [0-9:,]+ \\(FLAGS UID\\)")) {
                    assertThat(current).isNotNull();
                    String set = command.split(" ")[1];
                    for (int i = 0; i < current.messages().size(); i++) {
                        if (inSet(i + 1, set)) {
                            var item = current.messages().get(i);
                            send(out, "* " + (i + 1) + " FETCH (FLAGS ("
                                    + (listingFlags == null ? item.flags() : listingFlags) + ") UID " + item.uid() + ")\r\n");
                        }
                    }
                } else if (command.matches("FETCH [0-9:,]+ \\(ENVELOPE INTERNALDATE RFC822\\.SIZE FLAGS UID\\)")) {
                    assertThat(current).isNotNull();
                    if (rejectMetadata) { send(out, tag + " NO private-server-text\r\n"); continue; }
                    String set = command.split(" ")[1];
                    for (int i = 0; i < current.messages().size(); i++) {
                        if (inSet(i + 1, set)) {
                            var item = current.messages().get(i);
                            send(out, "* " + (i + 1) + " FETCH (ENVELOPE " + item.envelope()
                                    + " INTERNALDATE \"25-Oct-2026 12:00:00 +0000\" RFC822.SIZE " + item.declaredSize()
                                    + (omitMetadataFlags ? "" : malformedMetadataFlags
                                    || Long.valueOf(item.uid()).equals(malformedMetadataUid) ? " FLAGS NIL"
                                    : " FLAGS (" + (metadataFlags == null ? item.flags() : metadataFlags) + ")")
                                    + " UID " + item.uid() + ")\r\n");
                        }
                    }
                } else if (command.matches("FETCH [0-9]+ \\(RFC822\\.SIZE\\)")) {
                    assertThat(current).isNotNull();
                    int seq = Integer.parseInt(command.split(" ")[1]);
                    send(out, "* " + seq + " FETCH (RFC822.SIZE " + current.messages().get(seq - 1).declaredSize() + ")\r\n");
                } else if (command.matches("FETCH [0-9]+ \\(BODY\\.PEEK\\[\\]<[0-9]+\\.[0-9]+>\\)")) {
                    assertThat(current).isNotNull();
                    var match = Pattern.compile("FETCH ([0-9]+) \\(BODY\\.PEEK\\[\\]<([0-9]+)\\.([0-9]+)>\\)").matcher(command);
                    assertThat(match.matches()).isTrue();
                    int seq = Integer.parseInt(match.group(1)), offset = Integer.parseInt(match.group(2));
                    int length = Integer.parseInt(match.group(3));
                    var item = current.messages().get(seq - 1);
                    byte[] bytes = item.bytes();
                    int count = Math.min(length, Math.max(0, bytes.length - offset));
                    send(out, "* " + seq + " FETCH (BODY[]<" + offset + "> {" + count + "}\r\n");
                    if (count > 0) { out.write(bytes, offset, count); }
                    send(out, ")\r\n");
                } else if (command.equals("LOGOUT")) {
                    send(out, "* BYE shutdown\r\n" + tag + " OK logout\r\n"); break;
                } else if (!command.equals("NOOP")) {
                    throw new AssertionError("Unexpected synthetic command: " + command);
                }
                send(out, tag + " OK done\r\n");
            }
        } catch (IOException e) { if (!listener.isClosed()) { throw new RuntimeException(e); } }
    }

    private static boolean inSet(int sequence, String set) {
        return Arrays.stream(set.split(",")).anyMatch(part -> {
            String[] ends = part.split(":");
            int start = Integer.parseInt(ends[0]);
            return ends.length == 1 ? sequence == start : sequence >= start && sequence <= Integer.parseInt(ends[1]);
        });
    }

    int port() { return listener.getLocalPort(); }

    static void send(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII)); out.flush();
    }

    @Override public void close() throws Exception {
        listener.close();
        if (socket != null) { socket.close(); }
        try { task.get(6, TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
    }
}
