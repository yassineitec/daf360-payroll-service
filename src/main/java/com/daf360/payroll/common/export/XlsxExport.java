package com.daf360.payroll.common.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Socle des exports Excel « tableau » — copie de
 * {@code com.daf360.facturation.common.export.XlsxExport} : une feuille = un en-tête en gras
 * figé + des lignes de cellules typées.
 * <p>
 * Le type Java de chaque valeur décide de la cellule : {@link BigDecimal} → nombre au format
 * {@code #,##0.<scale>}, autre {@link Number} → nombre brut, {@link LocalDate} → vraie date
 * Excel {@code dd/mm/yyyy}, {@code null} → cellule vide, tout le reste → texte.
 * <p>
 * Largeurs calculées sur la longueur du contenu plutôt que {@code autoSizeColumn} : ce dernier
 * a besoin des polices AWT, absentes des images Docker « slim ».
 */
public final class XlsxExport {

    public static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final int MIN_COL_CHARS = 8;
    private static final int MAX_COL_CHARS = 60;

    private XlsxExport() {}

    public record SheetData(String name, List<String> headers, List<List<Object>> rows) {}

    public static byte[] write(List<SheetData> sheets) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = new Styles(wb);
            for (SheetData data : sheets) {
                writeSheet(wb, styles, data);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Échec de génération de l'export Excel", e);
        }
    }

    private static void writeSheet(XSSFWorkbook wb, Styles styles, SheetData data) {
        Sheet sheet = wb.createSheet(WorkbookUtil.createSafeSheetName(data.name()));
        int[] widths = new int[data.headers().size()];

        Row header = sheet.createRow(0);
        for (int c = 0; c < data.headers().size(); c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(data.headers().get(c));
            cell.setCellStyle(styles.header);
            widths[c] = data.headers().get(c).length();
        }

        int r = 1;
        for (List<Object> values : data.rows()) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < values.size(); c++) {
                Object v = values.get(c);
                if (v == null) continue;
                Cell cell = row.createCell(c);
                int len;
                if (v instanceof BigDecimal bd) {
                    cell.setCellValue(bd.doubleValue());
                    cell.setCellStyle(styles.number(Math.max(bd.scale(), 0)));
                    len = bd.toPlainString().length() + 2;
                } else if (v instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                    len = n.toString().length();
                } else if (v instanceof LocalDate d) {
                    cell.setCellValue(d);
                    cell.setCellStyle(styles.date);
                    len = 10;
                } else {
                    String s = v.toString();
                    cell.setCellValue(s);
                    len = s.length();
                }
                if (c < widths.length) widths[c] = Math.max(widths[c], len);
            }
        }

        for (int c = 0; c < widths.length; c++) {
            int chars = Math.min(MAX_COL_CHARS, Math.max(MIN_COL_CHARS, widths[c] + 2));
            sheet.setColumnWidth(c, chars * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    /** Styles partagés par classeur — POI limite le nombre de CellStyle par fichier. */
    private static final class Styles {
        private final XSSFWorkbook wb;
        private final CellStyle header;
        private final CellStyle date;
        private final Map<Integer, CellStyle> numberByScale = new HashMap<>();

        Styles(XSSFWorkbook wb) {
            this.wb = wb;
            Font bold = wb.createFont();
            bold.setBold(true);
            header = wb.createCellStyle();
            header.setFont(bold);
            date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
        }

        CellStyle number(int scale) {
            return numberByScale.computeIfAbsent(scale, s -> {
                CellStyle style = wb.createCellStyle();
                String pattern = s == 0 ? "#,##0" : "#,##0." + "0".repeat(s);
                style.setDataFormat(wb.createDataFormat().getFormat(pattern));
                return style;
            });
        }
    }
}
