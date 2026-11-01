package com.daf360.payroll.modules.simulation.service;

import com.daf360.payroll.modules.parameterset.entity.SocialChargeRate;
import com.daf360.payroll.modules.parameterset.service.ParameterSetService;
import com.daf360.payroll.modules.ref.entity.PaysRef;
import com.daf360.payroll.modules.ref.repository.PaysRefRepository;
import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import com.daf360.payroll.modules.simulation.repository.SimulationResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Export Excel de la fiche d'une simulation individuelle — même approche que
 * {@code PlReportExcelExportService} côté facturation : classeur Apache POI généré par le
 * backend, sans pdf-service, donc disponible en local comme en production.
 * <p>
 * Trois onglets : « Simulation » (contexte + cascade S1→S5 + détail + contre-valeurs),
 * « Rubriques » (rubriques appliquées, montant signé) et « Charges sociales » (taux du jeu de
 * paramètres pour le contrat simulé). Libellés en français ou en anglais selon {@code lang}.
 */
@Service
public class SimulationExcelExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final SimulationResultRepository resultRepo;
    private final PaysRefRepository paysRefRepo;
    private final ParameterSetService parameterSetService;
    private final ObjectMapper objectMapper;

    public SimulationExcelExportService(SimulationResultRepository resultRepo,
                                        PaysRefRepository paysRefRepo,
                                        ParameterSetService parameterSetService,
                                        ObjectMapper objectMapper) {
        this.resultRepo = resultRepo;
        this.paysRefRepo = paysRefRepo;
        this.parameterSetService = parameterSetService;
        this.objectMapper = objectMapper;
    }

    public record ExcelExport(byte[] bytes, String filename) {}

    public ExcelExport export(Long simulationId, String lang) {
        SimulationResult r = resultRepo.findById(simulationId)
                .orElseThrow(() -> new EntityNotFoundException("Simulation introuvable : " + simulationId));
        Labels l = Labels.of(lang);
        PaysRef pays = r.getPaysId() != null ? paysRefRepo.findById(r.getPaysId()).orElse(null) : null;
        String currency = r.getLocalCurrency() != null ? r.getLocalCurrency()
                : pays != null && pays.getDevise() != null ? pays.getDevise() : "";

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);
            writeSummarySheet(wb.createSheet(l.get("SHEET_SUMMARY")), s, l, r, pays, currency);
            writeRubriquesSheet(wb.createSheet(l.get("SHEET_RUBRIQUES")), s, l, r, currency);
            writeChargesSheet(wb.createSheet(l.get("SHEET_CHARGES")), s, l, r);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            String iso = pays != null && pays.getIsoCode() != null ? pays.getIsoCode() : "XX";
            return new ExcelExport(out.toByteArray(), "Simulation_%s_%d.xlsx".formatted(iso, simulationId));
        } catch (IOException e) {
            throw new UncheckedIOException("Échec de génération de l'export Excel de la simulation", e);
        }
    }

    // ── Onglet « Simulation » ────────────────────────────────────────────────

    private void writeSummarySheet(Sheet sheet, Styles s, Labels l, SimulationResult r,
                                   PaysRef pays, String currency) {
        int i = 0;
        title(sheet, i++, s, l.get("TITLE") + " #" + r.getId());
        i++;

        String paysLabel = pays != null
                ? pays.getFrenchLabel() + " (" + pays.getIsoCode() + ")"
                : (r.getPaysId() != null ? "#" + r.getPaysId() : "");
        i = labelValue(sheet, i, s, l.get("DATE"), r.getSimulatedAt() != null ? r.getSimulatedAt().format(FMT) : "");
        i = labelValue(sheet, i, s, l.get("PAYS"), paysLabel);
        i = labelValue(sheet, i, s, l.get("CURRENCY"), currency);
        i = labelValue(sheet, i, s, l.get("CONTRACT"), r.getContractType());
        i = labelValue(sheet, i, s, l.get("MODE"),
                "BRUT_TO_NET".equals(r.getMode()) ? l.get("MODE_BRUT_TO_NET") : l.get("MODE_NET_TO_BRUT"));
        if (r.getProfileUserId() != null) {
            i = labelValue(sheet, i, s, l.get("EMPLOYEE"), "#" + r.getProfileUserId());
        }
        if (r.getCandidateLabel() != null) i = labelValue(sheet, i, s, l.get("CANDIDATE"), r.getCandidateLabel());
        if (r.getPoste() != null)          i = labelValue(sheet, i, s, l.get("POSTE"), r.getPoste());
        if (r.getGrade() != null)          i = labelValue(sheet, i, s, l.get("GRADE"), r.getGrade());
        if (r.getDiscipline() != null)     i = labelValue(sheet, i, s, l.get("DISCIPLINE"), r.getDiscipline());
        i = labelValue(sheet, i, s, l.get("PARAMETER_SET"), "#" + r.getParameterSetId());
        i++;

        String suffix = currency.isBlank() ? "" : " (" + currency + ")";
        i = header(sheet, i, s, l.get("COL_STRATE"), l.get("COL_DESIGNATION"), l.get("COL_AMOUNT") + suffix);
        i = strate(sheet, i, s, "S1", l.get("S1"), r.getInputNet(), false);
        i = strate(sheet, i, s, "S2", l.get("S2"), r.getNetTaxable(), false);
        i = strate(sheet, i, s, "S3", l.get("S3"), r.getGross(), false);
        i = strate(sheet, i, s, "S4", l.get("S4"),
                r.getGrossWithBenefits() != null ? r.getGrossWithBenefits() : r.getGross(), false);
        i = strate(sheet, i, s, "S5", l.get("S5"), r.getLoadedCost(), true);
        i++;

        i = header(sheet, i, s, l.get("DETAIL"), "", l.get("COL_AMOUNT") + suffix);
        i = strate(sheet, i, s, "", l.get("IRPP"), r.getIrppAmount(), false);
        i = strate(sheet, i, s, "", l.get("EMPLOYEE_CHARGES"), r.getEmployeeCharges(), false);
        i = strate(sheet, i, s, "", l.get("EMPLOYER_CHARGES"), r.getEmployerCharges(), false);
        if (r.getCostNetRatio() != null) {
            Row row = sheet.createRow(i++);
            text(row, 1, null, l.get("RATIO"));
            number(row, 2, s.ratio, r.getCostNetRatio());
        }

        if (r.getLoadedCostEur() != null || r.getLoadedCostChf() != null || r.getLoadedCostUsd() != null) {
            i++;
            i = header(sheet, i, s, l.get("FX_TITLE"), l.get("COL_FX_RATE"), l.get("COL_AMOUNT"));
            i = fx(sheet, i, s, "EUR", currency, r.getFxRateEur(), r.getLoadedCostEur());
            i = fx(sheet, i, s, "CHF", currency, r.getFxRateChf(), r.getLoadedCostChf());
            fx(sheet, i, s, "USD", currency, r.getFxRateUsd(), r.getLoadedCostUsd());
        }

        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 42 * 256);
        sheet.setColumnWidth(2, 22 * 256);
    }

    // ── Onglet « Rubriques » ─────────────────────────────────────────────────

    private void writeRubriquesSheet(Sheet sheet, Styles s, Labels l, SimulationResult r, String currency) {
        String suffix = currency.isBlank() ? "" : " (" + currency + ")";
        int i = header(sheet, 0, s, l.get("COL_CODE"), l.get("COL_NATURE"), l.get("COL_CALC_MODE"),
                l.get("COL_DIRECTION"), l.get("COL_AMOUNT") + suffix);
        for (JsonNode n : parseArray(r.getRubriquesApplied())) {
            Row row = sheet.createRow(i++);
            text(row, 0, null, n.path("code").asText(""));
            text(row, 1, null, n.path("nature").asText(""));
            text(row, 2, null, n.path("calcMode").asText(""));
            String direction = n.path("direction").asText("");
            text(row, 3, null, direction);
            BigDecimal amount = n.path("amount").isNumber() ? n.path("amount").decimalValue() : BigDecimal.ZERO;
            number(row, 4, s.amount, "DEBIT".equals(direction) ? amount.negate() : amount);
        }
        if (i == 1) text(sheet.createRow(i), 0, null, l.get("EMPTY_RUBRIQUES"));
        widths(sheet, 18, 18, 24, 12, 20);
    }

    // ── Onglet « Charges sociales » ──────────────────────────────────────────

    private void writeChargesSheet(Sheet sheet, Styles s, Labels l, SimulationResult r) {
        int i = header(sheet, 0, s, l.get("COL_CODE"), l.get("COL_LABEL"), l.get("COL_EMPLOYEE_RATE"),
                l.get("COL_EMPLOYER_RATE"), l.get("COL_BASE"), l.get("COL_CAP"));
        List<SocialChargeRate> rates = r.getParameterSetId() == null ? List.of()
                : parameterSetService.loadRates(r.getParameterSetId()).stream()
                        .filter(c -> c.getContractType() != null && c.getContractType().equals(r.getContractType()))
                        .sorted(Comparator.comparing(c -> c.getEvalOrder() != null ? c.getEvalOrder() : 0))
                        .toList();
        for (SocialChargeRate c : rates) {
            Row row = sheet.createRow(i++);
            text(row, 0, null, c.getChargeCode());
            text(row, 1, null, c.getChargeLabel());
            number(row, 2, s.pct, c.getEmployeeRate());
            number(row, 3, s.pct, c.getEmployerRate());
            text(row, 4, null, c.getBaseCalculation());
            if (c.getCapAmount() != null) number(row, 5, s.amount, c.getCapAmount());
        }
        if (rates.isEmpty()) text(sheet.createRow(i), 0, null, l.get("EMPTY_CHARGES"));
        widths(sheet, 16, 36, 16, 16, 16, 16);
    }

    // ── Helpers de lignes ────────────────────────────────────────────────────

    private void title(Sheet sheet, int rowIdx, Styles s, String value) {
        text(sheet.createRow(rowIdx), 0, s.title, value);
    }

    private int labelValue(Sheet sheet, int rowIdx, Styles s, String label, String value) {
        Row row = sheet.createRow(rowIdx);
        text(row, 0, s.bold, label);
        text(row, 1, null, value != null ? value : "");
        return rowIdx + 1;
    }

    private int header(Sheet sheet, int rowIdx, Styles s, String... labels) {
        Row row = sheet.createRow(rowIdx);
        for (int c = 0; c < labels.length; c++) text(row, c, s.header, labels[c]);
        return rowIdx + 1;
    }

    private int strate(Sheet sheet, int rowIdx, Styles s, String code, String label, BigDecimal amount, boolean total) {
        Row row = sheet.createRow(rowIdx);
        text(row, 0, total ? s.bold : null, code);
        text(row, 1, total ? s.bold : null, label);
        number(row, 2, total ? s.amountBold : s.amount, amount);
        return rowIdx + 1;
    }

    private int fx(Sheet sheet, int rowIdx, Styles s, String target, String currency, BigDecimal rate, BigDecimal amount) {
        if (amount == null) return rowIdx;
        Row row = sheet.createRow(rowIdx);
        text(row, 0, null, target);
        text(row, 1, null, rate != null ? "1 " + currency + " = " + rate.stripTrailingZeros().toPlainString() + " " + target : "");
        number(row, 2, s.amount, amount);
        return rowIdx + 1;
    }

    private static void text(Row row, int col, CellStyle style, String value) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value != null ? value : "");
        if (style != null) cell.setCellStyle(style);
    }

    private static void number(Row row, int col, CellStyle style, BigDecimal value) {
        Cell cell = row.createCell(col);
        if (value != null) cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void widths(Sheet sheet, int... chars) {
        for (int c = 0; c < chars.length; c++) sheet.setColumnWidth(c, chars[c] * 256);
    }

    private List<JsonNode> parseArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) return List.of();
            List<JsonNode> items = new java.util.ArrayList<>();
            node.forEach(items::add);
            return items;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Styles ───────────────────────────────────────────────────────────────

    private static final class Styles {
        final CellStyle title;
        final CellStyle bold;
        final CellStyle header;
        final CellStyle amount;
        final CellStyle amountBold;
        final CellStyle pct;
        final CellStyle ratio;

        Styles(XSSFWorkbook wb) {
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            title = wb.createCellStyle();
            title.setFont(titleFont);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);
            header = wb.createCellStyle();
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            short money = wb.createDataFormat().getFormat("#,##0.00");
            amount = wb.createCellStyle();
            amount.setDataFormat(money);
            amountBold = wb.createCellStyle();
            amountBold.setDataFormat(money);
            amountBold.setFont(boldFont);
            pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
            ratio = wb.createCellStyle();
            ratio.setDataFormat(wb.createDataFormat().getFormat("0.00"));
        }
    }

    // ── Libellés FR / EN ─────────────────────────────────────────────────────

    private record Labels(Map<String, String> map) {
        String get(String key) { return map.getOrDefault(key, key); }

        static Labels of(String lang) {
            return lang != null && lang.toLowerCase().startsWith("en") ? new Labels(EN) : new Labels(FR);
        }
    }

    private static final Map<String, String> FR = Map.ofEntries(
            Map.entry("SHEET_SUMMARY", "Simulation"),
            Map.entry("SHEET_RUBRIQUES", "Rubriques"),
            Map.entry("SHEET_CHARGES", "Charges sociales"),
            Map.entry("TITLE", "Simulation de paie"),
            Map.entry("DATE", "Date"),
            Map.entry("PAYS", "Pays"),
            Map.entry("CURRENCY", "Devise"),
            Map.entry("CONTRACT", "Type de contrat"),
            Map.entry("MODE", "Sens du calcul"),
            Map.entry("MODE_NET_TO_BRUT", "Net → Brut"),
            Map.entry("MODE_BRUT_TO_NET", "Brut → Net"),
            Map.entry("EMPLOYEE", "Collaborateur"),
            Map.entry("CANDIDATE", "Candidat"),
            Map.entry("POSTE", "Poste"),
            Map.entry("GRADE", "Grade"),
            Map.entry("DISCIPLINE", "Discipline"),
            Map.entry("PARAMETER_SET", "Jeu de paramètres"),
            Map.entry("COL_STRATE", "Strate"),
            Map.entry("COL_DESIGNATION", "Désignation"),
            Map.entry("COL_AMOUNT", "Montant"),
            Map.entry("S1", "Net versé"),
            Map.entry("S2", "Net imposable (base IRPP)"),
            Map.entry("S3", "Brut base taxable"),
            Map.entry("S4", "Brut total (+ avantages exonérés)"),
            Map.entry("S5", "Coût chargé total"),
            Map.entry("DETAIL", "Détail du calcul"),
            Map.entry("IRPP", "IRPP"),
            Map.entry("EMPLOYEE_CHARGES", "Charges salariales"),
            Map.entry("EMPLOYER_CHARGES", "Charges patronales"),
            Map.entry("RATIO", "Ratio coût / net"),
            Map.entry("FX_TITLE", "Coût chargé en devises"),
            Map.entry("COL_FX_RATE", "Taux de change"),
            Map.entry("COL_CODE", "Code"),
            Map.entry("COL_NATURE", "Nature"),
            Map.entry("COL_CALC_MODE", "Mode de calcul"),
            Map.entry("COL_DIRECTION", "Sens"),
            Map.entry("COL_LABEL", "Libellé"),
            Map.entry("COL_EMPLOYEE_RATE", "Taux salarié"),
            Map.entry("COL_EMPLOYER_RATE", "Taux employeur"),
            Map.entry("COL_BASE", "Base"),
            Map.entry("COL_CAP", "Plafond"),
            Map.entry("EMPTY_RUBRIQUES", "Aucune rubrique appliquée."),
            Map.entry("EMPTY_CHARGES", "Aucune charge sociale pour ce contrat dans le jeu de paramètres."));

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("SHEET_SUMMARY", "Simulation"),
            Map.entry("SHEET_RUBRIQUES", "Pay items"),
            Map.entry("SHEET_CHARGES", "Social charges"),
            Map.entry("TITLE", "Payroll simulation"),
            Map.entry("DATE", "Date"),
            Map.entry("PAYS", "Country"),
            Map.entry("CURRENCY", "Currency"),
            Map.entry("CONTRACT", "Contract type"),
            Map.entry("MODE", "Calculation direction"),
            Map.entry("MODE_NET_TO_BRUT", "Net → Gross"),
            Map.entry("MODE_BRUT_TO_NET", "Gross → Net"),
            Map.entry("EMPLOYEE", "Employee"),
            Map.entry("CANDIDATE", "Candidate"),
            Map.entry("POSTE", "Position"),
            Map.entry("GRADE", "Grade"),
            Map.entry("DISCIPLINE", "Discipline"),
            Map.entry("PARAMETER_SET", "Parameter set"),
            Map.entry("COL_STRATE", "Stratum"),
            Map.entry("COL_DESIGNATION", "Description"),
            Map.entry("COL_AMOUNT", "Amount"),
            Map.entry("S1", "Net paid"),
            Map.entry("S2", "Taxable net (income tax base)"),
            Map.entry("S3", "Taxable gross"),
            Map.entry("S4", "Total gross (+ exempt benefits)"),
            Map.entry("S5", "Total loaded cost"),
            Map.entry("DETAIL", "Calculation detail"),
            Map.entry("IRPP", "Income tax (IRPP)"),
            Map.entry("EMPLOYEE_CHARGES", "Employee charges"),
            Map.entry("EMPLOYER_CHARGES", "Employer charges"),
            Map.entry("RATIO", "Cost / net ratio"),
            Map.entry("FX_TITLE", "Loaded cost in other currencies"),
            Map.entry("COL_FX_RATE", "Exchange rate"),
            Map.entry("COL_CODE", "Code"),
            Map.entry("COL_NATURE", "Nature"),
            Map.entry("COL_CALC_MODE", "Calculation mode"),
            Map.entry("COL_DIRECTION", "Direction"),
            Map.entry("COL_LABEL", "Label"),
            Map.entry("COL_EMPLOYEE_RATE", "Employee rate"),
            Map.entry("COL_EMPLOYER_RATE", "Employer rate"),
            Map.entry("COL_BASE", "Base"),
            Map.entry("COL_CAP", "Cap"),
            Map.entry("EMPTY_RUBRIQUES", "No pay item applied."),
            Map.entry("EMPTY_CHARGES", "No social charge for this contract in the parameter set."));
}
