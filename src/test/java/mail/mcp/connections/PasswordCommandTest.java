package mail.mcp.connections;

import mail.mcp.sync.SyncErrors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PasswordCommand} against a small Java program started with the test JVM's own launcher, so the
 * tests need no shell, no password manager and no network.
 */
class PasswordCommandTest {

    private static final String FIXTURE = """
            import java.nio.file.*;
            import java.util.HexFormat;

            public class Fixture {
                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "print" -> { System.out.write(HexFormat.of().parseHex(args[1])); System.out.flush(); }
                        case "fail" -> { System.err.print("vault is locked"); System.exit(3); }
                        case "sleep" -> Thread.sleep(120_000);
                        case "input" -> System.out.print(System.in.read() < 0 ? "no-input" : "input");
                        case "count" -> {
                            Files.writeString(Path.of(args[1]), "x", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            System.out.print("counted-secret");
                        }
                        default -> System.exit(64);
                    }
                }
            }
            """;

    @TempDir
    static Path fixtureDir;

    @TempDir
    Path temp;

    private static Path fixture;

    @BeforeAll
    static void writeFixture() throws IOException {
        fixture = Files.writeString(fixtureDir.resolve("Fixture.java"), FIXTURE);
    }

    private static List<String> fixture(String... args) {
        var command = new ArrayList<String>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add(fixture.toString());
        command.addAll(List.of(args));
        return command;
    }

    private static String hex(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void printsOneLineWithoutItsLineBreak() {
        var secret = new PasswordCommand("box", fixture("print", hex(" pässwörd \r\n")), false);

        assertThat(secret.value()).isEqualTo(" pässwörd ");
    }

    @Test
    void runsOnceUntilForgotten() throws IOException {
        Path calls = temp.resolve("calls.txt");
        var secret = new PasswordCommand("box", fixture("count", calls.toString()), false);

        assertThat(secret.value()).isEqualTo("counted-secret");
        assertThat(secret.value()).isEqualTo("counted-secret");
        assertThat(Files.readString(calls)).isEqualTo("x");

        secret.forget();
        assertThat(secret.value()).isEqualTo("counted-secret");
        assertThat(Files.readString(calls)).isEqualTo("xx");
    }

    @Test
    void serverModeGivesTheProgramNoInput() {
        assertThat(new PasswordCommand("box", fixture("input"), false).value()).isEqualTo("no-input");
    }

    @Test
    void nonzeroExitFailsWithoutTheErrorOutput() {
        assertThatThrownBy(() -> new PasswordCommand("box", fixture("fail"), false).value())
                .isInstanceOf(SecretUnavailableException.class)
                .hasMessageContaining("connection 'box' is unavailable: password command exited with code 3")
                .hasMessageContaining("no sign-in was attempted")
                .hasMessageNotContaining("vault is locked");
    }

    @Test
    void slowProgramIsStopped() {
        var secret = new PasswordCommand("box", fixture("sleep"), false, Duration.ofSeconds(3));

        assertThatThrownBy(secret::value)
                .isInstanceOf(SecretUnavailableException.class)
                .hasMessageContaining("did not finish within 3 s");
    }

    @Test
    void missingProgramFails() {
        assertThatThrownBy(() -> new PasswordCommand("box", List.of("no-such-program-for-mail-mcp-tests"), false).value())
                .isInstanceOf(SecretUnavailableException.class)
                .hasMessageContaining("could not start");
    }

    @Test
    void outputRules() {
        var secret = new PasswordCommand("box", List.of("unused"), false);

        assertThat(secret.parse("secret\n".getBytes(StandardCharsets.UTF_8))).isEqualTo("secret");
        assertThat(secret.parse("﻿secret".getBytes(StandardCharsets.UTF_8))).isEqualTo("secret");
        assertThat(secret.parse("secret \t".getBytes(StandardCharsets.UTF_8))).isEqualTo("secret \t");
        assertThatThrownBy(() -> secret.parse(new byte[0])).hasMessageContaining("printed nothing");
        assertThatThrownBy(() -> secret.parse("\n".getBytes(StandardCharsets.UTF_8))).hasMessageContaining("printed nothing");
        assertThatThrownBy(() -> secret.parse("user\nsecret\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("more than one line");
        assertThatThrownBy(() -> secret.parse("secret\n\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("more than one line");
        assertThatThrownBy(() -> secret.parse(new byte[] {(byte) 0xC3, (byte) 0x28}))
                .hasMessageContaining("not UTF-8");
        assertThatThrownBy(() -> secret.parse(new byte[64 * 1024 + 1])).hasMessageContaining("more than");
    }

    @Test
    void neverPrintsTheCommandOrTheValue() {
        var secret = new PasswordCommand("box", fixture("print", hex("synthetic-value")), false);
        secret.value();

        assertThat(secret.toString()).isEqualTo("PasswordCommand[connection=box]");
        assertThat(Secret.of("synthetic-value").toString()).isEqualTo("***");
    }

    @Test
    void failureIsNotARejectedSignIn() {
        var failure = new SecretUnavailableException("box", "exited with code 1");

        assertThat(SyncErrors.signInRejected(failure)).isFalse();
        assertThat(SyncErrors.describe(failure)).isEqualTo(failure.getMessage());
    }
}
