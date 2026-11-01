package com.daf360.payroll.modules.payroll.service;

import com.daf360.payroll.common.export.ExportFile;
import com.daf360.payroll.common.export.ExportLang;
import com.daf360.payroll.common.export.ExportRows;
import com.daf360.payroll.common.export.PdfRenderClient;
import com.daf360.payroll.common.export.XlsxExport;
import com.daf360.payroll.common.export.XlsxExport.SheetData;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.repository.PayrollResultRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PaysScopeContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Exports Excel et PDF du détail par rubrique d'un résultat de paie — le tableau du bulletin de
 * {@code /payroll/engine-results/:employeeId?result=<id>}. Même contrat que les exports de la
 * facturation : le front envoie les codes des rubriques affichées (recherche, filtres et tri
 * appliqués, voir {@link ExportRows#select}) et sa langue ; le serveur relit le résultat en base.
 * <p>
 * Colonnes = celles du tableau à l'écran (code, libellé, strate, nature, assiette, montant,
 * mode), retenues en négatif comme à l'écran. Le PDF ajoute un en-tête (période, collaborateur,
 * convergence) et la synthèse Brut → CNSS salariale → IRPP → Net, lue sur les strates réelles
 * du moteur (vérifié en base le 2026-10-20) : S1 SALAIRE_BASE · S2 PRIME_TRANSPORT ·
 * S3 CNSS_EE (part salariale) · S4 IRPP · S5 NET_A_PAYER.
 */
@Service
@RequiredArgsConstructor
public class PayrollResultExportService {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Africa/Tunis");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Copie de {@code PAYROLL.ENGINE_RUN.NATURE.*} (front). */
    private static final Map<String, String[]> NATURES = Map.of(
            "GAIN",      new String[] {"Gain", "Gain"},
            "RETENUE",   new String[] {"Retenue", "Deduction"},
            "AVANTAGE",  new String[] {"Avantage", "Benefit"},
            "INDEMNITE", new String[] {"Indemnité", "Allowance"},
            "PRIME",     new String[] {"Prime", "Bonus"});

    /** Copie de {@code PAYROLL.ADMIN_HOME.RUBRIQUES.MODE.*} (front). */
    private static final Map<String, String[]> MODES = Map.of(
            "TAUX_PCT",          new String[] {"Taux (%)", "Rate (%)"},
            "MONTANT_FIXE",      new String[] {"Montant fixe", "Fixed amount"},
            "BAREME_PROGRESSIF", new String[] {"Barème progressif", "Progressive scale"},
            "ANNUALISE_BAREME",  new String[] {"Barème annualisé", "Annualised scale"},
            "FORMULE",           new String[] {"Formule", "Formula"},
            "NEWTON_RAPHSON",    new String[] {"Newton-Raphson", "Newton-Raphson"});

    private final PayrollResultRepository resultRepo;
    private final UserContextService userContext;
    private final ObjectMapper objectMapper;
    private final PdfRenderClient pdfClient;

