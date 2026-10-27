package mail.mcp.connections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The password of a generic credential in Windows Credential Manager ({@code windowsCredential},
 * docs/decisions.md, Д28), read with {@code CredReadW} through the Java foreign function API.
 *
 * <p>Like {@link PasswordCommand}, it is read on the first {@link #value()} call, right before sign-in,
 * and kept in memory for the life of this process; {@link #forget()} drops it after a rejected sign-in.
 * Credential Manager stores the password as UTF-16LE; trailing NUL characters are removed. A missing
 * credential, an empty password or a failed call is a {@link SecretUnavailableException}. Only
 * reading is implemented: the user adds the credential in Credential Manager.
 *
 * <p>Native access needs {@code Enable-Native-Access: ALL-UNNAMED} (set in the jar manifest) or
 * {@code --enable-native-access=ALL-UNNAMED}; without it Java only prints a warning.
 */
public final class WindowsCredential implements Secret {

    private static final Logger log = LoggerFactory.getLogger(WindowsCredential.class);

    static final int CRED_TYPE_GENERIC = 1;
    private static final int ERROR_NOT_FOUND = 1168;

    /** {@code CREDENTIALW} on 64-bit Windows. */
    static final StructLayout CREDENTIAL = MemoryLayout.structLayout(
            JAVA_INT.withName("Flags"),
            JAVA_INT.withName("Type"),
            ADDRESS.withName("TargetName"),
            ADDRESS.withName("Comment"),
            JAVA_INT.withName("LastWrittenLow"),
            JAVA_INT.withName("LastWrittenHigh"),
            JAVA_INT.withName("CredentialBlobSize"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("CredentialBlob"),
            JAVA_INT.withName("Persist"),
            JAVA_INT.withName("AttributeCount"),
            ADDRESS.withName("Attributes"),
            ADDRESS.withName("TargetAlias"),
            ADDRESS.withName("UserName"));
    static final long TYPE = CREDENTIAL.byteOffset(groupElement("Type"));
    static final long TARGET_NAME = CREDENTIAL.byteOffset(groupElement("TargetName"));
    static final long BLOB_SIZE = CREDENTIAL.byteOffset(groupElement("CredentialBlobSize"));
    static final long BLOB = CREDENTIAL.byteOffset(groupElement("CredentialBlob"));
    static final long PERSIST = CREDENTIAL.byteOffset(groupElement("Persist"));
    static final long USER_NAME = CREDENTIAL.byteOffset(groupElement("UserName"));

    private final String connection;
    private final String target;
    private String value;

    /**
     * @param connection connection name, for messages
     * @param target     target name of the generic credential, e.g. {@code mail-mcp/work}
     */
    public WindowsCredential(String connection, String target) {
        this.connection = connection;
        this.target = target;
    }

    /** True on 64-bit Windows, the only platform with Credential Manager and this structure layout. */
    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                && ADDRESS.byteSize() == 8;
    }

    @Override
    public synchronized String value() {
        if (value == null) {
            value = read();
        }
        return value;
    }

    @Override
    public synchronized void forget() {
        value = null;
    }

    private String read() {
        byte[] blob;
        try {
            blob = Native.read(target);
        } catch (Throwable e) {
            log.warn("Windows credential of connection '{}' could not be read: {}", connection, e.getClass().getSimpleName());
            throw new SecretUnavailableException(connection, "Windows credential could not be read");
        }
        if (blob == null) {
            throw new SecretUnavailableException(connection, "there is no generic Windows credential named '"
                    + target + "'; add it in Credential Manager");
        }
        try {
            return decode(blob);
        } finally {
            Arrays.fill(blob, (byte) 0);
        }
    }

    /** UTF-16LE without trailing NUL characters. */
    String decode(byte[] blob) {
        if (blob.length % 2 != 0) {
            throw new SecretUnavailableException(connection, "Windows credential '" + target
                    + "' does not hold a UTF-16 password");
        }
        String text = new String(blob, StandardCharsets.UTF_16LE);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\0') {
            end--;
        }
        if (end == 0) {
            throw new SecretUnavailableException(connection, "Windows credential '" + target + "' has an empty password");
        }
        return text.substring(0, end);
    }

    /** Names the target, never the value. */
    @Override
    public String toString() {
        return "WindowsCredential[connection=" + connection + ", target=" + target + "]";
    }

    /** Native handles, created on first use so other platforms never load {@code advapi32}. */
    static final class Native {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
        static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
        static final VarHandle LAST_ERROR = CALL_STATE.varHandle(groupElement("GetLastError"));
        private static final MethodHandle CRED_READ = LINKER.downcallHandle(ADVAPI32.findOrThrow("CredReadW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
                Linker.Option.captureCallState("GetLastError"));
        private static final MethodHandle CRED_FREE = LINKER.downcallHandle(ADVAPI32.findOrThrow("CredFree"),
                FunctionDescriptor.ofVoid(ADDRESS));

        private Native() { }

        /** @return the credential blob, or {@code null} when there is no such generic credential */
        static byte[] read(String target) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CALL_STATE);
                MemorySegment out = arena.allocate(ADDRESS);
                int ok = (int) CRED_READ.invokeExact(state, arena.allocateFrom(target, StandardCharsets.UTF_16LE),
                        CRED_TYPE_GENERIC, 0, out);
                if (ok == 0) {
                    int error = (int) LAST_ERROR.get(state, 0L);
                    if (error == ERROR_NOT_FOUND) {
                        return null;
                    }
                    throw new IllegalStateException("CredReadW failed with error " + error);
                }
                MemorySegment pointer = out.get(ADDRESS, 0);
                try {
                    MemorySegment credential = pointer.reinterpret(CREDENTIAL.byteSize());
                    int size = credential.get(JAVA_INT, BLOB_SIZE);
                    MemorySegment blob = credential.get(ADDRESS, BLOB);
                    if (size <= 0 || blob.equals(MemorySegment.NULL)) {
                        return new byte[0];
                    }
                    return blob.reinterpret(size).toArray(JAVA_BYTE);
                } finally {
                    CRED_FREE.invokeExact(pointer);
                }
            }
        }
    }
}
