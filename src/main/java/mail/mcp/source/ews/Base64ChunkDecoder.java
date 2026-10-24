package mail.mcp.source.ews;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Base64;

/**
 * Decodes base64 text that arrives in arbitrary chunks (StAX text events) straight into an output
 * stream, so a large {@code MimeContent} never sits in memory as one string. Whitespace is skipped;
 * the decoded size is capped.
 */
final class Base64ChunkDecoder {

    // A multiple of 4, so every full buffer is a whole number of base64 quanta.
    private static final int BUFFER_CHARS = 64 * 1024;

    private final OutputStream out;
    private final long maxBytes;
    private final byte[] buffer = new byte[BUFFER_CHARS];
    private int used;
    private long written;

    Base64ChunkDecoder(OutputStream out, long maxBytes) {
        this.out = out;
        this.maxBytes = maxBytes;
    }

    void write(char[] chars, int start, int length) throws IOException {
        for (int i = start; i < start + length; i++) {
            char c = chars[i];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            if (c > 0x7F) {
                throw new EwsProtocolException("MimeContent contains a non-base64 character");
            }
            buffer[used++] = (byte) c;
            if (used == buffer.length) {
                flush(buffer);
                used = 0;
            }
        }
    }

    /** Decodes what is left. @return total decoded bytes */
    long finish() throws IOException {
        if (used % 4 != 0) {
            throw new EwsProtocolException("MimeContent is truncated: base64 length is not a multiple of 4");
        }
        if (used > 0) {
            flush(Arrays.copyOf(buffer, used));
            used = 0;
        }
        return written;
    }

    private void flush(byte[] quanta) throws IOException {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(quanta);
        } catch (IllegalArgumentException e) {
            throw new EwsProtocolException("MimeContent is not valid base64", e);
        }
        written += decoded.length;
        if (written > maxBytes) {
            throw new EwsProtocolException("MIME content is larger than the limit of " + maxBytes + " bytes");
        }
        out.write(decoded);
    }
}
