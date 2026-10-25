package mail.mcp.convert;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Adapted from redmine-mcp-server extraction/parser/PptxTextParser.java at f62b3e5. */
public final class PptxText {
    private PptxText() { }

    public static String extract(Path file) throws IOException {
        try (var in = Files.newInputStream(file); var pptx = new XMLSlideShow(in)) {
            var sb = new StringBuilder();
            int slideNum = 0;
            for (XSLFSlide slide : pptx.getSlides()) {
                slideNum++;
                var texts = new ArrayList<String>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) { texts.add(text.strip()); }
                    }
                    if (shape instanceof XSLFTable table) {
                        for (int r = 0; r < table.getNumberOfRows(); r++) {
                            var cells = new ArrayList<String>();
                            for (int c = 0; c < table.getNumberOfColumns(); c++) {
                                cells.add(table.getCell(r, c).getText().strip());
                            }
                            texts.add(String.join(" | ", cells));
                        }
                    }
                }
                if (!texts.isEmpty()) {
                    sb.append("--- Slide %d ---\n".formatted(slideNum));
                    sb.append(String.join("\n", texts)).append("\n\n");
                }
            }
            return sb.toString();
        }
    }
}
