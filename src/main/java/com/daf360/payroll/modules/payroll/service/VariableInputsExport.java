package com.daf360.payroll.modules.payroll.service;

import com.daf360.payroll.common.export.ExportLang;
import com.daf360.payroll.common.export.XlsxExport.RowFormat;
import com.daf360.payroll.common.export.XlsxExport.SheetData;
import com.daf360.payroll.modules.payroll.entity.PayrollResult;
import com.daf360.payroll.modules.payroll.variable.PayrollVariableInputs;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The « éléments variables » snapshot of a payroll result ({@code payroll_results
 * .variable_inputs_json}, V41/V42) in the payslip exports — the same information as the payslip
 * page: pay period, leave balances (start → end of the period), weekend overtime day by day,
 * absences, entry / exit, autorisations above quota, bonuses, salary-advance instalments taken
 * from the net, rates, and the warnings of the run.
 *
 * Results calculated before V41 have no snapshot: {@link #read} returns null and the exports stay
 * as they were.
 */
final class VariableInputsExport {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private VariableInputsExport() {}

    /** The snapshot, or null when absent / unreadable (never fails an export). */
    static PayrollVariableInputs read(ObjectMapper mapper, PayrollResult r) {
        String json = r.getVariableInputsJson();
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readerFor(PayrollVariableInputs.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    static String date(LocalDate d) {
        return d == null ? "" : d.format(DMY);
    }

    static String periodLabel(PayrollVariableInputs vi, ExportLang lang) {
        if (vi == null || vi.periodFrom() == null || vi.periodTo() == null) return null;
        return lang.pick("Période de paie : du ", "Pay period: ") + date(vi.periodFrom())
                + lang.pick(" au ", " to ") + date(vi.periodTo());
    }

    static String balanceLabel(String field, ExportLang lang) {
        if ("CONGE".equals(field)) return lang.pick("Congé", "Annual leave");
        if ("MALADIE".equals(field)) return lang.pick("Maladie", "Sick leave");
        return field;
    }

    private static String dayName(String dow, ExportLang lang) {
        try {
            String s = DayOfWeek.valueOf(dow).getDisplayName(TextStyle.FULL, lang.locale());
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        } catch (Exception e) {
            return dow;
        }
    }

    private static String pct(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " %";
    }

    /** « (collaborateur) » / « (rôle) » / « (pays) » after the cap — nothing before V43. */
    private static String capSource(String source, ExportLang lang) {
        if (source == null) return "";
        return switch (source) {
            case "EMPLOYEE" -> lang.pick(" (collaborateur)", " (employee)");
            case "ROLE" -> lang.pick(" (rôle)", " (role)");
            case "COUNTRY" -> lang.pick(" (pays)", " (country)");
            default -> lang.pick(" (par défaut)", " (default)");
        };
    }

    private static BigDecimal neg(BigDecimal v) {
        return v == null ? null : v.abs().negate();
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    // ── Excel ────────────────────────────────────────────────────────────────

    /** Sheet « Éléments variables »: one bold line per element, its detail lines grouped under it. */
    static SheetData variableSheet(PayrollVariableInputs vi, ExportLang lang) {
        List<List<Object>> rows = new ArrayList<>();
        List<RowFormat> formats = new ArrayList<>();
        RowFormat head = new RowFormat(true, 0, true, -1);
        RowFormat detail = new RowFormat(false, 1, false, 1);
        String h = lang.pick(" h", " h"), d = lang.pick(" j", " d");

        String period = periodLabel(vi, lang);
        if (period != null) {
            rows.add(Arrays.asList(lang.pick("Période de paie", "Pay period"),
                    date(vi.periodFrom()) + " → " + date(vi.periodTo()), null, null, null));
            formats.add(head);
        }

        var ot = vi.overtime();
        if (ot != null && ot.days() != null && !ot.days().isEmpty()) {
            // V43: each day may carry its own majoration — « selon le jour » when they differ.
            long distinctPcts = ot.days().stream().map(x -> x.majorationPct() == null ? "-" : x.majorationPct().stripTrailingZeros().toPlainString())
                    .distinct().count();
            String maj = distinctPcts > 1 ? lang.pick("selon le jour", "per day")
                    : ot.majorationPct() == null ? lang.pick("non configurée", "not configured")
                    : pct(ot.days().get(0).majorationPct() != null ? ot.days().get(0).majorationPct() : ot.majorationPct());
            rows.add(Arrays.asList(lang.pick("Heures supplémentaires (week-end)", "Weekend overtime"),
                    lang.pick("Majoration ", "Premium ") + maj + lang.pick(" · plafond ", " · cap ")
                            + ot.dailyCapHours().stripTrailingZeros().toPlainString() + lang.pick(" h par jour", " h per day")
                            + capSource(ot.capSource(), lang),
                    ot.paidHours(), h.trim(), ot.amount()));
            formats.add(head);
            for (var day : ot.days()) {
                rows.add(Arrays.asList(null,
                        date(day.date()) + " · " + dayName(day.dayOfWeek(), lang) + " · "
                                + lang.pick("travaillées ", "worked ") + day.workedHours().stripTrailingZeros().toPlainString() + h
                                + (day.capped() ? lang.pick(" (plafonnée)", " (capped)") : "")
                                + (day.majorationPct() != null ? lang.pick(" · majoration ", " · premium ") + pct(day.majorationPct()) : ""),
                        day.paidHours(), h.trim(), day.amount()));
                formats.add(detail);
            }
            if (positive(ot.cappedOutHours())) {
                rows.add(Arrays.asList(null, lang.pick("Au-delà du plafond, non payées", "Above the cap, not paid"),
                        ot.cappedOutHours(), h.trim(), null));
                formats.add(detail);
            }
        }

        var abs = vi.absences();
        if (abs != null && abs.items() != null && !abs.items().isEmpty()) {
            rows.add(Arrays.asList(lang.pick("Absences non payées", "Unpaid absences"),
                    lang.pick("Jours retenus × taux journalier", "Deducted days × daily rate"),
                    abs.unpaidDays(), d.trim(), neg(abs.amount())));
            formats.add(head);
            for (var a : abs.items()) {
                rows.add(Arrays.asList(null,
                        a.label() + " · " + date(a.from()) + " → " + date(a.to()) + " · "
                                + (a.deductsSalary() ? lang.pick("retenue", "deducted") : lang.pick("payée", "paid")),
                        a.days(), d.trim(), null));
                formats.add(detail);
            }
        }

        if (vi.nonEmployedDays() > 0) {
            String why = (vi.hireDate() != null && vi.periodFrom() != null && vi.hireDate().isAfter(vi.periodFrom())
                    ? lang.pick("entrée le ", "hired on ") + date(vi.hireDate()) : "")
                    + (vi.exitDate() != null ? lang.pick(" sortie le ", " leaves on ") + date(vi.exitDate()) : "");
            rows.add(Arrays.asList(lang.pick("Entrée / sortie en cours de période", "Entry / exit during the period"),
                    why.trim(), BigDecimal.valueOf(vi.nonEmployedDays()), d.trim(),
                    neg(vi.dailyRate() == null ? null : vi.dailyRate().multiply(BigDecimal.valueOf(vi.nonEmployedDays()))
                            .setScale(2, java.math.RoundingMode.HALF_UP))));
            formats.add(head);
        }

        var aut = vi.autorisations();
        if (aut != null && aut.approvedMinutes() > 0) {
            rows.add(Arrays.asList(lang.pick("Autorisations hors quota", "Autorisations above quota"),
                    aut.approvedMinutes() + lang.pick(" min approuvées · quota ", " min approved · quota ")
                            + (aut.monthlyQuotaMinutes() == null ? lang.pick("aucun", "none") : aut.monthlyQuotaMinutes() + " min"),
                    BigDecimal.valueOf(aut.deductedMinutes()), "min",
                    positive(aut.amount()) ? neg(aut.amount()) : null));
            formats.add(head);
        }

        if (vi.bonuses() != null && !vi.bonuses().isEmpty()) {
            rows.add(Arrays.asList(lang.pick("Primes du mois", "Bonuses of the month"), null, null, null, vi.bonusesTotal()));
            formats.add(head);
            for (var b : vi.bonuses()) {
                rows.add(Arrays.asList(null, b.label(), null, null, b.amountConverted()));
                formats.add(detail);
            }
        }

        if (vi.advances() != null && !vi.advances().isEmpty()) {
            rows.add(Arrays.asList(lang.pick("Avance sur salaire", "Salary advance"),
                    lang.pick("Retenue sur le net à payer (hors brut, CNSS et IRPP)", "Taken from the net pay (not in gross, CNSS or tax)"),
                    null, null, neg(vi.advancesTotal())));
            formats.add(head);
            for (var a : vi.advances()) {
                rows.add(Arrays.asList(null, lang.pick("Échéance n° ", "Instalment #") + a.seq(), null, null, neg(a.amountConverted())));
                formats.add(detail);
            }
        }

        rows.add(Arrays.asList(lang.pick("Taux", "Rates"),
                lang.pick("Taux horaire (", "Hourly rate (") + vi.hoursPerWeek().stripTrailingZeros().toPlainString()
                        + lang.pick(" h/semaine) · taux journalier (", " h/week) · daily rate (") + vi.workingDaysInMonth()
                        + lang.pick(" j ouvrés)", " working days)"),
                null, null, null));
        formats.add(head);
        rows.add(Arrays.asList(null, lang.pick("Taux horaire", "Hourly rate"), null, null, vi.hourlyRate()));
        formats.add(detail);
        rows.add(Arrays.asList(null, lang.pick("Taux journalier", "Daily rate"), null, null, vi.dailyRate()));
        formats.add(detail);

        if (vi.warnings() != null && !vi.warnings().isEmpty()) {
            rows.add(Arrays.asList(lang.pick("Avertissements", "Warnings"), null, null, null, null));
            formats.add(head);
            for (String w : vi.warnings()) {
                rows.add(Arrays.asList(null, w, null, null, null));
                formats.add(detail);
            }
        }

        return new SheetData(lang.pick("Éléments variables", "Variable elements"),
                List.of(lang.pick("Élément", "Element"), lang.pick("Détail", "Detail"),
                        lang.pick("Quantité", "Quantity"), lang.pick("Unité", "Unit"), lang.pick("Montant", "Amount")),
                rows, formats);
    }

    /** Sheet « Soldes de congés » — null when the snapshot carries no balance (before V42). */
    static SheetData balancesSheet(PayrollVariableInputs vi, ExportLang lang) {
        if (vi.balances() == null || vi.balances().isEmpty()) return null;
        List<List<Object>> rows = new ArrayList<>();
        for (var b : vi.balances()) {
            rows.add(Arrays.asList(balanceLabel(b.field(), lang), b.atPeriodStart(), b.takenInPeriod(), b.atPeriodEnd()));
        }
        String period = periodLabel(vi, lang);
        if (period != null) rows.add(Arrays.asList(period, null, null, null));
        rows.add(Arrays.asList(lang.pick(
                "Recalculés à partir du solde actuel et des absences approuvées (une correction manuelle du solde n'est pas datée).",
                "Rebuilt from the current balance and the approved leaves (a manual balance correction carries no date)."),
                null, null, null));
        return new SheetData(lang.pick("Soldes de congés", "Leave balances"),
                List.of(lang.pick("Solde", "Balance"), lang.pick("Début de période (j)", "Start of period (d)"),
                        lang.pick("Pris dans la période (j)", "Taken in the period (d)"),
                        lang.pick("Fin de période (j)", "End of period (d)")),
                rows);
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    /** CSS of the blocks below, appended to the payslip's own style sheet. */
    static final String PDF_CSS = """
              .period { font-size: 11px; font-weight: 700; margin: -8px 0 14px; }
              .balances { display: flex; gap: 10px; margin-bottom: 18px; }
              .bal { flex: 1; border: 1px solid #dadce0; border-radius: 6px; padding: 8px 10px; }
              .bal .k { font-size: 10px; color: #5f6368; text-transform: uppercase; letter-spacing: .04em; }
              .bal .v { font-size: 15px; font-weight: 700; margin-top: 2px; color: #1a56db; }
              .bal .s { font-size: 10px; color: #5f6368; margin-top: 2px; }
              .vi { margin-top: 18px; page-break-inside: auto; }
              .vi td.d { padding-left: 22px; color: #3c4043; }
              .vi tr.h td { font-weight: 700; background: #fafafa; }
              .warn { margin-top: 10px; padding: 6px 10px; border-radius: 6px; background: #fef3c7; color: #92400e; font-size: 10px; }
              .note { font-size: 9px; color: #5f6368; margin: -12px 0 16px; }
            """;

    /** Pay period line + balance tiles, placed under the payslip summary. Empty when no snapshot. */
    static String pdfTop(PayrollVariableInputs vi, ExportLang lang, DecimalFormat num) {
        if (vi == null) return "";
        StringBuilder sb = new StringBuilder();
        String period = periodLabel(vi, lang);
        if (period != null) sb.append("<div class=\"period\">").append(esc(period)).append("</div>");
        if (vi.balances() != null && !vi.balances().isEmpty()) {
            sb.append("<div class=\"balances\">");
            for (var b : vi.balances()) {
                boolean known = b.atPeriodStart() != null && b.atPeriodEnd() != null;
                sb.append("<div class=\"bal\"><div class=\"k\">").append(esc(balanceLabel(b.field(), lang)))
                  .append(lang.pick(" · fin de période", " · end of period")).append("</div>")
                  .append("<div class=\"v\">").append(known ? days(num, b.atPeriodEnd(), lang) : lang.pick("Non renseigné", "Not recorded")).append("</div>")
                  .append("<div class=\"s\">").append(lang.pick("Début : ", "Start: ")).append(known ? days(num, b.atPeriodStart(), lang) : "—")
                  .append(lang.pick(" · pris : ", " · taken: ")).append(days(num, b.takenInPeriod() == null ? BigDecimal.ZERO : b.takenInPeriod(), lang))
                  .append("</div></div>");
            }
            sb.append("</div><div class=\"note\">").append(esc(lang.pick(
                    "Soldes recalculés à partir du solde actuel et des absences approuvées.",
                    "Balances rebuilt from the current balance and the approved leaves."))).append("</div>");
        }
        return sb.toString();
    }

    /** « Éléments variables » table, after the pay-item breakdown. Empty when no snapshot. */
    static String pdfVariable(PayrollVariableInputs vi, ExportLang lang, DecimalFormat money, DecimalFormat num) {
        if (vi == null) return "";
        SheetData sheet = variableSheet(vi, lang);
        StringBuilder sb = new StringBuilder("<div class=\"vi\"><h2>")
                .append(esc(lang.pick("Éléments variables", "Variable elements"))).append("</h2><table><thead><tr>");
        for (int i = 0; i < sheet.headers().size(); i++) {
            sb.append(i >= 2 && i != 3 ? "<th class=\"num\">" : "<th>").append(esc(sheet.headers().get(i))).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        boolean inWarnings = false;
        for (int i = 0; i < sheet.rows().size(); i++) {
            List<Object> row = sheet.rows().get(i);
            boolean isHead = sheet.formats().get(i).outlineLevel() == 0;
            if (isHead) inWarnings = lang.pick("Avertissements", "Warnings").equals(row.get(0));
            if (inWarnings) continue;   // warnings are printed in a box below the table
            sb.append(isHead ? "<tr class=\"h\">" : "<tr>");
            sb.append("<td>").append(esc(str(row.get(0)))).append("</td>");
            sb.append(isHead ? "<td>" : "<td class=\"d\">").append(esc(str(row.get(1)))).append("</td>");
            sb.append("<td class=\"num\">").append(row.get(2) instanceof BigDecimal q ? num.format(q) : "").append("</td>");
            sb.append("<td>").append(esc(str(row.get(3)))).append("</td>");
            Object amount = row.get(4);
            sb.append("<td class=\"num").append(amount instanceof BigDecimal a && a.signum() < 0 ? " neg" : "").append("\">")
              .append(amount instanceof BigDecimal a ? money.format(a) : "").append("</td></tr>");
        }
        sb.append("</tbody></table>");
        if (vi.warnings() != null) {
            for (String w : vi.warnings()) sb.append("<div class=\"warn\">⚠ ").append(esc(w)).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private static String days(DecimalFormat num, BigDecimal v, ExportLang lang) {
        return num.format(v) + lang.pick(" j", " d");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
