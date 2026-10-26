package mail.mcp.convert;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.IOException;
import java.nio.file.Path;

/** Extracts text from a binary Word 97-2003 document without changing it. */
public final class DocText {
    private DocText() { }

    public static String extract(Path file) throws IOException {
        try (var fs = new POIFSFileSystem(file.toFile(), true);
             var document = new HWPFDocument(fs);
             var extractor = new WordExtractor(document)) {
            if (document.getFileInformationBlock().getFibBase().isFEncrypted()) { return ""; }
            return extractor.getText();
        }
    }
}
