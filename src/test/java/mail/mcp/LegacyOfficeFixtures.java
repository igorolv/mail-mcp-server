package mail.mcp;

import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.model.FileInformationBlock;
import org.apache.poi.hwpf.model.SubdocumentType;
import org.apache.poi.hwpf.model.types.DOPAbstractType;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Invented Office 97-2003 documents; no sample/template or external generator is needed. */
public final class LegacyOfficeFixtures {
    private LegacyOfficeFixtures() { }

    public static byte[] doc(String text) throws Exception {
        String paragraphs = text.replace("\n", "\r") + "\r";
        byte[] encoded = paragraphs.getBytes(StandardCharsets.UTF_16LE);
        byte[] main = new byte[2048 + encoded.length];
        byte[] table = new byte[1024];
        // Word 97 FIB: signature, version, Russian language, complex Unicode text, 1Table.
        LittleEndian.putShort(main, 0, (short) 0xA5EC);
        LittleEndian.putShort(main, 2, (short) 0x00C1);
        LittleEndian.putShort(main, 6, (short) 0x0419);
        LittleEndian.putShort(main, 10, (short) 0x1204);
        LittleEndian.putInt(main, 24, 2048);
        LittleEndian.putInt(main, 28, main.length);
        LittleEndian.putShort(main, 32, (short) 14); // FibRgW97: 14 words
        LittleEndian.putShort(main, 62, (short) 22); // FibRgLw97: 22 longs
        LittleEndian.putShort(main, 152, (short) 93); // FibRgFcLcb97: 93 offset/size pairs
        System.arraycopy(encoded, 0, main, 2048, encoded.length);
        // One character-formatting run, default properties, in the FKP at page 2 (1024).
        LittleEndian.putInt(main, 1024, 2048);
        LittleEndian.putInt(main, 1028, main.length);
        main[1535] = 1;
        LittleEndian.putInt(table, 700, 2048);
        LittleEndian.putInt(table, 704, main.length);
        LittleEndian.putInt(table, 708, 2);
        // Empty stylesheet with one default (nil) style; empty font table at offset 32.
        LittleEndian.putShort(table, 0, (short) 18);
        LittleEndian.putShort(table, 2, (short) 1);
        LittleEndian.putShort(table, 4, (short) 10);
        // CLX/Pcdt at 64: a single Unicode piece spanning all characters at WordDocument[2048].
        var piece = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        piece.position(64);
        piece.put((byte) 2).putInt(16).putInt(0).putInt(paragraphs.length())
                .putShort((short) 0).putInt(2048).putShort((short) 0);
        // One section, no section-property exceptions (fcSepx = -1).
        piece.position(96);
        piece.putInt(0).putInt(paragraphs.length()).putShort((short) 0).putInt(-1)
                .putShort((short) 0).putInt(-1);
        var fib = new FileInformationBlock(main);
        fib.fillVariableFields(main, table);
        fib.setSubdocumentTextStreamLength(SubdocumentType.MAIN, paragraphs.length());
        fib.setFcStshf(0); fib.setLcbStshf(22);
        fib.setFcSttbfffn(32); fib.setLcbSttbfffn(4);
        fib.setFcClx(64); fib.setLcbClx(21);
        fib.setFcPlcfsed(96); fib.setLcbPlcfsed(20);
        fib.setFcDop(128); fib.setLcbDop(DOPAbstractType.getSize());
        fib.setFcPlcfbteChpx(700); fib.setLcbPlcfbteChpx(12);
        var tableOutput = new ByteArrayOutputStream();
        tableOutput.writeBytes(table);
        fib.writeTo(main, tableOutput);
        try (var fs = new POIFSFileSystem(); var output = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(main), "WordDocument");
            fs.createDocument(new ByteArrayInputStream(tableOutput.toByteArray()), "1Table");
            fs.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    public static byte[] xls(String text) throws Exception {
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Данные");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(text);
            var number = row.createCell(1);
            number.setCellValue(42);
            var format = workbook.createCellStyle();
            format.setDataFormat(workbook.createDataFormat().getFormat("00000"));
            number.setCellStyle(format);
            sheet.createRow(1).createCell(0).setCellValue("Second row");
            sheet.createRow(2).createCell(0).setCellFormula("SUM(1,2)");
            workbook.createSheet("Другой лист").createRow(0).createCell(0).setCellValue("English cell");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    public static byte[] ppt(String text) throws Exception {
        try (var slides = new HSLFSlideShow(); var output = new ByteArrayOutputStream()) {
            var slide = slides.createSlide();
            slide.createTextBox().setText(text);
            var table = slide.createTable(1, 2);
            table.getCell(0, 0).setText("Таблица");
            table.getCell(0, 1).setText("Table cell");
            slides.createSlide().createTextBox().setText("Second slide");
            slides.write(output);
            return output.toByteArray();
        }
    }

    public static byte[] zip(String name, byte[] data) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(data);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    public static byte[] message() throws Exception {
        String boundary = "legacy-fixture";
        var output = new ByteArrayOutputStream();
        output.writeBytes(("MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=" + boundary
                + "\r\n\r\n--" + boundary + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nOuter body\r\n")
                .getBytes(StandardCharsets.UTF_8));
        String[] names = {"document.doc", "cells.xls", "slides.ppt", "broken.doc"};
        byte[][] data = {doc("Договор документа\nEnglish paragraph"), xls("Договор таблицы"),
                ppt("Отчёт презентации"), "invalid".getBytes(StandardCharsets.UTF_8)};
        for (int i = 0; i < names.length; i++) {
            output.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(OutlookFixtures.eml(names[i], "application/octet-stream", data[i]));
            output.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }
}
