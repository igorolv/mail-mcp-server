package mail.mcp.connections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A password printed by a local program, such as a password manager's CLI ({@code passwordCommand},
 * docs/decisions.md, Д22).
 *
 * <p>The program runs on the first {@link #value()} call, right before sign-in, and its result stays in
 * memory for the life of this process, so frequent synchronizations do not unlock the password
 * manager each time. {@link #forget()} drops it after a rejected sign-in.
 *
 * <p>Rules: the arguments go to the program as given, without a shell. Standard output must be UTF-8
 * and hold exactly one non-empty line; one trailing line break is removed, other whitespace is kept.
 * A nonzero exit code, a timeout, empty or multi-line output are failures. In the server the program
 * gets no input (it has no terminal to ask in) and its error output goes to the log only; the
 * user-run {@code mail-sync} lets it use the terminal, so it may ask for a master password there.
 * Nothing the program prints is ever written to {@code System.out}, which carries JSON-RPC.
 */
public final class PasswordCommand implements Secret {

    private static final Logger log = LoggerFactory.getLogger(PasswordCommand.class);

    static final Duration SERVER_TIMEOUT = Duration.ofSeconds(60);
    static final Duration TERMINAL_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_LOGGED_ERROR_CHARS = 300;
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final String connection;
    private final List<String> command;
    private final boolean terminal;
    private final Duration timeout;
    private String value;

    /**
     * @param connection connection name, for messages
     * @param command    program and its arguments, placeholders already resolved
     * @param terminal   true in the user-run {@code mail-sync}: the program may use the terminal
     */
    public PasswordCommand(String connection, List<String> command, boolean terminal) {
        this(connection, command, terminal, terminal ? TERMINAL_TIMEOUT : SERVER_TIMEOUT);
    }

    PasswordCommand(String connection, List<String> command, boolean terminal, Duration timeout) {
        this.connection = connection;
        this.command = List.copyOf(command);
        this.terminal = terminal;
        this.timeout = timeout;
    }

    @Override
    public synchronized String value() {
        if (value == null) {
            value = run();
        }
        return value;
    }

    @Override
    public synchronized void forget() {
        value = null;
    }

    private String run() {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.PIPE);
        if (terminal) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
        } else {
            builder.redirectInput(ProcessBuilder.Redirect.PIPE).redirectError(ProcessBuilder.Redirect.PIPE);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            log.warn("Password command of connection '{}' could not start: {}", connection, e.getClass().getSimpleName());
            throw new SecretUnavailableException(connection, "password command could not start; check the program name and path");
        }
        CompletableFuture<byte[]> output = drain(process.getInputStream(), MAX_OUTPUT_BYTES + 1);
        CompletableFuture<byte[]> errors = terminal ? CompletableFuture.completedFuture(new byte[0])
                : drain(process.getErrorStream(), MAX_LOGGED_ERROR_CHARS * 4);
        if (!terminal) {
            try { process.getOutputStream().close(); } // end of input: a prompt fails instead of waiting
            catch (IOException ignored) { }
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new SecretUnavailableException(connection, "password command did not finish within " + timeout.toSeconds() + " s");
            }
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new SecretUnavailableException(connection, "password command was interrupted");
        }
        int exit = process.exitValue();
        byte[] bytes = collect(output, process);
        if (exit != 0) {
            log.warn("Password command of connection '{}' exited with code {}; error output: {}",
                    connection, exit, errorExcerpt(collect(errors, process)));
            throw new SecretUnavailableException(connection, "password command exited with code " + exit);
        }
        return parse(bytes);
    }

    /** One line of UTF-8: one trailing line break and a leading byte order mark are removed. */
    String parse(byte[] bytes) {
        if (bytes.length > MAX_OUTPUT_BYTES) {
            throw new SecretUnavailableException(connection, "password command printed more than " + MAX_OUTPUT_BYTES + " bytes");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new SecretUnavailableException(connection, "password command printed output that is not UTF-8");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (text.endsWith("\r\n")) {
            text = text.substring(0, text.length() - 2);
        } else if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            throw new SecretUnavailableException(connection, "password command printed nothing");
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new SecretUnavailableException(connection, "password command printed more than one line");
        }
        return text;
    }

    private static CompletableFuture<byte[]> drain(InputStream stream, int keep) {
        return CompletableFuture.supplyAsync(() -> {
            var kept = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try (stream) {
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    int room = keep - kept.size();
                    if (room > 0) {
                        kept.write(buffer, 0, Math.min(room, read));
                    } // keep reading past the limit so the program never blocks on a full pipe
                }
            } catch (IOException ignored) {
                // a killed program closes its pipe; what was read so far is enough
            }
            return kept.toByteArray();
        }, Thread::startVirtualThread);
    }

    /** A grandchild that still holds the pipe must not block sign-in forever. */
    private byte[] collect(CompletableFuture<byte[]> stream, Process process) {
        try {
            return stream.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            kill(process);
            throw new SecretUnavailableException(connection, "password command left its output open after exiting");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretUnavailableException(connection, "password command was interrupted");
        } catch (ExecutionException e) {
            throw new SecretUnavailableException(connection, "password command output could not be read");
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String errorExcerpt(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return "none";
        }
        return text.length() > MAX_LOGGED_ERROR_CHARS ? text.substring(0, MAX_LOGGED_ERROR_CHARS) + "..." : text;
    }

    /** Neither the value nor the command line: arguments may name vault items. */
    @Override
    public String toString() {
        return "PasswordCommand[connection=" + connection + "]";
    }
}
