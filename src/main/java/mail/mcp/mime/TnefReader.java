package mail.mcp.mime;

import mail.mcp.model.AttachmentInfo;
import org.apache.poi.hmef.HMEFMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Enumerates immediate TNEF children; POI eagerly loads attributes before output limits apply. */
final class TnefReader {
    private TnefReader() { }

    static boolean readAttachments(Path source, Path attachmentsDir, int firstNumber,
                                   int fileLimit, long byteLimit, List<AttachmentInfo> entries) throws IOException {
        if (fileLimit <= 0 || byteLimit <= 0) { return true; }
        try (var input = Files.newInputStream(source)) {
            var message = new HMEFMessage(input);
            for (var attachment : message.getAttachments()) {
                if (entries.size() >= fileLimit || byteLimit == 0) { return true; }
                String name = attachment.getLongFilename();
                if (name == null || name.isBlank()) { name = attachment.getFilename(); }
                if (name == null || name.isBlank()) { name = null; }
                if (name != null) { name = name.replace('\\', '/'); }
                byte[] data = attachment.getContents();
                if (data.length > byteLimit) { return true; }
                String file = (firstNumber + entries.size()) + "__" + (name == null ? "part"
                        : MimeReader.safeName(name.substring(name.lastIndexOf('/') + 1)));
                Path target = attachmentsDir == null ? null : attachmentsDir.resolve(file);
                boolean complete = false;
                try {
                    if (attachmentsDir != null) { Files.createDirectories(attachmentsDir); Files.write(target, data); }
                    entries.add(new AttachmentInfo(file, name, "application/octet-stream", data.length, null, false, null, null));
                    byteLimit -= data.length;
                    complete = true;
                } finally {
                    if (!complete && target != null) { Files.deleteIfExists(target); }
                }
            }
            return entries.size() >= fileLimit || byteLimit == 0;
        }
    }
}
