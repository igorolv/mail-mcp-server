package mail.mcp.convert;

import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

/** Adapted from redmine-mcp-server extraction/parser/PptxTextParser.java at f62b3e5, for binary PPT. */
public final class PptText {
    private PptText() { }

    public static String extract(Path file) throws IOException {
        try (var fs = new POIFSFileSystem(file.toFile(), true); var slides = new HSLFSlideShow(fs)) {
            if (slides.getEncryptionInfo() != null) { return ""; }
            var text = new StringBuilder();
            int number = 0;
            for (var slide : slides.getSlides()) {
                number++;
                var parts = new ArrayList<String>();
                for (var shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        String value = textShape.getText();
                        if (value != null && !value.isBlank()) { parts.add(value.strip()); }
                    }
                    if (shape instanceof HSLFTable table) {
                        for (int row = 0; row < table.getNumberOfRows(); row++) {
                            var cells = new ArrayList<String>();
                            for (int column = 0; column < table.getNumberOfColumns(); column++) {
                                var cell = table.getCell(row, column);
                                cells.add(cell == null || cell.getText() == null ? "" : cell.getText().strip());
                            }
                            if (cells.stream().anyMatch(c -> !c.isBlank())) { parts.add(String.join(" | ", cells)); }
                        }
                    }
                }
                if (!parts.isEmpty()) {
                    text.append("--- Slide %d ---\n".formatted(number)).append(String.join("\n", parts)).append("\n\n");
                }
            }
            return text.toString();
        }
    }
}
