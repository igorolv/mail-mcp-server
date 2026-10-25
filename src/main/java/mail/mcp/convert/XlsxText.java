package mail.mcp.convert;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Adapted from redmine-mcp-server extraction/parser/XlsxTextParser.java at f62b3e5. */
public final class XlsxText {
    private XlsxText() { }

    public static String extract(Path file) throws IOException {
        try (var in = Files.newInputStream(file); var wb = new XSSFWorkbook(in)) {
            var sb = new StringBuilder();
            var formatter = new DataFormatter();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                var sheet = wb.getSheetAt(i);
                sb.append("--- Sheet: %s ---\n".formatted(sheet.getSheetName()));
                for (var row : sheet) {
                    var cells = new ArrayList<String>();
                    for (var cell : row) { cells.add(formatter.formatCellValue(cell)); }
                    if (cells.stream().anyMatch(c -> !c.isEmpty())) {
                        sb.append(String.join(" | ", cells)).append("\n");
                    }
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }
}
