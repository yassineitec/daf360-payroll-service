package com.daf360.payroll.common.export;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Socle des exports Excel « tableau » — copie de
 * {@code com.daf360.facturation.common.export.XlsxExport} (lignes en gras, groupes repliables).
 * Ancien commentaire de la facturation :
 * {@code XLSX.writeFile} faits jusqu'ici dans le navigateur) : une feuille = un en-tête en
 * gras figé + des lignes de cellules typées.
 * <p>
 * Le type Java de chaque valeur décide de la cellule : {@link BigDecimal} → nombre au format
 * {@code #,##0.<scale>} (l'échelle du BigDecimal fixe les décimales affichées), autre
 * {@link Number} → nombre brut, {@link LocalDate} → vraie date Excel {@code dd/mm/yyyy}
 * (triable / filtrable, contrairement au texte qu'écrivait le front), {@code null} → cellule
 * vide, tout le reste → texte.
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

    /**
     * Une feuille. {@code formats} (optionnel, une entrée par ligne de {@code rows}, ou
     * {@code null}) : ligne en gras et niveau de regroupement. Les lignes de niveau 1 qui
     * suivent une ligne de niveau 0 forment un groupe repliable (+ / −) sous elle — une
     * facture et ses lignes, par exemple. Sans {@code formats}, rendu inchangé.
     */
    public record SheetData(String name, List<String> headers, List<List<Object>> rows,
                            List<RowFormat> formats) {
        public SheetData(String name, List<String> headers, List<List<Object>> rows) {
            this(name, headers, rows, null);
        }
    }

    /**
     * Mise en forme d'une ligne de données, sans couleur : gras, niveau de regroupement
     * (0 = aucun), trait fin au-dessus de la ligne sur toute la largeur du tableau
     * ({@code separatorAbove} — sépare les groupes) et colonne dont le texte est en retrait
     * ({@code indentColumn}, -1 = aucune — montre qu'une ligne dépend de celle du dessus).
     */
    public record RowFormat(boolean bold, int outlineLevel, boolean separatorAbove, int indentColumn) {
        public static final RowFormat PLAIN = new RowFormat(false, 0);

        public RowFormat(boolean bold, int outlineLevel) {
            this(bold, outlineLevel, false, -1);
        }
    }

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

    /** Réponse HTTP « pièce jointe » : même forme que PlReportController#exportXlsx. */
    public static ResponseEntity<byte[]> attachment(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(bytes);
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
        for (int i = 0; i < data.rows().size(); i++) {
            List<Object> values = data.rows().get(i);
            RowFormat fmt = formatOf(data, i);
            boolean bold = fmt.bold();
            boolean sep = fmt.separatorAbove();
            Row row = sheet.createRow(r++);
            // Trait de séparation : toutes les colonnes du tableau, sinon il aurait des trous.
            int last = sep ? Math.max(values.size(), widths.length) : values.size();
            for (int c = 0; c < last; c++) {
                Object v = c < values.size() ? values.get(c) : null;
                if (v == null) {
                    if (sep) row.createCell(c).setCellStyle(styles.style(Kind.TEXT, 0, bold, true, false));
                    continue;
                }
                Cell cell = row.createCell(c);
                boolean indent = c == fmt.indentColumn();
                int len;
                if (v instanceof BigDecimal bd) {
                    cell.setCellValue(bd.doubleValue());
                    cell.setCellStyle(styles.style(Kind.NUMBER, Math.max(bd.scale(), 0), bold, sep, false));
                    len = bd.toPlainString().length() + 2;
                } else if (v instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                    if (bold || sep) cell.setCellStyle(styles.style(Kind.TEXT, 0, bold, sep, false));
                    len = n.toString().length();
                } else if (v instanceof LocalDate d) {
                    cell.setCellValue(d);
                    cell.setCellStyle(styles.style(Kind.DATE, 0, bold, sep, false));
                    len = 10;
                } else {
                    String s = v.toString();
                    cell.setCellValue(s);
                    if (bold || sep || indent) cell.setCellStyle(styles.style(Kind.TEXT, 0, bold, sep, indent));
                    len = s.length() + (indent ? 3 : 0);
                }
                if (c < widths.length) widths[c] = Math.max(widths[c], len);
            }
        }

        for (int c = 0; c < widths.length; c++) {
            int chars = Math.min(MAX_COL_CHARS, Math.max(MIN_COL_CHARS, widths[c] + 2));
            sheet.setColumnWidth(c, chars * 256);
        }
        sheet.createFreezePane(0, 1);
        groupRows(sheet, data);
    }

    private static RowFormat formatOf(SheetData data, int i) {
        List<RowFormat> f = data.formats();
        return f == null || i >= f.size() || f.get(i) == null ? RowFormat.PLAIN : f.get(i);
    }

    /**
     * Regroupe chaque suite de lignes de niveau ≥ 1 (lignes Excel = index + 1, l'en-tête
     * occupant la ligne 0). Bouton +/− sur la ligne de résumé AU-DESSUS du groupe (la
     * facture), groupes dépliés à l'ouverture.
     */
    private static void groupRows(Sheet sheet, SheetData data) {
        if (data.formats() == null) return;
        sheet.setRowSumsBelow(false);
        int start = -1;
        for (int i = 0; i <= data.rows().size(); i++) {
            boolean inGroup = i < data.rows().size() && formatOf(data, i).outlineLevel() > 0;
            if (inGroup && start < 0) start = i;
            if (!inGroup && start >= 0) {
                sheet.groupRow(start + 1, i);   // lignes Excel start+1 .. i (index i-1 + 1)
                start = -1;
            }
        }
    }

    private enum Kind { TEXT, NUMBER, DATE }

    /**
     * Styles partagés par classeur — POI limite le nombre de CellStyle par fichier : un style
     * par combinaison (type de cellule, décimales, gras, trait, retrait) réellement utilisée.
     */
    private static final class Styles {
        private final XSSFWorkbook wb;
        private final CellStyle header;
        private final Font bold;
        private final Map<String, CellStyle> cache = new HashMap<>();

        Styles(XSSFWorkbook wb) {
            this.wb = wb;
            bold = wb.createFont();
            bold.setBold(true);
            header = wb.createCellStyle();
            header.setFont(bold);
        }

        CellStyle style(Kind kind, int scale, boolean isBold, boolean topBorder, boolean indent) {
            String key = kind + ":" + scale + ":" + isBold + ":" + topBorder + ":" + indent;
            return cache.computeIfAbsent(key, k -> {
                CellStyle style = wb.createCellStyle();
                if (kind == Kind.NUMBER) {
                    String pattern = scale == 0 ? "#,##0" : "#,##0." + "0".repeat(scale);
                    style.setDataFormat(wb.createDataFormat().getFormat(pattern));
                } else if (kind == Kind.DATE) {
                    style.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
                }
                if (isBold) style.setFont(bold);
                if (topBorder) style.setBorderTop(BorderStyle.THIN);
                if (indent) style.setIndention((short) 2);
                return style;
            });
        }
    }
}