    /** Une ligne de {@code payroll_results.rubrique_details} (JSON écrit par ResultPersister). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Line(String rubriqueCode, String labelFr, String labelEn, String nature, int strate,
                BigDecimal assiette, BigDecimal amount, String modeCalcul) {}

    // ── Excel ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ExportFile exportXlsx(Long resultId, Collection<String> codes, ExportLang lang) {
        PayrollResult r = findInScope(resultId);
        List<Line> lines = ExportRows.select(lines(r), codes, Line::rubriqueCode);

        List<List<Object>> rows = lines.stream().map(l -> Arrays.<Object>asList(
                l.rubriqueCode(), label(l, lang), "S" + l.strate(), lang.label(NATURES, l.nature()),
                l.assiette(), signed(l), lang.label(MODES, l.modeCalcul()))).toList();

        byte[] bytes = XlsxExport.write(List.of(new SheetData(lang.pick("Rubriques", "Pay items"), headers(lang), rows)));
        return ExportFile.xlsx(filename(r, "xlsx"), bytes);
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ExportFile exportPdf(Long resultId, Collection<String> codes, String employeeName, ExportLang lang) {
        PayrollResult r = findInScope(resultId);
        List<Line> lines = ExportRows.select(lines(r), codes, Line::rubriqueCode);
        String filename = filename(r, "pdf");
        return ExportFile.pdf(filename, pdfClient.render(buildHtml(r, lines, employeeName, lang), filename));
    }

    private String buildHtml(PayrollResult r, List<Line> lines, String employeeName, ExportLang lang) {
        DecimalFormat money = money(lang);
        String title = lang.pick("Bulletin de paie", "Payslip") + " — " + period(r, lang);
        String who = employeeName != null && !employeeName.isBlank()
                ? escape(employeeName.trim())
                : lang.pick("Collaborateur", "Employee") + " #" + r.getEmployeeId();
        String calculated = r.getCalculatedAt() != null
                ? r.getCalculatedAt().atZoneSameInstant(DISPLAY_ZONE).format(DATE_TIME) : "—";
        String convergence = r.isConvergenceOk()
                ? "<span class=\"badge ok\">" + lang.pick("Convergence OK", "Converged") + "</span>"
                : "<span class=\"badge ko\">" + lang.pick("Non convergé", "Not converged") + "</span>";

        BigDecimal gross = r.getAggregateGross() != null ? r.getAggregateGross() : nz(r.getStrate1()).add(nz(r.getStrate2()));
        BigDecimal cnss  = nz(r.getStrate3());
        BigDecimal irpp  = r.getAggregateIrpp() != null ? r.getAggregateIrpp() : nz(r.getStrate4());
        BigDecimal net   = nz(r.getStrate5());

        StringBuilder body = new StringBuilder();
        for (Line l : lines) {
            BigDecimal amount = signed(l);
            body.append("<tr>")
                .append("<td class=\"code\">").append(escape(l.rubriqueCode())).append("</td>")
                .append("<td>").append(escape(label(l, lang))).append("</td>")
                .append("<td class=\"c\">S").append(l.strate()).append("</td>")
                .append("<td>").append(escape(lang.label(NATURES, l.nature()))).append("</td>")
                .append("<td class=\"num\">").append(fmt(money, l.assiette())).append("</td>")
                .append("<td class=\"num").append(amount != null && amount.signum() < 0 ? " neg" : "").append("\">")
                    .append(fmt(money, amount)).append("</td>")
                .append("<td>").append(escape(lang.label(MODES, l.modeCalcul()))).append("</td>")
                .append("</tr>");
        }
        if (lines.isEmpty()) {
            body.append("<tr><td colspan=\"7\" class=\"empty\">")
                .append(lang.pick("Aucune rubrique.", "No pay item.")).append("</td></tr>");
        }

        StringBuilder head = new StringBuilder();
        for (String h : headers(lang)) head.append("<th>").append(escape(h)).append("</th>");

        return """
                <!DOCTYPE html>
                <html lang="%s">
                <head>
                <meta charset="UTF-8"/>
                <title>%s</title>
                <style>
                  @page { size: A4 landscape; margin: 14mm 12mm; }
                  body { font-family: Arial, sans-serif; font-size: 11px; color: #191c1e; margin: 0; }
                  h1 { font-size: 17px; margin: 0 0 4px; }
                  .sub { font-size: 11px; color: #5f6368; margin-bottom: 16px; }
                  .badge { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 10px; font-weight: 700; margin-left: 6px; }
                  .badge.ok { background: #dcfce7; color: #166534; }
                  .badge.ko { background: #fee2e2; color: #991b1b; }
                  .summary { display: flex; gap: 10px; margin-bottom: 18px; }
                  .tile { flex: 1; border: 1px solid #dadce0; border-radius: 6px; padding: 8px 10px; }
                  .tile .k { font-size: 10px; color: #5f6368; text-transform: uppercase; letter-spacing: .04em; }
                  .tile .v { font-size: 15px; font-weight: 700; margin-top: 2px; font-variant-numeric: tabular-nums; }
                  .tile.net { background: #eef2ff; border-color: #c7d2fe; }
                  .tile .v.neg { color: #b91c1c; }
                  h2 { font-size: 13px; margin: 0 0 8px; }
                  table { width: 100%%; border-collapse: collapse; }
                  th, td { padding: 6px 8px; border-bottom: 1px solid #e5e7eb; text-align: left; }
                  th { background: #f3f4f6; font-weight: 600; font-size: 10px; text-transform: uppercase; letter-spacing: .03em; }
                  thead { display: table-header-group; }
                  tr { page-break-inside: avoid; }
                  .code { font-family: monospace; font-size: 10px; }
                  .c { text-align: center; }
                  .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
                  .neg { color: #b91c1c; }
                  .empty { text-align: center; color: #5f6368; padding: 16px; }
                </style>
                </head>
                <body>
                <h1>%s %s</h1>
                <div class="sub">%s · %s %d · %s %s</div>
                <div class="summary">
                  <div class="tile"><div class="k">%s</div><div class="v">%s</div></div>
                  <div class="tile"><div class="k">%s</div><div class="v neg">− %s</div></div>
                  <div class="tile"><div class="k">IRPP</div><div class="v neg">− %s</div></div>
                  <div class="tile net"><div class="k">%s</div><div class="v">%s</div></div>
                </div>
                <h2>%s</h2>
                <table>
                  <thead><tr>%s</tr></thead>
                  <tbody>%s</tbody>
                </table>
                </body>
                </html>
                """.formatted(
                lang == ExportLang.EN ? "en" : "fr",
                escape(title),
                escape(title), convergence,
                who, lang.pick("Résultat n°", "Result #"), r.getId(), lang.pick("calculé le", "calculated on"), calculated,
                lang.pick("Salaire brut", "Gross salary"), fmt(money, gross),
                lang.pick("Charges salariales (CNSS)", "Employee contributions (CNSS)"), fmt(money, cnss),
                fmt(money, irpp),
                lang.pick("Net à payer", "Net pay"), fmt(money, net),
                lang.pick("Détail par rubrique", "Breakdown by pay item"),
                head, body);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Même contrôle de périmètre pays que les exports de simulation ({@code findInScope}). */
    private PayrollResult findInScope(Long resultId) {
        PayrollResult r = resultRepo.findById(resultId)
                .orElseThrow(() -> new EntityNotFoundException("Résultat de paie introuvable : " + resultId));
        if (!isSuperAdmin()) {
            PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
            if (r.getPaysId() == null || scope == null || !scope.allows(r.getPaysId())) {
                throw new AccessDeniedException("Résultat de paie hors de votre périmètre pays");
            }
        }
        return r;
    }

