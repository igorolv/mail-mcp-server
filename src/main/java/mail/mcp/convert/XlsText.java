package mail.mcp.convert;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.DataFormatter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

/** Adapted from redmine-mcp-server extraction/parser/XlsxTextParser.java at f62b3e5, for binary XLS. */
public final class XlsText {
    private XlsText() { }

    public static String extract(Path file) throws IOException {
        try (var fs = new POIFSFileSystem(file.toFile(), true); var workbook = new HSSFWorkbook(fs)) {
            if (workbook.getEncryptionInfo() != null) { return ""; }
            var text = new StringBuilder();
            var formatter = new DataFormatter();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                var sheet = workbook.getSheetAt(i);
                var rows = new StringBuilder();
                for (var row : sheet) {
                    var cells = new ArrayList<String>();
                    for (var cell : row) { cells.add(formatter.formatCellValue(cell)); }
                    if (cells.stream().anyMatch(c -> !c.isBlank())) { rows.append(String.join(" | ", cells)).append('\n'); }
                }
                if (!rows.isEmpty()) {
                    text.append("--- Sheet: %s ---\n".formatted(sheet.getSheetName())).append(rows).append('\n');
                }
            }
            return text.toString();
        }
    }
}
