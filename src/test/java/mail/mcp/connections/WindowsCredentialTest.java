package mail.mcp.connections;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link WindowsCredential} against the real Credential Manager of the test account. Each test writes
 * a synthetic generic credential that lives only for the logon session and deletes it at once.
 * Skipped on other platforms.
 */
class WindowsCredentialTest {

    private static final int CRED_PERSIST_SESSION = 1;

    private static String target() {
        return "mail-mcp-test/" + UUID.randomUUID();
    }

    @Test
    void readsAGenericCredentialOnceUntilForgotten() throws Throwable {
        assumeTrue(WindowsCredential.supported());
        String target = target();
        write(target, "synthetic-ä-value");
        try {
            var secret = new WindowsCredential("box", target);
            assertThat(secret.value()).isEqualTo("synthetic-ä-value");

            write(target, "changed-synthetic-value");
            assertThat(secret.value()).as("kept in memory").isEqualTo("synthetic-ä-value");
            secret.forget();
            assertThat(secret.value()).isEqualTo("changed-synthetic-value");
        } finally {
            delete(target);
        }
    }

    @Test
    void missingCredentialIsNotASignInAttempt() {
        assumeTrue(WindowsCredential.supported());
        String target = target();

        assertThatThrownBy(() -> new WindowsCredential("box", target).value())
                .isInstanceOf(SecretUnavailableException.class)
                .hasMessageContaining("connection 'box' is unavailable")
                .hasMessageContaining("no generic Windows credential named '" + target + "'")
                .hasMessageContaining("no sign-in was attempted");
    }

    @Test
    void emptyPasswordFails() throws Throwable {
        assumeTrue(WindowsCredential.supported());
        String target = target();
        write(target, "");
        try {
            assertThatThrownBy(() -> new WindowsCredential("box", target).value())
                    .isInstanceOf(SecretUnavailableException.class)
                    .hasMessageContaining("empty password");
        } finally {
            delete(target);
        }
    }

    @Test
    void decodingRules() {
        var secret = new WindowsCredential("box", "mail-mcp/box");

        assertThat(secret.decode("pass word\0".getBytes(StandardCharsets.UTF_16LE))).isEqualTo("pass word");
        assertThatThrownBy(() -> secret.decode(new byte[] {'a', 'b', 'c'})).hasMessageContaining("UTF-16");
        assertThatThrownBy(() -> secret.decode(new byte[0])).hasMessageContaining("empty password");
        assertThat(secret.toString()).doesNotContain("pass word");
    }

    // --- synthetic credentials: test-only writes ---------------------------------------------------

    private static void write(String target, String password) throws Throwable {
        MethodHandle credWrite = WindowsCredential.Native.LINKER.downcallHandle(
                WindowsCredential.Native.ADVAPI32.findOrThrow("CredWriteW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            byte[] blob = password.getBytes(StandardCharsets.UTF_16LE);
            MemorySegment credential = arena.allocate(WindowsCredential.CREDENTIAL);
            credential.set(JAVA_INT, WindowsCredential.TYPE, WindowsCredential.CRED_TYPE_GENERIC);
            credential.set(ADDRESS, WindowsCredential.TARGET_NAME, arena.allocateFrom(target, StandardCharsets.UTF_16LE));
            credential.set(JAVA_INT, WindowsCredential.BLOB_SIZE, blob.length);
            credential.set(ADDRESS, WindowsCredential.BLOB, blob.length == 0 ? MemorySegment.NULL
                    : arena.allocate(blob.length).copyFrom(MemorySegment.ofArray(blob)));
            credential.set(JAVA_INT, WindowsCredential.PERSIST, CRED_PERSIST_SESSION);
            credential.set(ADDRESS, WindowsCredential.USER_NAME, arena.allocateFrom("test", StandardCharsets.UTF_16LE));
            assertThat((int) credWrite.invokeExact(credential, 0)).as("CredWriteW").isNotZero();
        }
    }

    private static void delete(String target) throws Throwable {
        MethodHandle credDelete = WindowsCredential.Native.LINKER.downcallHandle(
                WindowsCredential.Native.ADVAPI32.findOrThrow("CredDeleteW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            int ignored = (int) credDelete.invokeExact(arena.allocateFrom(target, StandardCharsets.UTF_16LE),
                    WindowsCredential.CRED_TYPE_GENERIC, 0);
        }
    }
}
