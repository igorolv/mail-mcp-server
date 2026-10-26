package mail.mcp.mime;

import mail.mcp.model.MailMessage;
import mail.mcp.model.AttachmentInfo;
import mail.mcp.OutlookFixtures;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertyValue.LongPropertyValue;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
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
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static mail.mcp.OutlookFixtures.*;

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
                .containsExactly("1__forwarded.eml", "2__nested.msg", "3__package.zip", "4__layout.txt");
        assertThat(expanded.attachments()).extracting(a -> a.parent())
                .containsExactly(null, "1__forwarded.eml", "2__nested.msg", "3__package.zip");
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
                "2__forwarded.eml", "3__inner.zip", "4__forwarded.eml", "5__inside.txt", "6__nested.msg",
                "7__package.zip", "8__layout.txt");
        assertThat(expanded.attachments()).extracting(a -> a.parent())
                .containsExactly(null, null, "1__outer.zip", "2__forwarded.eml", "3__inner.zip", "4__forwarded.eml",
                        "6__nested.msg", "7__package.zip");
        before = snapshot();
        assertThat(Containers.expand(original, attachments(), false)).isEqualTo(expanded);
        assertThat(snapshot()).isEqualTo(before);
        Files.delete(attachments().resolve("1__outer.zip"));
        before = snapshot();
        assertThat(Containers.expand(original, attachments(), false).attachments()).extracting(a -> a.name())
                .doesNotContain("inner.zip", "inside.txt");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void msgChainExtractsZipAndDocument() throws Exception {
        var original = MimeReader.read(fixture("M09"), attachments()).message();
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(AttachmentInfo::name)
                .containsExactly("forwarded.eml", "nested.msg", "package.zip", "layout.txt");
        assertThat(expanded.attachments()).extracting(AttachmentInfo::parent)
                .containsExactly(null, "1__forwarded.eml", "2__nested.msg", "3__package.zip");
        assertThat(expanded.attachments()).extracting(AttachmentInfo::size).containsExactly(6276L, 4096L, 172L, 52L);
        assertThat(hash(attachments().resolve("3__package.zip")))
                .isEqualTo("a6e020b0a188a5e4211b918b78d9f2b44741fe953ddfbd3fc3cd36b11b658409");
        assertThat(hash(attachments().resolve("4__layout.txt")))
                .isEqualTo("8249de765f1e2e4c18c6de95a1d116814743abd6a4936678b02b3eb1c2e6a2b3");
    }

    @Test void tnefExtractsUnicodeAttachment() throws Exception {
        var original = MimeReader.read(fixture("M10"), attachments()).message();
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).hasSize(2);
        var child = expanded.attachments().getLast();
        assertThat(child.name()).isEqualTo("Длинное имя вложения для учебного письма.txt");
        assertThat(child.file()).isEqualTo("2__" + child.name());
        assertThat(child.parent()).isEqualTo("1__winmail.dat");
        assertThat(child.size()).isEqualTo(42);
        assertThat(child.contentType()).isEqualTo("application/octet-stream");
        assertThat(hash(attachments().resolve(child.file())))
                .isEqualTo("fbc28a0427b821c53873b3d386a14cf0e03f6f6e94aabccb1955ee139c41b96a");
    }

    private MailMessage single(byte[] data, String name, String type) throws Exception {
        Files.createDirectories(attachments());
        Files.write(attachments().resolve("1__container"), data);
        return new MailMessage(null, List.of(), List.of(), List.of(), null, null, null, List.of(),
                List.of(new AttachmentInfo("1__container", name, type, data.length, null, false, null, null)));
    }

    @Test void outlookContainersAreDetectedByTypeAndName() throws Exception {
        var child = new Child("child.txt", bytes("payload"));
        for (String name : new String[]{"item.msg", "C:\\folder\\ITEM.MSG", "item.txt", null}) {
            var original = single(msg("subject", "body", child), name,
                    name == null || name.endsWith("txt") ? "APPLICATION/VND.MS-OUTLOOK" : "application/octet-stream");
            assertThat(Containers.expand(original, attachments(), false).attachments()).hasSize(2);
        }
        for (String name : new String[]{"file.TNEF", "C:\\folder\\WINMAIL.DAT", "winmail.dat", "file.txt", null}) {
            var original = single(tnef(null, null, child), name,
                    name == null ? "application/vnd.ms-tnef" : name.endsWith("txt")
                            ? "APPLICATION/MS-TNEF" : "application/octet-stream");
            assertThat(Containers.expand(original, attachments(), false).attachments()).hasSize(2);
        }
        var opaque = single(tnef(null, null, child), "unrelated.dat", "application/octet-stream");
        assertThat(Containers.expand(opaque, attachments(), true)).isEqualTo(opaque);
        // EML then ZIP keep priority over conflicting MSG/TNEF indications.
        var eml = single(OutlookFixtures.eml("child.txt", "text/plain", bytes("text")), "file.MSG", "MESSAGE/RFC822");
        assertThat(Containers.expand(eml, attachments(), false).attachments()).hasSize(2);
        var archive = single(zip(entries("child.txt")), "file.TNEF", "APPLICATION/ZIP");
        assertThat(Containers.expand(archive, attachments(), false).attachments()).hasSize(2);
    }

    @Test void embeddedMsgIsSavedAsReadableMessage() throws Exception {
        byte[] data = embeddedMsg("nested");
        var original = single(data, "parent.msg", "application/vnd.ms-outlook");
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(AttachmentInfo::file)
                .containsExactly("1__container", "2__nested.msg", "3__grandchild.txt");
        assertThat(expanded.attachments()).extracting(AttachmentInfo::parent)
                .containsExactly(null, "1__container", "2__nested.msg");
        var child = attachments().resolve("2__nested.msg");
        assertThat(Files.size(child)).isEqualTo(expanded.attachments().get(1).size());
        try (var fs = new POIFSFileSystem(child.toFile(), true); var message = new MAPIMessage(fs)) {
            assertThat(message.getSubject()).isEqualTo("Встроенная тема");
            assertThat(message.getTextBody()).isEqualTo("Встроенное тело");
            var flags = (LongPropertyValue) message.getMainChunks().getProperties().get(MAPIProperty.MESSAGE_FLAGS).getFirst();
            assertThat(flags.getValue()).isEqualTo(42);
            try (var props = fs.createDocumentInputStream("__properties_version1.0")) {
                byte[] values = props.readAllBytes();
                assertThat(values).hasSize(64);
                assertThat(Arrays.copyOfRange(values, 24, 32)).containsOnly((byte) 0);
            }
            var mapping = (org.apache.poi.poifs.filesystem.DirectoryNode) fs.getRoot().getEntry("__nameid_version1.0");
            try (var input = mapping.createDocumentInputStream("__substg1.0_00030102")) {
                assertThat(input.readAllBytes()).containsExactly(0x34, 0x12, 0, 0, 2, 0, 0, 0);
            }
        }
        assertThat(attachments().resolve("1__container")).hasBinaryContent(data);
        assertThat(attachments().resolve("3__grandchild.txt")).hasBinaryContent(bytes("grandchild bytes"));
    }

    @Test void outlookEntryNamesStayInsideAttachmentsDirectory() throws Exception {
        var children = new Child[]{new Child("../outside.txt", bytes("a")), new Child("C:\\folder\\same.txt", bytes("b")),
                new Child("same.txt", bytes("c")), new Child(null, new byte[0]), new Child(" ", bytes("e"))};
        var original = single(msg(root -> {
            properties(root, false, 6);
            for (int i = 0; i < children.length; i++) { attachment(root, i, children[i]); }
            var reference = attachment(root, 5, new Child("unsupported.txt", null));
            string(reference, 0x370D, "C:\\must-not-be-opened.txt");
            string(reference, 0x370E, "https://example.com/must-not-be-fetched");
            var first = (org.apache.poi.poifs.filesystem.DirectoryNode) root.getEntry("__attach_version1.0_#00000000");
            string(first, 0x370E, "Text/Plain; charset=Windows-1251");
            string(first, 0x3712, "<cid-value>");
            var second = (org.apache.poi.poifs.filesystem.DirectoryNode) root.getEntry("__attach_version1.0_#00000001");
            string(second, 0x3704, "short.txt");
            string(second, 0x370E, "invalid-type");
        }), "names.msg", "application/octet-stream");
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(AttachmentInfo::file)
                .containsExactly("1__container", "2__outside.txt", "3__same.txt", "4__same.txt", "5__part", "6__part");
        assertThat(expanded.attachments().get(1).charset()).isEqualTo("windows-1251");
        assertThat(expanded.attachments().get(1).contentId()).isEqualTo("cid-value");
        assertThat(expanded.attachments().get(1).inline()).isFalse();
        assertThat(expanded.attachments().get(2).contentType()).isEqualTo("application/octet-stream");
        assertThat(expanded.attachments().get(2).name()).isEqualTo("C:/folder/same.txt");
        original = single(tnef(null, null, children), "winmail.dat", "application/octet-stream");
        assertThat(Containers.expand(original, attachments(), true).attachments()).extracting(AttachmentInfo::file)
                .containsExactly("1__container", "2__outside.txt", "3__same.txt", "4__same.txt", "5__part", "6__part");
        assertThat(temp.resolve("outside.txt")).doesNotExist();
        try (var paths = Files.list(temp)) { assertThat(paths.toList()).containsExactly(attachments()); }
    }

    @Test void mixedContainerDepthStopsBeforeLevelSix() throws Exception {
        byte[] chain = tnef(null, null, new Child("unopened.txt", bytes("beyond depth")));
        chain = msg("subject", "body", new Child("six.tnef", chain));
        chain = zip(Map.of("five.msg", chain));
        chain = OutlookFixtures.eml("four.zip", "application/zip", chain);
        chain = tnef(null, null, new Child("three.eml", chain));
        chain = msg("subject", "body", new Child("two.tnef", chain));
        var original = single(chain, "one.msg", "application/octet-stream");
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(AttachmentInfo::file)
                .containsExactly("1__container", "2__two.tnef", "3__three.eml", "4__four.zip", "5__five.msg", "6__six.tnef");
        for (int i = 1; i < 6; i++) {
            assertThat(expanded.attachments().get(i).parent()).isEqualTo(expanded.attachments().get(i - 1).file());
        }
        assertThat(Containers.expand(original, attachments(), false)).isEqualTo(expanded);
    }

    @Test void outlookContainersShareMessageLimits() throws Exception {
        var original = message(new Part("one.eml", "message/rfc822", OutlookFixtures.eml("a.txt", "text/plain", bytes("aaaa"))),
                new Part("two.zip", "application/zip", zip(entries("bbbb"))),
                new Part("three.msg", "application/octet-stream", msg(null, null, new Child("c.txt", bytes("cccc")))),
                new Part("four.tnef", "application/octet-stream", tnef(null, null, new Child("d.txt", bytes("dddd")))),
                new Part("later.msg", "application/octet-stream", msg(null, null, new Child("later.txt", bytes("later")))));
        for (boolean save : new boolean[]{false, true}) {
            for (int limit = 1; limit <= 4; limit++) {
                var limited = Containers.expand(original, attachments(), save, limit, 100);
                assertThat(limited.attachments()).hasSize(5 + limit);
                for (int bytes : new int[]{limit * 4, limit * 4 + 2}) {
                    assertThat(Containers.expand(original, attachments(), save, 100, bytes)).isEqualTo(limited);
                }
            }
        }
        // A serialized embedded message uses its compound-file length, including mapping/header overhead.
        original = single(embeddedMsg(null), "parent.msg", "application/octet-stream");
        var full = Containers.expand(original, attachments(), true);
        long size = full.attachments().get(1).size();
        for (boolean save : new boolean[]{false, true}) {
            assertThat(Containers.expand(original, attachments(), save, 100, size - 1).attachments()).hasSize(1);
            var exact = Containers.expand(original, attachments(), save, 100, size);
            assertThat(exact.attachments()).isEqualTo(full.attachments().subList(0, 2));
            assertThat(Containers.expand(original, attachments(), save, 1, Long.MAX_VALUE)).isEqualTo(exact);
        }
        assertThat(attachments().resolve("2__embedded.msg")).isRegularFile();
        // An overflowing child does not consume a number or remain partially saved.
        Files.delete(attachments().resolve("2__embedded.msg"));
        assertThat(Containers.expand(original, attachments(), true, 10, size - 1).attachments()).hasSize(1);
        assertThat(attachments().resolve("2__embedded.msg")).doesNotExist();
    }

    @Test void brokenOutlookContainersDoNotFailMessageExpansion() throws Exception {
        byte[] partial = tnef(null, null, new Child("kept.txt", bytes("kept")), new Child("broken.txt", null));
        var original = message(new Part("bad.msg", "application/octet-stream", bytes("invalid")),
                new Part("bad.tnef", "application/octet-stream", bytes("invalid")),
                new Part("partial.tnef", "application/octet-stream", partial),
                new Part("later.msg", "application/octet-stream", msg(null, null, new Child("later.txt", bytes("later")))));
        var expanded = Containers.expand(original, attachments(), true);
        assertThat(expanded.attachments()).extracting(AttachmentInfo::file).containsExactly("1__bad.msg", "2__bad.tnef",
                "3__partial.tnef", "4__later.msg", "5__kept.txt", "6__later.txt");
        assertThat(attachments().resolve("5__kept.txt")).hasBinaryContent(bytes("kept"));
        assertThat(attachments().resolve("6__broken.txt")).doesNotExist();
        assertThat(attachments().resolve("3__partial.tnef")).hasBinaryContent(partial);
        assertThat(Containers.expand(original, attachments(), false)).isEqualTo(expanded);
    }

    @Test void outlookEnumerationMatchesSavedListWithoutWriting() throws Exception {
        var original = message(new Part("embedded.msg", "application/octet-stream", embeddedMsg("child.msg")),
                new Part("winmail.dat", "application/octet-stream", tnef(null, null, new Child("текст.txt", bytes("text")))));
        var before = snapshot();
        var unsaved = Containers.expand(original, attachments(), false);
        assertThat(unsaved.attachments()).hasSize(4); // immediate children; the derived MSG does not exist yet
        assertThat(snapshot()).isEqualTo(before);
        var saved = Containers.expand(original, attachments(), true);
        assertThat(saved.attachments()).hasSize(5);
        before = snapshot();
        assertThat(Containers.expand(original, attachments(), false)).isEqualTo(saved);
        assertThat(snapshot()).isEqualTo(before);
        Path absent = temp.resolve("not-created");
        var entries = new java.util.ArrayList<AttachmentInfo>();
        MsgReader.readAttachments(attachments().resolve("1__embedded.msg"), null, 3, 10, Long.MAX_VALUE, entries);
        assertThat(entries).hasSize(1);
        assertThat(absent).doesNotExist();
        assertThat(snapshot()).isEqualTo(before);
    }

    private static String hash(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
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
