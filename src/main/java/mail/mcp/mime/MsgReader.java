package mail.mcp.mime;

import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.ParseException;
import mail.mcp.model.AttachmentInfo;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertyValue.LongPropertyValue;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentOutputStream;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.CodePageUtil;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Enumerates immediate MSG children; POI eagerly loads chunks before output limits apply. */
final class MsgReader {
    private MsgReader() { }

    static boolean readAttachments(Path source, Path attachmentsDir, int firstNumber,
                                   int fileLimit, long byteLimit, List<AttachmentInfo> entries) throws IOException {
        if (fileLimit <= 0 || byteLimit <= 0) { return true; }
        try (var fs = new POIFSFileSystem(source.toFile(), true); var message = new MAPIMessage(fs)) {
            String encoding = encoding(message);
            for (var attachment : message.getAttachmentFiles()) {
                if (entries.size() >= fileLimit || byteLimit == 0) { return true; }
                if (encoding != null) {
                    for (var chunk : attachment.getChunks()) {
                        if (chunk instanceof StringChunk string) { string.set7BitEncoding(encoding); }
                    }
                }
                String name = value(attachment.getAttachLongFileName());
                if (name == null) { name = value(attachment.getAttachFileName()); }
                if (name != null) { name = name.replace('\\', '/'); }
                boolean embedded = attachment.isEmbeddedMessage();
                byte[] data = null;
                if (embedded) {
                    if (name == null) { name = "embedded.msg"; }
                    else if (!name.toLowerCase(Locale.ROOT).endsWith(".msg")) { name += ".msg"; }
                } else {
                    if (attachment.getAttachData() == null) { continue; }
                    data = attachment.getAttachData().getValue();
                    if (data == null) { continue; }
                    if (data.length > byteLimit) { return true; }
                }
                String file = (firstNumber + entries.size()) + "__" + (name == null ? "part"
                        : MimeReader.safeName(name.substring(name.lastIndexOf('/') + 1)));
                Path target = attachmentsDir == null ? null : attachmentsDir.resolve(file);
                boolean complete = false;
                long size;
                try {
                    if (attachmentsDir != null) { Files.createDirectories(attachmentsDir); }
                    if (embedded) {
                        size = embedded(attachment.getAttachmentDirectory().getDirectory(), fs.getRoot(), target, byteLimit);
                        if (size < 0) { return true; }
                    } else {
                        size = data.length;
                        if (target != null) { Files.write(target, data); }
                    }
                    String type = "application/vnd.ms-outlook";
                    String charset = null;
                    if (!embedded) {
                        type = "application/octet-stream";
                        String declared = value(attachment.getAttachMimeTag());
                        if (declared != null) {
                            try {
                                var parsed = new ContentType(declared);
                                String base = parsed.getBaseType().toLowerCase(Locale.ROOT);
                                if (base.matches("[a-z0-9!#$%&'*+.^_`{|}~-]+/[a-z0-9!#$%&'*+.^_`{|}~-]+") && !base.contains("*")) {
                                    type = base;
                                    charset = parsed.getParameter("charset");
                                    if (charset != null) { charset = charset.toLowerCase(Locale.ROOT); }
                                }
                            } catch (ParseException e) { /* invalid declaration stays binary */ }
                        }
                    }
                    String cid = value(attachment.getAttachContentId());
                    if (cid != null && cid.startsWith("<") && cid.endsWith(">")) { cid = cid.substring(1, cid.length() - 1); }
                    entries.add(new AttachmentInfo(file, name, type, size, cid, false, charset, null));
                    byteLimit -= size;
                    complete = true;
                } finally {
                    if (!complete && target != null) { Files.deleteIfExists(target); }
                }
            }
            return entries.size() >= fileLimit || byteLimit == 0;
        }
    }

    private static String value(StringChunk chunk) {
        String value = chunk == null ? null : chunk.getValue();
        return value == null || value.isBlank() ? null : value;
    }

    private static String encoding(MAPIMessage message) throws IOException {
        for (var property : List.of(MAPIProperty.MESSAGE_CODEPAGE, MAPIProperty.INTERNET_CPID)) {
            var values = message.getMainChunks().getProperties().get(property);
            if (values != null && !values.isEmpty()) {
                return CodePageUtil.codepageToEncoding(((LongPropertyValue) values.getFirst()).getValue(), true);
            }
        }
        return null;
    }

    private static long embedded(DirectoryNode source, DirectoryNode root, Path target, long allowance) throws IOException {
        try (var child = new POIFSFileSystem()) {
            var destination = child.getRoot();
            destination.setStorageClsid(source.getStorageClsid());
            for (var entry : source) {
                if (entry.getName().equals("__properties_version1.0")) {
                    // MS-OXMSG 2.4.1: embedded header is 24 bytes; top-level adds 8 reserved bytes.
                    try (var input = source.createDocumentInputStream(entry.getName());
                         var output = new DocumentOutputStream(destination, entry.getName())) {
                        byte[] header = input.readNBytes(24);
                        if (header.length != 24) { throw new IOException("Invalid embedded property header"); }
                        output.write(header);
                        output.write(new byte[8]);
                        input.transferTo(output);
                    }
                } else { EntryUtils.copyNodeRecursively(entry, destination); }
            }
            // MS-OXMSG 2.2.3: the original root owns mappings for all embedded subobjects.
            if (root.hasEntry("__nameid_version1.0") && !destination.hasEntry("__nameid_version1.0")) {
                EntryUtils.copyNodeRecursively(root.getEntry("__nameid_version1.0"), destination);
            }
            long[] count = {0};
            boolean[] exceeded = {false};
            try (var output = new FilterOutputStream(target == null ? OutputStream.nullOutputStream() : Files.newOutputStream(target)) {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    if (length > allowance - count[0]) { exceeded[0] = true; throw new IOException("Extracted size limit"); }
                    out.write(data, offset, length);
                    count[0] += length;
                }
            }) {
                child.writeFilesystem(output);
            } catch (IOException e) {
                if (exceeded[0]) { return -1; }
                throw e;
            }
            return count[0];
        }
    }
}
