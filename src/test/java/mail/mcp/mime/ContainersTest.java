package mail.mcp.mime;

import mail.mcp.model.MailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ContainersTest {
    @TempDir Path temp;

    private Path attachments() { return temp.resolve("attachments"); }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private record Part(String name, String type, byte[] bytes) { }

    private MailMessage message(Part... parts) throws Exception {
        var mime = new StringBuilder("MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n");
        for (var part : parts) {
            mime.append("--parts\r\nContent-Type: ").append(part.type())
                    .append("\r\nContent-Disposition: attachment; filename=\"").append(part.name())
                    .append('"');
            if (part.type().equals("message/rfc822")) {
                mime.append("\r\n\r\n").append(new String(part.bytes(), StandardCharsets.UTF_8));
            } else {
                mime.append("\r\nContent-Transfer-Encoding: base64\r\n\r\n")
                        .append(Base64.getEncoder().encodeToString(part.bytes()));
            }
            mime.append("\r\n");
        }
        mime.append("--parts--\r\n");
        Path eml = temp.resolve("message.eml");
        Files.writeString(eml, mime);
        return MimeReader.read(eml, attachments()).message();
    }

    private static byte[] zip(Charset charset, Map<String, byte[]> entries, boolean stored) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, charset)) {
            for (var item : entries.entrySet()) {
                var entry = new ZipEntry(item.getKey());
                if (stored) {
                    var crc = new CRC32();
                    crc.update(item.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(item.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        return zip(StandardCharsets.UTF_8, entries, false);
    }

    private static Map<String, byte[]> entries(String... names) {
        var entries = new LinkedHashMap<String, byte[]>();
        for (var name : names) { entries.put(name, bytes(name)); }
        return entries;
    }

    private static Path fixture(String id) throws Exception {
        try (var paths = Files.list(Path.of("testdata/mime"))) {
            return paths.filter(p -> p.getFileName().toString().startsWith(id + "-"))
                    .filter(p -> p.toString().endsWith(".eml")).findFirst().orElseThrow();
        }
    }

    @Test void zipEntriesAreAppendedWithParent() throws Exception {
        var entries = entries("folder/", "first.txt", "..\\folder\\second?.txt");
        var original = message(new Part("archive.zip", "application/zip", zip(entries)),
                new Part("last.txt", "text/plain", bytes("MIME attachment")));
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).hasSize(4);
        assertThat(expanded.attachments().subList(0, 2)).isEqualTo(original.attachments());
        assertThat(expanded.attachments()).extracting(a -> a.file())
                .containsExactly("1__archive.zip", "2__last.txt", "3__first.txt", "4__second_.txt");
        assertThat(expanded.attachments()).extracting(a -> a.parent())
                .containsExactly(null, null, "1__archive.zip", "1__archive.zip");
        var last = expanded.attachments().getLast();
        assertThat(last.name()).isEqualTo("../folder/second?.txt");
        assertThat(last.contentType()).isEqualTo("application/octet-stream");
        assertThat(last.contentId()).isNull();
        assertThat(last.charset()).isNull();
        assertThat(last.inline()).isFalse();
        assertThat(last.size()).isEqualTo(entries.get("..\\folder\\second?.txt").length);
        assertThat(Files.readAllBytes(attachments().resolve(last.file())))
                .isEqualTo(entries.get("..\\folder\\second?.txt"));
        assertThat(Files.readAllBytes(attachments().resolve("3__first.txt"))).isEqualTo(entries.get("first.txt"));
        try (var paths = Files.list(attachments())) { assertThat(paths.toList()).allMatch(Files::isRegularFile); }
    }

    @Test void cp866EntryNamesAreDecoded() throws Exception {
        var original = message(new Part("legacy.zip", "application/octet-stream",
                zip(Charset.forName("IBM866"), entries("папка/отчёт.txt"), false)),
                new Part("utf8.zip", "application/zip", zip(entries("данные.txt"))));
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(a -> a.name())
                .containsExactly("legacy.zip", "utf8.zip", "папка/отчёт.txt", "данные.txt");
        assertThat(attachments().resolve("3__отчёт.txt")).hasBinaryContent(bytes("папка/отчёт.txt"));
    }

    @Test void nestedMessageAttachmentsAreListed() throws Exception {
        var original = MimeReader.read(fixture("M09"), attachments()).message();
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(a -> a.file())
                .containsExactly("1__forwarded.eml", "2__nested.msg");
        assertThat(expanded.attachments()).extracting(a -> a.parent()).containsExactly(null, "1__forwarded.eml");
        assertThat(attachments().resolve("2__nested.msg")).isRegularFile();
    }

    @Test void depthStopsBeforeLevelSix() throws Exception {
        var original = MimeReader.read(fixture("M19"), attachments()).message();
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).hasSize(6);
        for (int i = 1; i <= 6; i++) {
            var attachment = expanded.attachments().get(i - 1);
            assertThat(attachment.file()).isEqualTo(i + "__level-" + i + ".eml");
            assertThat(attachment.parent()).isEqualTo(i == 1 ? null : (i - 1) + "__level-" + (i - 1) + ".eml");
        }
        byte[] chain = zip(Map.of("unopened.txt", bytes("beyond depth")));
        for (int i = 6; i >= 2; i--) { chain = zip(Map.of("level-" + i + ".zip", chain)); }
        original = message(new Part("level-1.zip", "application/zip", chain));
        expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).hasSize(6);
        for (int i = 1; i <= 6; i++) {
            var attachment = expanded.attachments().get(i - 1);
            assertThat(attachment.file()).isEqualTo(i + "__level-" + i + ".zip");
            assertThat(attachment.parent()).isEqualTo(i == 1 ? null : (i - 1) + "__level-" + (i - 1) + ".zip");
        }
    }

    @Test void oversizedEntryIsSkipped() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("large.txt", new byte[20 * 1024 * 1024 + 1]);
        entries.put("next.txt", bytes("kept"));
        var original = message(new Part("archive.zip", "application/zip", zip(entries)));
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(a -> a.file()).containsExactly("1__archive.zip", "2__next.txt");
        assertThat(attachments().resolve("2__large.txt")).doesNotExist();
        assertThat(attachments().resolve("2__next.txt")).hasBinaryContent(bytes("kept"));
    }

    @Test void messageLimitsStopExpansion() throws Exception {
        var original = message(new Part("first.zip", "application/zip", zip(entries("aaaa", "bbbb", "cccc"))),
                new Part("later.zip", "application/zip", zip(entries("later"))));
        var fileLimited = Containers.expand(original, attachments(), true, 2, 100);
        assertThat(fileLimited.attachments()).extracting(a -> a.file())
                .containsExactly("1__first.zip", "2__later.zip", "3__aaaa", "4__bbbb");
        assertThat(attachments().resolve("5__cccc")).doesNotExist();
        assertThat(Containers.expand(original, attachments(), false, 2, 100)).isEqualTo(fileLimited);
        var byteLimited = Containers.expand(original, attachments(), true, 100, 6);
        assertThat(byteLimited.attachments()).extracting(a -> a.file())
                .containsExactly("1__first.zip", "2__later.zip", "3__aaaa");
        assertThat(attachments().resolve("4__bbbb")).doesNotExist();
        assertThat(Containers.expand(original, attachments(), false, 100, 6)).isEqualTo(byteLimited);
        assertThat(Containers.expand(original, attachments(), true, 100, 4).attachments())
                .isEqualTo(byteLimited.attachments());

        byte[] nested = bytes("Content-Type: multipart/mixed; boundary=n\r\n\r\n"
                + "--n\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=a.txt\r\n\r\naaaa\r\n"
                + "--n\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=b.txt\r\n\r\nbbbb\r\n--n--\r\n");
        original = message(new Part("nested.eml", "message/rfc822", nested),
                new Part("later.zip", "application/zip", zip(entries("later"))));
        for (int limit : new int[]{1, 100}) {
            var limited = Containers.expand(original, attachments(), true, limit, 6);
            assertThat(limited.attachments()).extracting(a -> a.file())
                    .containsExactly("1__nested.eml", "2__later.zip", "3__a.txt");
            assertThat(attachments().resolve("4__b.txt")).doesNotExist();
            assertThat(Containers.expand(original, attachments(), false, limit, 6)).isEqualTo(limited);
        }
    }

    @Test void brokenArchiveKeepsEarlierEntries() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("first.txt", bytes("kept"));
        entries.put("broken.txt", bytes("cut in the middle"));
        byte[] archive = zip(StandardCharsets.UTF_8, entries, true);
        int secondHeader = 30 + "first.txt".length() + 4;
        archive = Arrays.copyOf(archive, secondHeader + 30 + "broken.txt".length() + 3);
        var original = message(new Part("broken.zip", "application/zip", archive),
                new Part("later.zip", "application/zip", zip(entries("later.txt"))));
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(a -> a.file())
                .containsExactly("1__broken.zip", "2__later.zip", "3__first.txt", "4__later.txt");
        assertThat(attachments().resolve("3__first.txt")).hasBinaryContent(bytes("kept"));
        assertThat(attachments().resolve("4__broken.txt")).doesNotExist();
        assertThat(Containers.expand(original, attachments(), false)).isEqualTo(expanded);
    }

    @Test void nestedFileLimitStopsBeforeLaterAttachmentsAreDecoded() throws Exception {
        byte[] nested = bytes("Content-Type: multipart/mixed; boundary=n\r\n\r\n"
                + "--n\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=kept.txt\r\n\r\nkept\r\n"
                + "--n\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=bad.bin\r\n"
                + "Content-Transfer-Encoding: unsupported-encoding\r\n\r\nunreadable\r\n--n--\r\n");
        var original = message(new Part("nested.eml", "message/rfc822", nested),
                new Part("later.zip", "application/zip", zip(entries("later.txt"))));
        // Without the limit the second leaf fails, so this also verifies that the fixture is effective.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MimeReader.read(
                attachments().resolve("1__nested.eml"), null)).isInstanceOf(java.io.IOException.class);
        for (boolean save : new boolean[]{false, true}) {
            var before = snapshot();
            var expanded = Containers.expand(original, attachments(), save, 1, 100);
            assertThat(expanded.attachments()).extracting(a -> a.file())
                    .containsExactly("1__nested.eml", "2__later.zip", "3__kept.txt");
            if (!save) { assertThat(snapshot()).isEqualTo(before); }
        }
        assertThat(attachments().resolve("3__kept.txt")).hasBinaryContent(bytes("kept"));
        assertThat(attachments().resolve("4__bad.bin")).doesNotExist();
        assertThat(attachments().resolve("4__later.txt")).doesNotExist();
    }

    @Test void nestedByteLimitStopsWhileDecodingAttachment() throws Exception {
        byte[] nested = bytes("Content-Type: multipart/mixed; boundary=n\r\n\r\n"
                + "--n\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=kept.txt\r\n\r\nkept\r\n"
                + "--n\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=large.bin\r\n"
                + "Content-Transfer-Encoding: x-uuencode\r\n\r\nbegin 644 payload\r\nM" + "`".repeat(60)
                + "\r\n!\r\nend\r\n--n--\r\n");
        var original = message(new Part("nested.eml", "message/rfc822", nested),
                new Part("later.zip", "application/zip", zip(entries("later.txt"))));
        // The first UU line decodes to 45 bytes; reading the malformed next line must fail.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MimeReader.read(
                attachments().resolve("1__nested.eml"), null)).isInstanceOf(java.io.IOException.class);
        for (boolean save : new boolean[]{false, true}) {
            var before = snapshot();
            var expanded = Containers.expand(original, attachments(), save, 100, 6);
            assertThat(expanded.attachments()).extracting(a -> a.file())
                    .containsExactly("1__nested.eml", "2__later.zip", "3__kept.txt");
            if (!save) { assertThat(snapshot()).isEqualTo(before); }
        }
        assertThat(attachments().resolve("3__kept.txt")).hasBinaryContent(bytes("kept"));
        assertThat(attachments().resolve("4__large.bin")).doesNotExist();
        assertThat(attachments().resolve("4__later.txt")).doesNotExist();
    }

    @Test void brokenNestedMessageKeepsEarlierEntries() throws Exception {
        byte[] nested = bytes("Content-Type: multipart/mixed; boundary=n\r\n\r\n"
                + "--n\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=kept.txt\r\n\r\nkept\r\n"
                + "--n\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=bad.bin\r\n"
                + "Content-Transfer-Encoding: unsupported-encoding\r\n\r\nunreadable\r\n--n--\r\n");
        var original = message(new Part("nested.eml", "message/rfc822", nested),
                new Part("later.zip", "application/zip", zip(entries("later.txt"))));
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(a -> a.file())
                .containsExactly("1__nested.eml", "2__later.zip", "3__kept.txt", "4__later.txt");
        assertThat(attachments().resolve("3__kept.txt")).hasBinaryContent(bytes("kept"));
        assertThat(attachments().resolve("4__bad.bin")).doesNotExist();
        var before = snapshot();
        assertThat(Containers.expand(original, attachments(), false)).isEqualTo(expanded);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void readingWithoutSavingGivesTheSameList() throws Exception {
        byte[] nested = zip(Map.of("inside.txt", bytes("nested content")));
        var original = message(new Part("outer.zip", "application/zip", zip(Map.of("inner.zip", nested))),
                new Part("forwarded.eml", "message/rfc822", Files.readAllBytes(fixture("M09"))));
        var before = snapshot();
        var unsaved = Containers.expand(original, attachments(), false);
        assertThat(unsaved.attachments()).hasSize(4);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(attachments().resolve("3__inner.zip")).doesNotExist();
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(a -> a.file()).containsExactly("1__outer.zip",
                "2__forwarded.eml", "3__inner.zip", "4__forwarded.eml", "5__inside.txt", "6__nested.msg");
        assertThat(expanded.attachments()).extracting(a -> a.parent())
                .containsExactly(null, null, "1__outer.zip", "2__forwarded.eml", "3__inner.zip", "4__forwarded.eml");
        before = snapshot();
        assertThat(Containers.expand(original, attachments(), false)).isEqualTo(expanded);
        assertThat(snapshot()).isEqualTo(before);
        Files.delete(attachments().resolve("1__outer.zip"));
        before = snapshot();
        assertThat(Containers.expand(original, attachments(), false).attachments()).extracting(a -> a.name())
                .doesNotContain("inner.zip", "inside.txt");
        assertThat(snapshot()).isEqualTo(before);
    }

    private Map<Path, String> snapshot() throws Exception {
        var result = new LinkedHashMap<Path, String>();
        try (var files = Files.walk(temp)) {
            for (var file : files.sorted().toList()) {
                result.put(file, (Files.isRegularFile(file) ? Base64.getEncoder().encodeToString(Files.readAllBytes(file)) : "dir")
                        + Files.getLastModifiedTime(file));
            }
        }
        return result;
    }
}
