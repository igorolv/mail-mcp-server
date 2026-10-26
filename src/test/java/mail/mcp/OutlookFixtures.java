package mail.mcp;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Small invented compound files and checksum-valid TNEF; no external generators or samples. */
public final class OutlookFixtures {
    private OutlookFixtures() { }

    public record Child(String name, byte[] data) { }
    @FunctionalInterface public interface MessageWriter { void write(DirectoryEntry root) throws Exception; }

    public static byte[] msg(MessageWriter writer) throws Exception {
        try (var fs = new POIFSFileSystem(); var output = new ByteArrayOutputStream()) {
            writer.write(fs.getRoot());
            fs.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    public static byte[] msg(String subject, String body, Child... children) throws Exception {
        return msg(root -> {
            properties(root, false, children.length);
            if (subject != null) { string(root, 0x0037, subject); }
            if (body != null) { string(root, 0x1000, body); }
            for (int i = 0; i < children.length; i++) { attachment(root, i, children[i]); }
        });
    }

    public static void stream(DirectoryEntry root, String name, byte[] data) throws Exception {
        root.createDocument(name, new ByteArrayInputStream(data));
    }

    public static void string(DirectoryEntry root, int id, String value) throws Exception {
        stream(root, String.format("__substg1.0_%04X001F", id), (value + "\0").getBytes(StandardCharsets.UTF_16LE));
    }

    public static void properties(DirectoryEntry root, boolean embedded, int attachments, long... fixed) throws Exception {
        var data = ByteBuffer.allocate((embedded ? 24 : 32) + fixed.length / 2 * 16).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(12, attachments);
        data.putInt(20, attachments);
        data.position(embedded ? 24 : 32);
        for (int i = 0; i < fixed.length; i += 2) {
            data.putInt((int) fixed[i]).putInt(6).putLong(fixed[i + 1]);
        }
        stream(root, "__properties_version1.0", data.array());
    }

    public static DirectoryEntry attachment(DirectoryEntry root, int number, Child child) throws Exception {
        var entry = root.createDirectory(String.format("__attach_version1.0_#%08X", number));
        stream(entry, "__properties_version1.0", new byte[8]);
        if (child.name() != null) { string(entry, 0x3707, child.name()); }
        if (child.data() != null) { stream(entry, "__substg1.0_37010102", child.data()); }
        return entry;
    }

    public static byte[] embeddedMsg(String name) throws Exception {
        return msg(root -> {
            properties(root, false, 1);
            string(root, 0x0037, "Parent subject");
            var mapping = root.createDirectory("__nameid_version1.0");
            stream(mapping, "__substg1.0_00020102", new byte[0]);
            stream(mapping, "__substg1.0_00030102", new byte[]{0x34, 0x12, 0, 0, 2, 0, 0, 0});
            stream(mapping, "__substg1.0_00040102", new byte[0]);
            var attach = attachment(root, 0, new Child(name, null));
            var child = attach.createDirectory("__substg1.0_3701000D");
            properties(child, true, 1, 0x0E070003, 42, 0x80000003L, 123);
            string(child, 0x0037, "Встроенная тема");
            string(child, 0x1000, "Встроенное тело");
            attachment(child, 0, new Child("grandchild.txt", "grandchild bytes".getBytes(StandardCharsets.UTF_8)));
        });
    }

    private static void integer(ByteArrayOutputStream output, int value) { output.writeBytes(le(value)); }
    private static byte[] le(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }

    public static byte[] rtf(String text) {
        byte[] data = text.getBytes(StandardCharsets.US_ASCII);
        var output = new ByteArrayOutputStream();
        integer(output, data.length + 12);
        integer(output, data.length);
        integer(output, 0x414C454D); // MELA: uncompressed RTF, CRC is zero.
        integer(output, 0);
        output.writeBytes(data);
        return output.toByteArray();
    }

    private static void attribute(ByteArrayOutputStream output, int level, int tag, byte[] data) {
        output.write(level);
        integer(output, tag);
        integer(output, data.length);
        output.writeBytes(data);
        int checksum = 0;
        for (byte value : data) { checksum += value & 255; }
        output.write(checksum & 255);
        output.write((checksum >>> 8) & 255);
    }

    private static byte[] mapi(int tag, byte[] data) {
        var output = new ByteArrayOutputStream();
        integer(output, 1); // property count
        integer(output, tag);
        integer(output, 1); // value count
        integer(output, data.length);
        output.writeBytes(data);
        output.writeBytes(new byte[(4 - data.length % 4) % 4]);
        return output.toByteArray();
    }

    public static byte[] tnef(String subject, String bodyRtf, Child... children) {
        var output = new ByteArrayOutputStream();
        integer(output, 0x223e9f78);
        output.write(1); output.write(0);
        if (subject != null) {
            attribute(output, 1, 0x00069003, mapi(0x0070001F, (subject + "\0").getBytes(StandardCharsets.UTF_16LE)));
        }
        if (bodyRtf != null) { attribute(output, 1, 0x00069003, mapi(0x10090102, rtf(bodyRtf))); }
        for (var child : children) {
            attribute(output, 2, 0x00069002, new byte[14]);
            if (child.name() != null) {
                attribute(output, 2, 0x00069005, mapi(0x3707001F, (child.name() + "\0").getBytes(StandardCharsets.UTF_16LE)));
            }
            if (child.data() != null) { attribute(output, 2, 0x0006800F, child.data()); }
        }
        return output.toByteArray();
    }

    public static byte[] eml(String name, String type, byte[] data) {
        return ("Content-Type: " + type + "\r\nContent-Disposition: attachment"
                + (name == null ? "" : "; filename=\"" + name + "\"")
                + "\r\nContent-Transfer-Encoding: base64\r\n\r\n" + Base64.getEncoder().encodeToString(data))
                .getBytes(StandardCharsets.UTF_8);
    }

    public static Path fixture(String id) throws Exception {
        try (var files = Files.list(Path.of("testdata/mime"))) {
            return files.filter(p -> p.getFileName().toString().startsWith(id + "-"))
                    .filter(p -> p.toString().endsWith(".eml")).findFirst().orElseThrow();
        }
    }
}