    /** Lignes du résultat, dans l'ordre par défaut du tableau (strate puis code). */
    private List<Line> lines(PayrollResult r) {
        if (r.getRubriqueDetails() == null || r.getRubriqueDetails().isBlank()) return List.of();
        try {
            List<Line> all = objectMapper.readValue(r.getRubriqueDetails(), new TypeReference<List<Line>>() {});
            return all.stream()
                    .sorted(Comparator.comparingInt(Line::strate).thenComparing(Line::rubriqueCode, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Détail des rubriques illisible pour le résultat " + r.getId(), e);
        }
    }

    private static List<String> headers(ExportLang lang) {
        return List.of(
                "Code", lang.pick("Libellé", "Label"), lang.pick("Strate", "Tier"), "Nature",
                lang.pick("Assiette", "Base"), lang.pick("Montant", "Amount"), "Mode");
    }

    /** Libellé anglais quand il existe, comme {@code rubriqueLineLabel} côté front. */
    private static String label(Line l, ExportLang lang) {
        return lang == ExportLang.EN && l.labelEn() != null && !l.labelEn().isBlank() ? l.labelEn() : l.labelFr();
    }

    /** Retenues en négatif, comme le tableau à l'écran. */
    private static BigDecimal signed(Line l) {
        if (l.amount() == null) return null;
        return "RETENUE".equals(l.nature()) ? l.amount().abs().negate() : l.amount();
    }

    private static String period(PayrollResult r, ExportLang lang) {
        String m = Month.of(r.getPeriodMonth()).getDisplayName(TextStyle.FULL, lang.locale());
        return Character.toUpperCase(m.charAt(0)) + m.substring(1) + " " + r.getPeriodYear();
    }

    private static String filename(PayrollResult r, String ext) {
        return "Bulletin_" + r.getEmployeeId() + "_" + r.getPeriodYear() + "-"
                + String.format("%02d", r.getPeriodMonth()) + "." + ext;
    }

    private static DecimalFormat money(ExportLang lang) {
        DecimalFormatSymbols s = DecimalFormatSymbols.getInstance(lang.locale());
        if (lang == ExportLang.FR) s.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.00", s);
    }

    private static String fmt(DecimalFormat f, BigDecimal v) {
        return v == null ? "—" : f.format(v);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "PAYROLL_SUPER_ADMIN".equals(a.getAuthority()));
    }
}
